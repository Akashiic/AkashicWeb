package com.akashiic.web.client.host;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.protocol.Protocol;

/**
 * One authenticated connection to the host (docs/PROTOCOL.md section 1), created by {@link HostSupervisor} after the
 * handshake.
 *
 * <p>
 * Threads. One daemon reader, {@value #READER_THREAD}, parses host messages, streams FRAME pixels into the screen's
 * canvas and calls {@link Inbound}; it never touches Minecraft. Sends never block the caller: each typed helper
 * encodes the payload, takes a sequence number and puts the message on a bounded queue that one daemon writer thread
 * drains under the writer lock. The game's client thread therefore never waits on a socket, even if the host's main
 * thread hangs; if the queue fills up the host is considered stalled and the connection is closed (the supervisor
 * relaunches it).
 *
 * <p>
 * Whatever ends the reader or the writer (EOF, a protocol violation, or an unexpected exception or Error such as a
 * direct-memory OutOfMemoryError) closes the connection, so the supervisor always sees the end of a run: a dead thread
 * never leaves a READY status with frozen frames behind. A FRAME of a page too large to hold is skipped and fails only
 * its screen ({@link Inbound#onFrameRejected}); the connection stays up.
 */
public final class HostConnection {

    public static final String READER_THREAD = "AkashicWeb-Host-Reader";
    public static final String WRITER_THREAD = "AkashicWeb-Host-Writer";

    /** Outgoing messages buffered before the host counts as stalled. Control messages are small (under 1 KB). */
    static final int QUEUE_CAPACITY = 4096;
    /** STATE/MEDIA/EVENT/PONG/BYE larger than this are skipped (logged), not fatal. */
    static final int MAX_TEXT_PAYLOAD = 256 * 1024;
    private static final int SCRATCH_BYTES = 256 * 1024;
    private static final int OUT_BUFFER = 64 * 1024;

    private static final Logger LOG = LogManager.getLogger("AkashicWeb/Host");

    /** Where host messages go. Called on the reader thread. */
    public interface Inbound {

        /** Frame path of a screen, or null to discard its frames (unknown or destroyed screen). */
        FramePipeline framesFor(int screenId);

        void onPcm(int screenId, PcmChunk chunk);

        void onState(int screenId, String json);

        void onMedia(int screenId, String json);

        void onEvent(int screenId, String json);

        /**
         * A FRAME of screen {@code screenId} was skipped because its {@code width x height} page does not
         * {@link FrameCanvas#fits fit}; called once per page size (later frames of that size are skipped quietly).
         */
        default void onFrameRejected(int screenId, int width, int height) {}
    }

    private static final class Outgoing {

        final int type;
        final int screenId;
        final int seq;
        final byte[] payload;

        Outgoing(int type, int screenId, int seq, byte[] payload) {
            this.type = type;
            this.screenId = screenId;
            this.seq = seq;
            this.payload = payload;
        }
    }

    /** Marker that tells the writer to flush and stop (graceful shutdown after QUIT). */
    private static final Outgoing END = new Outgoing(-1, 0, 0, null);

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Inbound inbound;
    private final ArrayBlockingQueue<Outgoing> queue;

    private final Object enqueueLock = new Object();
    private final Object writerLock = new Object();
    /** Next outgoing seq per screen (per direction, per screen, wrapping). Guarded by enqueueLock. */
    private final Map<Integer, Integer> seqs = new HashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private final CountDownLatch endWritten = new CountDownLatch(1);
    private volatile HostFailure closeReason;
    private volatile String closeDetail = "";
    private volatile String byeReason;

    private final AtomicLong nextNonce = new AtomicLong(1);
    private volatile long pingNonce;
    private volatile long pingSentNanos;
    private volatile long lastRttMillis = -1;
    private volatile long messagesIn;
    private volatile long messagesOut;
    private volatile boolean welcomeWritten;

    private Thread reader;
    private Thread writer;

    /**
     * {@code in} must be the stream the handshake read HELLO from: its buffer may already hold the next messages.
     */
    HostConnection(Socket socket, DataInputStream in, Inbound inbound) throws IOException {
        this(socket, in, inbound, QUEUE_CAPACITY);
    }

