package com.akashiic.web.client.install;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Downloads one pinned asset into {@code <dir>/<asset>.part}, resuming across attempts and game restarts, and
 * returns {@code <dir>/<asset>} only after the whole file matched the pinned SHA-256 (ADR-011 "Install").
 * <ul>
 * <li>Every attempt starts from the canonical URL ({@link HttpFetcher}); only URL, ETag and size are persisted
 * ({@link DownloadState}).</li>
 * <li>A resume sends {@code Range: bytes=<have>-} and {@code If-Range: <etag>}; a 200 instead of a 206 means the
 * entity changed (or Range is unsupported), so the part file restarts from 0 and the SHA-256 pin decides.</li>
 * <li>Every byte read is written before the next read, so an abort loses at most the bytes in flight: a resume
 * re-fetches less than one {@link #CHUNK}.</li>
 * <li>Failures back off exponentially; the attempt counter resets whenever an attempt made progress, so a long, flaky
 * download is not abandoned while it is still moving.</li>
 * <li>A certificate-path failure switches once to the bundled-roots context ({@link InstallerTls}).</li>
 * </ul>
 * Not thread-safe: one download per instance, on the installer thread. {@link #cancel()} may be called from any
 * thread.
 */
public final class Downloader {

    private static final Logger LOG = LogManager.getLogger("AkashicWeb/Installer");

    public static final int CHUNK = 64 * 1024;
    /** Consecutive attempts without progress before giving up. */
    static final int MAX_ATTEMPTS = 6;
    /** Head-room asked of the disk when a write fails, to tell "disk full" from other write errors. */
    static final long WRITE_MARGIN = 16L * 1024 * 1024;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+)");

    /** Sleep hook so tests do not wait for real backoff delays. */
    @FunctionalInterface
    public interface Sleeper {

        void sleep(long ms) throws InterruptedException;
    }

    private final HttpFetcher http;
    private final Backoff backoff;
    private final Sleeper sleeper;
    private final ToLongFunction<File> usableSpace;
    private final int maxAttempts;
    private volatile boolean cancelled;
    private boolean fallbackTls;

    /** Body bytes received over all attempts (tests compare it with the file size to measure re-fetching). */
    volatile long bytesReceived;
    volatile int attempts;

    Downloader(HttpFetcher http, Backoff backoff, Sleeper sleeper, ToLongFunction<File> usableSpace, int maxAttempts) {
        this.http = http;
        this.backoff = backoff;
        this.sleeper = sleeper;
        this.usableSpace = usableSpace;
        this.maxAttempts = maxAttempts;
    }

    /** Stops the download at the next chunk; the part file stays valid for a later resume. */
    public void cancel() {
        cancelled = true;
    }

    boolean usedFallbackTls() {
        return fallbackTls;
    }

    /**
     * @return the verified zip, {@code <dir>/<asset name>}
     * @throws InstallException HASH_MISMATCH, DOWNLOAD_FAILED, TLS_FAILED or DISK_FULL
     */
    public File download(EngineTarget t, File dir, ProgressListener progress) throws InstallException {
        FileOps.ensureDir(dir);
        File done = new File(dir, t.assetName);
        File part = new File(dir, t.assetName + ".part");
        File stateFile = new File(dir, t.assetName + ".state.json");
        if (done.isFile()) {
            if (done.length() == t.size && Sha256.same(hash(done), t.sha256)) return done;
            delete(done);
        }
        boolean restartedAfterMismatch = false;
        while (true) {
            boolean resumed = fetch(t, part, stateFile, progress);
            String actual = hash(part);
            if (Sha256.same(actual, t.sha256)) {
                try {
                    FileOps.move(part, done);
                } catch (IOException e) {
                    throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "cannot rename " + part.getName(), e);
                }
                delete(stateFile);
                return done;
            }
            delete(part);
            delete(stateFile);
            // A resumed file can be corrupt where a plain one is not (a crash mid-write, a server without a strong
            // validator); one clean restart costs one download and settles which case it is.
            if (resumed && !restartedAfterMismatch) {
                restartedAfterMismatch = true;
                LOG.warn("Engine download {} failed its SHA-256 after a resume; downloading it once more", t.assetName);
                continue;
            }
            throw new InstallException(
                InstallFailure.HASH_MISMATCH,
                t.assetName + " sha256 " + actual + " is not the pinned " + t.sha256);
        }
    }

    /** Fills the part file to {@code t.size} bytes. Returns true if any byte came from a resumed (206) response. */
    private boolean fetch(EngineTarget t, File part, File stateFile, ProgressListener progress)
        throws InstallException {
        DownloadState st = DownloadState.read(stateFile);
        if (st == null || !st.matches(t) || part.length() > t.size) {
            delete(part);
            st = DownloadState.fresh(t);
        }
        boolean resumed = false;
        int failures = 0;
        IOException last = null;
        while (part.length() < t.size) {
            checkCancelled();
            long before = part.length();
            try {
                Attempt a = attempt(t, part, stateFile, st, progress);
                st = a.state;
                resumed |= a.resumed;
                if (part.length() >= t.size) break;
                throw new IOException("connection closed at " + part.length() + " of " + t.size + " bytes");
            } catch (InstallException e) {
                if (!e.retryable) throw e;
                last = e;
            } catch (IOException e) {
                if (InstallerTls.isTrustFailure(e) && !fallbackTls) {
                    // Old 8u51 cacerts: retry right away with the bundled roots added (never as the JVM default).
                    LOG.warn("Engine download: certificate not trusted by the JRE ({}); retry with bundled roots", e);
                    fallbackTls = true;
                    continue;
                }
                last = e;
            }
            failures = part.length() > before ? 1 : failures + 1;
            if (failures >= maxAttempts) throw finalFailure(last);
            long delay = backoff.delayMs(failures);
            LOG.info("Engine download attempt failed ({}); retry in {} ms", last, delay);
            try {
                sleeper.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
                throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "interrupted", e);
            }
        }
        return resumed;
    }

    private static final class Attempt {

        final DownloadState state;
        final boolean resumed;

        Attempt(DownloadState state, boolean resumed) {
            this.state = state;
            this.resumed = resumed;
        }
    }

    private Attempt attempt(EngineTarget t, File part, File stateFile, DownloadState st, ProgressListener progress)
        throws IOException {
        attempts++;
        long have = part.length();
        if (have > 0 && st.etag == null) {
            // No validator: resuming could splice two different files together. Start over instead.
            delete(part);
            have = 0;
        }
        boolean ranged = have > 0;
        HttpFetcher.Response r = http.open(t.url, ranged ? have : 0, ranged ? st.etag : null, fallbackTls);
        HttpURLConnection c = r.conn;
        try {
            if (r.code == 206 && ranged) {
                long[] range = parseContentRange(c.getHeaderField("Content-Range"));
                String etag = c.getHeaderField("ETag");
                if (range == null || range[0] != have
                    || range[2] != t.size
                    || (etag != null && !etag.equals(st.etag))) {
                    // A 206 that does not continue our bytes of the same entity: never splice, start over.
                    delete(part);
                    throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "bad 206 from " + r.host, true, null);
                }
                copyBody(c, part, true, t, progress);
                return new Attempt(st, true);
            }
            if (r.code == 200) {
                long length = c.getContentLengthLong();
                if (length >= 0 && length != t.size) {
                    throw new InstallException(
                        InstallFailure.HASH_MISMATCH,
                        r.host + " serves " + length + " bytes, pinned size is " + t.size);
                }
                if (have > 0) LOG.info("Engine download: {} sent the whole file (validator changed): restart", r.host);
                DownloadState fresh = new DownloadState(t.url, c.getHeaderField("ETag"), t.size);
                fresh.write(stateFile);
                copyBody(c, part, false, t, progress);
                return new Attempt(fresh, false);
            }
            if (r.code == 416) {
                delete(part); // our offset is past the entity: the part file is not a prefix of it
                throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "HTTP 416 from " + r.host, true, null);
            }
            boolean gone = r.code == 404 || r.code == 410;
            throw new InstallException(
                InstallFailure.DOWNLOAD_FAILED,
                "HTTP " + r.code + " from " + r.host + (gone ? " (release asset missing)" : ""),
                !gone,
                null);
        } finally {
            c.disconnect();
        }
    }

    private void copyBody(HttpURLConnection c, File part, boolean append, EngineTarget t, ProgressListener progress)
        throws IOException {
        long done = append ? part.length() : 0;
        OutputStream out;
        try {
            out = new FileOutputStream(part, append);
        } catch (IOException e) {
            throw writeFailure(part, t.size - done, e);
        }
        try (InputStream in = c.getInputStream(); OutputStream o = out) {
            byte[] buf = new byte[CHUNK];
            int n;
            while ((n = in.read(buf)) != -1) {
                checkCancelled();
                if (done + n > t.size) {
                    throw new InstallException(InstallFailure.HASH_MISMATCH, "server sent more than " + t.size + " B");
                }
                try {
                    o.write(buf, 0, n);
                } catch (IOException e) {
                    throw writeFailure(part, t.size - done, e);
                }
                done += n;
                bytesReceived += n;
                progress.onProgress(done, t.size);
            }
        }
    }

    private InstallException writeFailure(File part, long remaining, IOException e) {
        File dir = part.getAbsoluteFile()
            .getParentFile();
        if (usableSpace.applyAsLong(dir) < remaining + WRITE_MARGIN) {
            return new InstallException(InstallFailure.DISK_FULL, "no space left for " + part.getName(), e);
        }
        return new InstallException(InstallFailure.DOWNLOAD_FAILED, "cannot write " + part.getName() + ": " + e, e);
    }

    private InstallException finalFailure(IOException last) {
        if (last instanceof InstallException) {
            InstallException ie = (InstallException) last;
            return new InstallException(ie.failure, ie.detail, false, ie);
        }
        if (last != null && InstallerTls.isTlsFailure(last)) {
            return new InstallException(InstallFailure.TLS_FAILED, String.valueOf(last), last);
        }
        return new InstallException(InstallFailure.DOWNLOAD_FAILED, String.valueOf(last), last);
    }

    private void checkCancelled() throws InstallException {
        if (cancelled) throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "cancelled");
    }

    /** {@code bytes <first>-<last>/<total>} as {first, last, total}, or null. */
    static long[] parseContentRange(String header) {
        if (header == null) return null;
        Matcher m = CONTENT_RANGE.matcher(header.trim());
        if (!m.matches()) return null;
        try {
            return new long[] { Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String hash(File f) throws InstallException {
        try {
            return Sha256.of(f);
        } catch (IOException e) {
            throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "cannot read " + f.getName() + ": " + e, e);
        }
    }

    private static void delete(File f) {
        if (f.exists() && !f.delete()) LOG.warn("Could not delete {}", f.getName());
    }
}
