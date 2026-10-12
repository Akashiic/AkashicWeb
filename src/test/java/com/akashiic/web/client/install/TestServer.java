package com.akashiic.web.client.install;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A loopback HTTP server shaped like GitHub release downloads, for installer tests:
 * <ul>
 * <li>{@code /canonical/<name>} answers 302 to {@code /signed/<name>?sig=<n>}, a signature valid for ONE request
 * (expired signed URLs are why the installer must re-follow redirects on every attempt);</li>
 * <li>{@code /direct/<name>} serves the entity without a redirect;</li>
 * <li>Range {@code bytes=N-} with If-Range against a strong ETag: 206 when it matches, 200 with the whole entity when
 * not (RFC 9110 semantics);</li>
 * <li>failure injection: cut the connection after N body bytes, queued status codes, a wrong Content-Length.</li>
 * </ul>
 */
final class TestServer implements AutoCloseable {

    /** One request as received. */
    static final class Request {

        final String path;
        final String range;
        final String ifRange;
        final String userAgent;

        Request(String path, String range, String ifRange, String userAgent) {
            this.path = path;
            this.range = range;
            this.ifRange = ifRange;
            this.userAgent = userAgent;
        }
    }

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-");

    private final HttpServer server;
    volatile byte[] body = new byte[0];
    volatile String etag = "\"v1\"";
    /** Bytes of the next entity response to send before cutting the connection; -1 = no cut. One-shot. */
    final Deque<Long> cuts = new ArrayDeque<>();
    /** Status codes to answer (once each) before serving normally. */
    final Deque<Integer> statuses = new ArrayDeque<>();
    /** Answer every entity request with this status (e.g. 404); 0 = off. */
    volatile int persistentStatus;
    /** Declared Content-Length for 200 responses instead of the real one; -1 = real. */
    volatile long contentLengthOverride = -1;
    final AtomicLong bytesServed = new AtomicLong();
    /** When set, the body pauses once at this offset until {@link #release} opens; {@link #reached} reports it. */
    volatile long gateAt = -1;
    final CountDownLatch reached = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger canonicalHits = new AtomicInteger();
    final AtomicInteger entityHits = new AtomicInteger();
    final List<Request> requests = Collections.synchronizedList(new ArrayList<Request>());
    private final Set<String> usedSignatures = Collections.synchronizedSet(new HashSet<String>());
    private final AtomicInteger signatures = new AtomicInteger();

    TestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        server.createContext("/", this::handle);
        server.start();
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String canonicalUrl(String name) {
        return base() + "/canonical/" + name;
    }

    String directUrl(String name) {
        return base() + "/direct/" + name;
    }

    int requestCount() {
        return requests.size();
    }

    /** Requests that reached the entity (not the redirects). */
    List<Request> entityRequests() {
        List<Request> out = new ArrayList<>();
        synchronized (requests) {
            for (Request r : requests) {
                if (!r.path.startsWith("/canonical/")) out.add(r);
            }
        }
        return out;
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        requests.add(new Request(
            path + (query == null ? "" : "?" + query),
            ex.getRequestHeaders().getFirst("Range"),
            ex.getRequestHeaders().getFirst("If-Range"),
            ex.getRequestHeaders().getFirst("User-Agent")));
        try {
            if (path.startsWith("/canonical/")) {
                canonicalHits.incrementAndGet();
                String sig = "s" + signatures.incrementAndGet();
                String name = path.substring("/canonical/".length());
                ex.getResponseHeaders().set("Location", "/signed/" + name + "?sig=" + sig);
                ex.sendResponseHeaders(302, -1);
                return;
            }
            if (path.startsWith("/signed/")) {
                String sig = query == null ? "" : query.replace("sig=", "");
                if (sig.isEmpty() || !usedSignatures.add(sig)) {
                    ex.sendResponseHeaders(403, -1); // an expired signed URL, as GitHub would answer
                    return;
                }
            } else if (!path.startsWith("/direct/")) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            serveEntity(ex);
        } finally {
            ex.close();
        }
    }

    private void serveEntity(HttpExchange ex) throws IOException {
        entityHits.incrementAndGet();
        Integer queued;
        synchronized (statuses) {
            queued = statuses.poll();
        }
        int status = queued != null ? queued : persistentStatus;
        if (status != 0) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        byte[] b = body;
        String tag = etag;
        long from = 0;
        String range = ex.getRequestHeaders().getFirst("Range");
        String ifRange = ex.getRequestHeaders().getFirst("If-Range");
        if (range != null) {
            Matcher m = RANGE.matcher(range);
            if (m.matches() && (ifRange == null || ifRange.equals(tag))) {
                from = Long.parseLong(m.group(1));
                if (from >= b.length) {
                    ex.getResponseHeaders().set("Content-Range", "bytes */" + b.length);
                    ex.sendResponseHeaders(416, -1);
                    return;
                }
            }
        }
        ex.getResponseHeaders().set("ETag", tag);
        ex.getResponseHeaders().set("Accept-Ranges", "bytes");
        long length = b.length - from;
        if (from > 0) {
            ex.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + (b.length - 1) + "/" + b.length);
            ex.sendResponseHeaders(206, length);
        } else {
            long declared = contentLengthOverride >= 0 ? contentLengthOverride : length;
            ex.sendResponseHeaders(200, declared);
            if (declared != length) return; // the client must give up on the header alone
        }
        Long cut;
        synchronized (cuts) {
            cut = cuts.poll();
        }
        long toSend = cut == null || cut < 0 ? length : Math.min(cut, length);
        OutputStream out = ex.getResponseBody();
        int off = (int) from;
        long left = toSend;
        try {
            while (left > 0) {
                if (gateAt >= 0 && off >= gateAt) {
                    gateAt = -1;
                    out.flush();
                    reached.countDown();
                    awaitRelease();
                }
                int n = (int) Math.min(8192, left);
                out.write(b, off, n);
                off += n;
                left -= n;
                bytesServed.addAndGet(n);
            }
            out.flush();
            if (toSend < length) {
                // Closing with bytes missing makes the JDK server drop the connection: the client sees a short body.
                throw new IOException("cut");
            }
        } catch (IOException e) {
            // Expected when cutting, or when the client aborted (cancel test).
        }
    }

    private void awaitRelease() {
        try {
            release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
