package com.akashiic.web.client.install;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

/**
 * Cross-process lock on {@code <base>/install.lock} (OS file lock, released when the JVM dies), so two game instances
 * on the same PC (ADR-014 P1 "two instances at once") never download or extract into the same folders together.
 * Within one JVM the installer's single worker thread already serializes its tasks.
 */
final class InstallLock implements AutoCloseable {

    private final RandomAccessFile file;
    private final FileLock lock;

    private InstallLock(RandomAccessFile file, FileLock lock) {
        this.file = file;
        this.lock = lock;
    }

    /** Waits until the lock is free. */
    static InstallLock acquire(File lockFile) throws InstallException {
        return open(lockFile, true);
    }

    /** The lock, or null when another process holds it. */
    static InstallLock tryAcquire(File lockFile) throws InstallException {
        return open(lockFile, false);
    }

    private static InstallLock open(File lockFile, boolean wait) throws InstallException {
        FileOps.ensureDir(
            lockFile.getAbsoluteFile()
                .getParentFile());
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(lockFile, "rw");
            FileChannel ch = raf.getChannel();
            FileLock l = wait ? ch.lock() : ch.tryLock();
            if (l == null) {
                raf.close();
                return null;
            }
            return new InstallLock(raf, l);
        } catch (IOException | OverlappingFileLockException e) {
            closeQuietly(raf);
            throw new InstallException(
                InstallFailure.INSTALL_DIR_NOT_WRITABLE,
                "cannot lock " + lockFile.getName() + ": " + e,
                e);
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // Boundary: closing the file below releases the lock anyway.
        }
        closeQuietly(file);
    }

    private static void closeQuietly(RandomAccessFile f) {
        if (f == null) return;
        try {
            f.close();
        } catch (IOException ignored) {
            // Boundary: nothing useful to do; the OS releases the handle with the process.
        }
    }
}
