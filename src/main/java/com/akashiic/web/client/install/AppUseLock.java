package com.akashiic.web.client.install;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Keeps an engine folder's host app from being rewritten, or the folder replaced or deleted, while another game
 * instance may run the host from it (review finding 2).
 * <p>
 * Every engine folder is shared by all game instances and mod versions of this user, yet it has a single
 * {@code resources/app}: Electron loads its app from there and nowhere else (default_app.asar is not installed). In
 * wave
 * 1 an instance with another mod version rewrote that folder to match its own jar while the first instance's host was
 * running from it; the running host also loads files from it lazily, and after a protocol change the two instances
 * would have swapped the folder back and forth, each breaking the other.
 * <p>
 * The rule: an instance holds a shared OS lock on {@code resources/.akw-inuse.lock} from the moment it reports READY
 * for that engine until its JVM exits or it moves to another engine. Rewriting {@code resources/app}, replacing the
 * folder or pruning it needs the exclusive lock. So these happen only when no other instance may be running that host;
 * otherwise the instance that needs a different app reports ENGINE_IN_USE ("close the other game instance") and the
 * player retries with Repair. The OS releases the lock when a JVM dies. Within one JVM a registry counts the holders,
 * because Java refuses two overlapping locks on one file from one process.
 * <p>
 * Limitation until host apps get versioned folders (a bootstrap in host/ plus an {@code --akw-app} argument from the
 * host launcher, outside the installer): two different AkashicWeb versions cannot use the browser at the same time on
 * one PC; the second one says why instead of breaking the first.
 */
final class AppUseLock {

    private static final Logger LOG = LogManager.getLogger("AkashicWeb/Installer");

    /** Inside {@code resources/}; the {@code .akw-} prefix keeps it out of the engine's unexpected-file check. */
    static final String FILE = ".akw-inuse.lock";
    /** How long a shared hold waits while another instance rewrites the app (a few KB: well under a second). */
    static final long SHARE_WAIT_MS = 10_000;

    /** Work done under the exclusive lock. */
    @FunctionalInterface
    interface Exclusive {

        void run() throws InstallException;
    }

    private static final class Entry {

        final RandomAccessFile file;
        final FileLock lock;
        int holders;

        Entry(RandomAccessFile file, FileLock lock) {
            this.file = file;
            this.lock = lock;
        }
    }

    /** Guarded by itself. Key: canonical path of the lock file. */
    private static final Map<String, Entry> HELD = new HashMap<>();

    /** One instance's shared hold on one engine folder. */
    static final class Hold implements AutoCloseable {

        final File engineDir;
        private final String key;
        private boolean closed;

        private Hold(File engineDir, String key) {
            this.engineDir = engineDir;
            this.key = key;
        }

        @Override
        public void close() {
            synchronized (HELD) {
                if (closed) return;
                closed = true;
                Entry e = HELD.get(key);
                if (e != null && --e.holders == 0) {
                    HELD.remove(key);
                    release(e);
                }
            }
        }
    }

    private AppUseLock() {}

    static File fileFor(File engineDir) {
        return new File(new File(engineDir, "resources"), FILE);
    }

    /**
     * A shared hold, waiting up to {@link #SHARE_WAIT_MS} while another instance holds the lock exclusively.
     *
     * @throws InstallException ENGINE_IN_USE if that wait runs out; INSTALL_DIR_NOT_WRITABLE if the lock file cannot
     *                          be created
     */
    static Hold share(File engineDir) throws InstallException {
        File f = fileFor(engineDir);
        String key = key(f);
        synchronized (HELD) {
            Entry e = HELD.get(key);
            if (e == null) {
                e = open(f, true, SHARE_WAIT_MS);
                if (e == null) {
                    throw new InstallException(
                        InstallFailure.ENGINE_IN_USE,
                        "another game instance is rewriting the browser engine's host app");
                }
                HELD.put(key, e);
            }
            e.holders++;
            return new Hold(engineDir, key);
        }
    }

    /**
     * Runs {@code work} under the exclusive lock. {@code mine} is the caller's own hold (it is kept, and re-shared
     * afterwards), or null.
     *
     * @return false, without running {@code work}, when anyone else holds the folder: another instance in this JVM or
     *         another process
     */
    static boolean exclusively(File engineDir, Hold mine, Exclusive work) throws InstallException {
        File f = fileFor(engineDir);
        String key = key(f);
        synchronized (HELD) {
            Entry shared = HELD.get(key);
            int own = mine != null && !mine.closed && mine.key.equals(key) ? 1 : 0;
            if (shared != null && shared.holders > own) return false;
            if (shared != null) {
                // Java cannot upgrade a lock in place: drop our shared one, try the exclusive, re-share after.
                HELD.remove(key);
                release(shared);
            }
            try {
                Entry x = open(f, false, 0);
                if (x == null) return false;
                try {
                    work.run();
                    return true;
                } finally {
                    release(x);
                }
            } finally {
                if (shared != null) reshare(f, key, shared.holders, mine);
            }
        }
    }

    private static void reshare(File f, String key, int holders, Hold mine) {
        try {
            Entry again = f.getParentFile()
                .isDirectory() ? open(f, true, SHARE_WAIT_MS) : null;
            if (again != null) {
                again.holders = holders;
                HELD.put(key, again);
                return;
            }
        } catch (InstallException e) {
            LOG.warn("Could not re-share {}: {}", f.getName(), e.detail);
        }
        // The folder went away (replaced) or another instance took it: our hold no longer protects anything.
        if (mine != null) mine.closed = true;
    }

    /** The lock, or null when it is held elsewhere (after waiting up to {@code waitMs}). */
    private static Entry open(File f, boolean shared, long waitMs) throws InstallException {
        FileOps.ensureDir(f.getParentFile());
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "rw");
            long deadline = System.currentTimeMillis() + waitMs;
            while (true) {
                FileLock l;
                try {
                    l = raf.getChannel()
                        .tryLock(0, Long.MAX_VALUE, shared);
                } catch (OverlappingFileLockException e) {
                    l = null; // held in this JVM outside the registry: treat like another holder
                }
                if (l != null) return new Entry(raf, l);
                if (System.currentTimeMillis() >= deadline) {
                    raf.close();
                    return null;
                }
                Thread.sleep(50);
            }
        } catch (IOException e) {
            closeQuietly(raf);
            throw new InstallException(
                InstallFailure.INSTALL_DIR_NOT_WRITABLE,
                "cannot lock " + f.getName() + ": " + e,
                e);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            closeQuietly(raf);
            throw new InstallException(InstallFailure.ENGINE_IN_USE, "interrupted while waiting for " + f.getName());
        }
    }

    private static void release(Entry e) {
        try {
            e.lock.release();
        } catch (IOException ignored) {
            // Boundary: closing the file below releases the lock anyway.
        }
        closeQuietly(e.file);
    }

    private static void closeQuietly(RandomAccessFile f) {
        if (f == null) return;
        try {
            f.close();
        } catch (IOException ignored) {
            // Boundary: nothing useful to do; the OS releases the handle with the process.
        }
    }

    private static String key(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }
}