    HostConnection(Socket socket, DataInputStream in, Inbound inbound, int queueCapacity) throws IOException {
        this.socket = socket;
        this.in = in;
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), OUT_BUFFER));
        this.inbound = inbound;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
    }

    /** Starts the reader and writer threads. Messages queued before this are sent first, in order. */
    synchronized void start() {
        if (reader != null) return;
        reader = new Thread(this::readLoop, READER_THREAD);
        reader.setDaemon(true);
        writer = new Thread(this::writeLoop, WRITER_THREAD);
        writer.setDaemon(true);
        writer.start();
        reader.start();
    }

    // --- typed sends (game -> host). Each returns false when the message could not be queued. ---------------------

    public boolean sendWelcome(SitePolicy policy, String locale, String userAgent) {
        return enqueue(HostProtocol.WELCOME, 0, HostMessages.welcome(Protocol.MAJOR, policy, locale, userAgent));
    }

    public boolean sendCreate(int screenId, CreateSpec spec) {
        return enqueue(HostProtocol.CREATE, screenId, HostMessages.create(spec));
    }

    public boolean sendDestroy(int screenId) {
        boolean ok = enqueue(HostProtocol.DESTROY, screenId, null);
        synchronized (enqueueLock) {
            seqs.remove(screenId);
        }
        return ok;
    }

    public boolean sendNavigate(int screenId, String url) {
        return enqueue(HostProtocol.NAVIGATE, screenId, HostMessages.navigate(url));
    }

    public boolean sendAction(int screenId, String action) {
        return enqueue(HostProtocol.NAVIGATE, screenId, HostMessages.action(action));
    }

    public boolean sendInput(int screenId, InputEvent event) {
        return enqueue(HostProtocol.INPUT, screenId, HostMessages.input(event));
    }

    public boolean sendPolicy(SitePolicy policy) {
        return enqueue(HostProtocol.POLICY, 0, HostMessages.policy(policy));
    }

    public boolean sendAudio(int screenId, boolean muted, float volume) {
        return enqueue(HostProtocol.AUDIO, screenId, HostMessages.audio(muted, volume));
    }

    public boolean sendRender(int screenId, int fps, boolean painting) {
        return enqueue(HostProtocol.RENDER, screenId, HostMessages.render(fps, painting));
    }

    public boolean sendResize(int screenId, int width, int height) {
        return enqueue(HostProtocol.RESIZE, screenId, HostMessages.resize(width, height));
    }

    public boolean sendSync(int screenId, SyncCommand cmd) {
        String json = HostMessages.sync(cmd);
        return json != null && enqueue(HostProtocol.SYNC, screenId, json);
    }

    /**
     * PING with a fresh nonce; the PONG updates {@link #lastRttMillis()}. The supervisor sends one only when none is
     * outstanding ({@link #pingOutstanding()}), so {@link #pongOverdueMillis()} counts from the unanswered one.
     */
    public boolean sendPing() {
        long nonce = nextNonce.getAndIncrement();
        pingSentNanos = System.nanoTime(); // before the nonce: whoever sees the nonce sees its send time
        pingNonce = nonce;
        return enqueue(HostProtocol.PING, 0, HostMessages.ping(nonce));
    }

    public boolean sendQuit() {
        return enqueue(HostProtocol.QUIT, 0, null);
    }

    // --- lifecycle ---------------------------------------------------------------------------------------------

    /** Closes once; the first reason wins. Safe from any thread. */
    public void close(HostFailure reason, String detail) {
        if (!closed.compareAndSet(false, true)) return;
        closeReason = reason;
        closeDetail = detail == null ? "" : detail;
        try {
            socket.close(); // unblocks the reader and a writer stuck in a socket write
        } catch (IOException e) {
            // Closing a socket that already failed: nothing left to release.
        }
        Thread w = writer;
        if (w != null && w != Thread.currentThread()) w.interrupt();
        endWritten.countDown();
        closedLatch.countDown();
    }

    /**
     * Graceful end for shutdown: lets the writer send everything queued (normally a final QUIT), waits up to
     * {@code millis}, then closes.
     */
    public void flushAndClose(long millis) {
        boolean queued;
        synchronized (enqueueLock) {
            queued = !closed.get() && queue.offer(END);
        }
        if (queued) {
            try {
                endWritten.await(millis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
        }
        close(HostFailure.SHUTDOWN, "shutdown");
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Waits for the connection to close; true when it is closed. */
    public boolean awaitClosed(long millis) throws InterruptedException {
        return closedLatch.await(millis, TimeUnit.MILLISECONDS);
    }

    public HostFailure closeReason() {
        return closeReason;
    }

    public String closeDetail() {
        String bye = byeReason;
        return bye == null ? closeDetail : closeDetail + " (BYE " + bye + ")";
    }

    /** Round trip of the last answered PING in ms, or -1. */
    public long lastRttMillis() {
        return lastRttMillis;
    }

    /** True when a PING has been waiting longer than {@code millis} for its PONG. */
    public boolean pongOverdue(long millis) {
        return pingNonce != 0 && System.nanoTime() - pingSentNanos > millis * 1_000_000L;
    }

    /** True while a PING waits for its PONG. */
    public boolean pingOutstanding() {
        return pingNonce != 0;
    }

    /** How long the outstanding PING has waited, in ms, or -1 when none is outstanding. */
    public long pongOverdueMillis() {
        if (pingNonce == 0) return -1;
        return Math.max(0, (System.nanoTime() - pingSentNanos) / 1_000_000L);
    }

    public long messagesIn() {
        return messagesIn;
    }

    public long messagesOut() {
        return messagesOut;
    }

    /**
     * True once WELCOME was written to the socket. The supervisor reads a host exit code 68 with it: after WELCOME the
     * host refused it, before WELCOME the host gave up waiting for it.
     */
    public boolean welcomeSent() {
        return welcomeWritten;
    }

    // --- internals -----------------------------------------------------------------------------------------------

    private boolean enqueue(int type, int screenId, String json) {
        byte[] payload = json == null ? null : json.getBytes(StandardCharsets.UTF_8);
        if (payload != null && payload.length > HostProtocol.MAX_PAYLOAD) {
            LOG.error("AkashicWeb: dropping an oversized host message (type {}, {} bytes)", type, payload.length);
            return false;
        }
        boolean stalled;
        synchronized (enqueueLock) {
            if (closed.get()) return false;
            Integer next = seqs.get(screenId);
            int seq = next == null ? 0 : next;
            stalled = !queue.offer(new Outgoing(type, screenId, seq, payload));
            if (!stalled) seqs.put(screenId, seq + 1); // int overflow wraps, as the protocol says
        }
        if (stalled) {
            close(HostFailure.WRITE_STALLED, "host stopped reading (" + queue.size() + " messages queued)");
            return false;
        }
        return true;
    }

    private void writeLoop() {
        boolean graceful = false;
        try {
            while (true) {
                Outgoing m = queue.take();
                synchronized (writerLock) {
                    if (m == END) {
                        out.flush();
                        graceful = true; // flushAndClose() closes with SHUTDOWN once it sees endWritten
                        endWritten.countDown();
                        return;
                    }
                    HostProtocol.write(out, m.type, m.screenId, m.seq, m.payload);
                    messagesOut++;
                    if (m.type == HostProtocol.WELCOME) welcomeWritten = true;
                    if (queue.isEmpty()) out.flush(); // batch bursts (input), flush when idle
                }
            }
        } catch (InterruptedException e) {
            // close() interrupts us: the connection is over.
        } catch (IOException e) {
            close(HostFailure.CONNECTION_LOST, "write: " + e);
        } catch (Throwable t) {
            // Boundary: an Error (OutOfMemoryError, LinkageError) or a bug must end the connection visibly too. Close
            // first: logging may itself fail when memory is short.
            close(HostFailure.INTERNAL_ERROR, "writer: " + t);
            LOG.error("AkashicWeb: host writer failed", t);
        } finally {
            if (!graceful) close(HostFailure.INTERNAL_ERROR, "writer ended"); // no-op when already closed
        }
    }

    private void readLoop() {
        try {
            byte[] scratch = new byte[SCRATCH_BYTES];
            byte[] frameHead = new byte[HostProtocol.FrameHeader.BYTES];
            while (!closed.get()) {
                HostProtocol.Header h = HostProtocol.readHeader(in);
                if (h == null) {
                    close(HostFailure.CONNECTION_LOST, "EOF");
                    return;
                }
                messagesIn++;
                dispatch(h, scratch, frameHead);
            }
        } catch (HostProtocol.ProtocolException e) {
            close(HostFailure.PROTOCOL_ERROR, e.getMessage());
        } catch (IOException e) {
            close(HostFailure.CONNECTION_LOST, e.toString());
        } catch (Throwable t) {
            // Boundary: a RuntimeException or an Error from the frame path (a direct-memory OutOfMemoryError from
            // ByteBuffer.allocateDirect) or from a listener must end this connection visibly, never leave a dead
            // reader behind a READY status. Close first: logging may itself fail when memory is short.
            close(HostFailure.INTERNAL_ERROR, "reader: " + t);
            LOG.error("AkashicWeb: host reader failed", t);
        } finally {
            // Whatever ended the loop, the connection must not outlive its reader (the supervisor waits on close).
            close(HostFailure.INTERNAL_ERROR, "reader ended"); // no-op when already closed
        }
    }

    private void dispatch(HostProtocol.Header h, byte[] scratch, byte[] frameHead) throws IOException {
        switch (h.type) {
            case HostProtocol.FRAME: {
                if (h.length < HostProtocol.FrameHeader.BYTES) throw new HostProtocol.ProtocolException("short FRAME");
                in.readFully(frameHead);
                HostProtocol.FrameHeader f = HostProtocol.FrameHeader.parse(frameHead, h.length);
                FramePipeline pipeline = inbound.framesFor(h.screenId);
                long pixelBytes = h.length - HostProtocol.FrameHeader.BYTES;
                if (pipeline == null) {
                    skip(pixelBytes, scratch);
                } else if (!FrameCanvas.fits(f.width, f.height)) {
                    // A page we cannot hold (e.g. painted at a display scale above 100%). The payload length was
                    // validated, so skip it and fail only this screen; the other screens keep the connection.
                    skip(pixelBytes, scratch);
                    if (pipeline.reject(f.width, f.height)) inbound.onFrameRejected(h.screenId, f.width, f.height);
                } else {
                    pipeline.receive(f, in, scratch);
                }
                break;
            }
            case HostProtocol.PCM: {
                // A valid chunk is at most 1 MiB: refuse a longer one before allocating anything for it.
                if (h.length > PcmChunk.MAX_PAYLOAD)
                    throw new HostProtocol.ProtocolException("PCM payload of " + h.length + " bytes");
                byte[] buf = h.length <= scratch.length ? scratch : new byte[h.length];
                in.readFully(buf, 0, h.length);
                inbound.onPcm(h.screenId, PcmChunk.decode(buf, 0, h.length));
                break;
            }
            case HostProtocol.STATE: {
                String json = readText(h, scratch);
                if (json != null) inbound.onState(h.screenId, json);
                break;
            }
            case HostProtocol.MEDIA: {
                String json = readText(h, scratch);
                if (json != null) inbound.onMedia(h.screenId, json);
                break;
            }
            case HostProtocol.EVENT: {
                String json = readText(h, scratch);
                if (json != null) inbound.onEvent(h.screenId, json);
                break;
            }
            case HostProtocol.PONG:
                onPong(readText(h, scratch));
                break;
            case HostProtocol.BYE:
                onBye(readText(h, scratch));
                break;
            default:
                // Unknown types (and a repeated HELLO) are ignored: forward compatibility within a major.
                skip(h.length, scratch);
        }
    }

    /** UTF-8 text payload, or null (payload skipped) when larger than {@link #MAX_TEXT_PAYLOAD}. */
    private String readText(HostProtocol.Header h, byte[] scratch) throws IOException {
        if (h.length > MAX_TEXT_PAYLOAD) {
            LOG.warn("AkashicWeb: skipping a {}-byte host message of type {}", h.length, h.type);
            skip(h.length, scratch);
            return null;
        }
        in.readFully(scratch, 0, h.length);
        return new String(scratch, 0, h.length, StandardCharsets.UTF_8);
    }

    private void skip(long bytes, byte[] scratch) throws IOException {
        while (bytes > 0) {
            int n = (int) Math.min(scratch.length, bytes);
            in.readFully(scratch, 0, n);
            bytes -= n;
        }
    }

    private void onPong(String json) {
        if (json == null) return;
        try {
            long nonce = JsonParser.integer(JsonParser.parseObject(json), "nonce", -1);
            if (nonce == pingNonce) {
                lastRttMillis = (System.nanoTime() - pingSentNanos) / 1_000_000L;
                pingNonce = 0;
            }
        } catch (JsonParser.JsonException e) {
            LOG.debug("AkashicWeb: malformed PONG ignored: {}", e.getMessage());
        }
    }

    private void onBye(String json) {
        if (json == null) return;
        String reason = "?";
        try {
            String r = JsonParser.string(JsonParser.parseObject(json), "reason");
            if (r != null) reason = HelloInfo.clean(r, HelloInfo.MAX_FIELD);
        } catch (JsonParser.JsonException e) {
            reason = "malformed";
        }
        byeReason = reason;
        LOG.info("AkashicWeb: host says goodbye: {}", reason);
    }
}
