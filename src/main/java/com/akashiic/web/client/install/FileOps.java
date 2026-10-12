package com.akashiic.web.client.install;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Small file helpers shared by the installer classes. */
final class FileOps {

    /** Windows antivirus scanners briefly hold new files open; a rename is retried this many times. */
    static final int MOVE_ATTEMPTS = 8;

    private FileOps() {}

    /** Writes via a temp file in the same folder and an atomic rename, so readers never see half a file. */
    static void writeAtomic(File target, byte[] bytes) throws IOException {
        File dir = target.getAbsoluteFile().getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(dir, target.getName() + ".tmp-" + Long.toHexString(System.nanoTime()));
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.flush();
        }
        try {
            move(tmp, target);
        } finally {
            if (tmp.exists() && !tmp.delete()) tmp.deleteOnExit();
        }
    }

    /**
     * Atomic rename, replacing a file target (not a folder). Retried with short pauses because AV scanners and the
     * Windows search indexer hold fresh files for a moment; the last failure is rethrown.
     */
    static void move(File from, File to) throws IOException {
        IOException last = null;
        for (int i = 0; i < MOVE_ATTEMPTS; i++) {
            try {
                try {
                    Files.move(
                        from.toPath(),
                        to.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                last = e;
                pause(100L << Math.min(i, 4));
            }
        }
        throw last;
    }

    /** Deletes a file or folder tree, best effort; returns false if something is left (in use on Windows). */
    static boolean deleteTree(File f) {
        if (f == null || !f.exists()) return true;
        boolean ok = true;
        // Never follow a symlinked folder: only its link is ours to delete.
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) ok &= deleteTree(c);
            }
        }
        return f.delete() && ok;
    }

    static void ensureDir(File dir) throws InstallException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "cannot create folder " + dir);
        }
    }

    private static void pause(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
