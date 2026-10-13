package com.akashiic.web.client.install;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The {@code <base>/engine/} folder: one sub-folder per installed engine ({@code <engineId>-<hash8>}), staging and
 * trash folders, the {@code .complete} marker, retention of the previous engine for rollback (ADR-011 "Install").
 * <p>
 * An engine folder is usable only if its marker exists and names the SHA-256 of its local manifest. The marker is
 * written after the atomic rename and removed first when a folder is retired, so a folder that is half-written or
 * half-deleted is never mistaken for an engine.
 */
final class EngineStore {

    private static final Logger LOG = LogManager.getLogger("AkashicWeb/Installer");

    static final String STAGING_PREFIX = ".staging-";
    static final String TRASH_PREFIX = ".trash-";
    /** Temporary host app folders inside {@code resources/} while it is swapped. */
    static final String APP_NEW_PREFIX = ".akw-app-new-";
    static final String APP_OLD_PREFIX = ".akw-app-old-";

    /** The {@code .complete} marker's content. */
    static final class Marker {

        final String engineId;
        final String zipSha256;
        final String manifestSha256;
        final String hostExecutable;
        final long installedMs;

        Marker(String engineId, String zipSha256, String manifestSha256, String hostExecutable, long installedMs) {
            this.engineId = engineId;
            this.zipSha256 = zipSha256;
            this.manifestSha256 = manifestSha256;
            this.hostExecutable = hostExecutable;
            this.installedMs = installedMs;
        }
    }

    /** Result of inspecting one engine folder. */
    static final class Inspection {

        final File dir;
        /** Null when the folder is not an installed engine at all. */
        final Marker marker;
        final LocalManifest manifest;
        /** Null when intact. */
        final LocalManifest.Problem problem;

        Inspection(File dir, Marker marker, LocalManifest manifest, LocalManifest.Problem problem) {
            this.dir = dir;
            this.marker = marker;
            this.manifest = manifest;
            this.problem = problem;
        }

        boolean installed() {
            return marker != null;
        }

        boolean intact() {
            return marker != null && problem == null;
        }
    }

    /** Renames a folder on the same volume (a seam: tests make it fail like a folder held open on Windows). */
    @FunctionalInterface
    interface DirMover {

        void move(File from, File to) throws IOException;
    }

    final File root;
    private final DirMover mover;

    EngineStore(File root) {
        this(root, EngineStore::moveDir);
    }

    EngineStore(File root, DirMover mover) {
        this.root = root;
        this.mover = mover;
    }

    File dirFor(EngineTarget t) {
        return new File(root, t.dirName());
    }

    File newStaging(EngineTarget t) throws InstallException {
        FileOps.ensureDir(root);
        File staging = new File(root, STAGING_PREFIX + t.dirName() + "-" + Long.toHexString(System.nanoTime()));
        FileOps.ensureDir(staging);
        return staging;
    }

    /**
     * Atomically renames the staging folder to its final name. A previous folder of the same name (a broken install
     * being repaired) is first renamed into the trash. Renames are retried because Windows antivirus scanners hold
     * freshly written executables open; if the staging folder still cannot be renamed, that is reported as
     * AV_BLOCKED, its usual cause. An old folder that cannot be moved aside is held open by another process, usually
     * the host of another game instance: ENGINE_IN_USE.
     */
    void promote(File staging, File finalDir) throws InstallException {
        if (finalDir.exists()) {
            removeMarker(finalDir);
            File trash = new File(root, TRASH_PREFIX + finalDir.getName() + "-" + Long.toHexString(System.nanoTime()));
            try {
                mover.move(finalDir, trash);
            } catch (IOException e) {
                throw new InstallException(
                    InstallFailure.ENGINE_IN_USE,
                    "old engine folder is in use (close other game instances): " + e,
                    e);
            }
            FileOps.deleteTree(trash);
        }
        try {
            mover.move(staging, finalDir);
        } catch (IOException e) {
            throw new InstallException(InstallFailure.AV_BLOCKED, "cannot finalize the engine folder: " + e, e);
        }
    }

    /** Writes the marker and returns what it says, so callers never depend on reading it back. */
    Marker writeMarker(File dir, LocalManifest m, String hostExecutable, long nowMs) throws InstallException {
        Properties p = new Properties();
        p.setProperty("engine", m.engineId);
        p.setProperty("zip-sha256", m.zipSha256);
        p.setProperty("manifest-sha256", m.digest());
        p.setProperty("host", hostExecutable);
        p.setProperty("installed-ms", Long.toString(nowMs));
        StringWriter w = new StringWriter();
        try {
            p.store(w, "AkashicWeb engine install marker");
            byte[] bytes = w.toString()
                .getBytes(StandardCharsets.ISO_8859_1);
            FileOps.writeAtomic(new File(dir, LocalManifest.MARKER), bytes);
        } catch (IOException e) {
            throw new InstallException(
                InstallFailure.INSTALL_DIR_NOT_WRITABLE,
                "cannot write the install marker: " + e,
                e);
        }
        return new Marker(m.engineId, m.zipSha256, m.digest(), hostExecutable, nowMs);
    }

    static Marker readMarker(File dir) {
        File f = new File(dir, LocalManifest.MARKER);
        if (!f.isFile() || f.length() > 16 * 1024) return null;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
            String engine = p.getProperty("engine");
            String zip = p.getProperty("zip-sha256");
            String manifest = p.getProperty("manifest-sha256");
            String host = p.getProperty("host");
            long when = Long.parseLong(p.getProperty("installed-ms", "0"));
            if (engine == null || zip == null || manifest == null || host == null || !SafePath.isSafeRelative(host)) {
                return null;
            }
            return new Marker(engine, zip, manifest, host, when);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    static void removeMarker(File dir) {
        File m = new File(dir, LocalManifest.MARKER);
        if (m.exists() && !m.delete()) LOG.warn("Could not remove the install marker in {}", dir.getName());
    }

    /** Checks one engine folder: marker, manifest binding, then sizes (quick) or hashes (full). */
    static Inspection inspect(File dir, boolean full) {
        Marker marker = readMarker(dir);
        if (marker == null) return new Inspection(dir, null, null, null);
        LocalManifest manifest;
        try {
            manifest = LocalManifest.read(dir);
        } catch (InstallException e) {
            return new Inspection(dir, marker, null, problem(InstallFailure.HASH_MISMATCH, e.detail));
        }
        if (!manifest.digest()
            .equals(marker.manifestSha256) || !manifest.engineId.equals(marker.engineId)) {
            LocalManifest.Problem drift = problem(InstallFailure.HASH_MISMATCH, "manifest does not match marker");
            return new Inspection(dir, marker, manifest, drift);
        }
        LocalManifest.Problem p = full ? manifest.fullCheck(dir, marker.hostExecutable)
            : manifest.quickCheck(dir, marker.hostExecutable);
        return new Inspection(dir, marker, manifest, p);
    }

    private static LocalManifest.Problem problem(InstallFailure f, String detail) {
        return new LocalManifest.Problem(f, detail, false);
    }

    /** Folders with a marker, newest install first. */
    List<File> installedEngines() {
        List<File> out = new ArrayList<>();
        File[] children = root.listFiles();
        if (children == null) return out;
        final Map<File, Long> installedAt = new HashMap<>();
        for (File c : children) {
            if (!c.isDirectory() || c.getName()
                .startsWith(".")) continue;
            Marker m = readMarker(c);
            if (m == null) continue;
            out.add(c);
            installedAt.put(c, m.installedMs);
        }
        Collections.sort(out, (a, b) -> Long.compare(installedAt.get(b), installedAt.get(a)));
        return out;
    }

    /**
     * Keeps {@code current} and the newest other installed engine (the rollback target); retires the rest and any
     * trash. Best effort: a folder another game instance may be running its host from ({@link AppUseLock}) is left for
     * a later start.
     */
    void prune(File current) {
        boolean keptPrevious = false;
        for (File dir : installedEngines()) {
            if (dir.equals(current)) continue;
            if (!keptPrevious) {
                keptPrevious = true;
                continue;
            }
            try {
                boolean removed = AppUseLock.exclusively(dir, null, () -> {
                    LOG.info("Removing old engine {}", dir.getName());
                    removeMarker(dir);
                });
                if (removed) FileOps.deleteTree(dir);
                else LOG.info("Old engine {} is in use by another game instance; kept for now", dir.getName());
            } catch (InstallException e) {
                LOG.warn("Old engine {} not removed: {}", dir.getName(), e.detail);
            }
        }
        File[] children = root.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.getName()
                .startsWith(TRASH_PREFIX)) FileOps.deleteTree(c);
        }
    }

    /** Deletes leftovers of interrupted installs. Call only while holding the install lock. */
    void cleanStaging() {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File c : children) {
            String n = c.getName();
            if (n.startsWith(STAGING_PREFIX) || n.startsWith(TRASH_PREFIX)) FileOps.deleteTree(c);
        }
    }

    /** Renames a folder on the same volume, retrying while a scanner holds a file inside it. */
    static void moveDir(File from, File to) throws IOException {
        IOException last = null;
        for (int i = 0; i < FileOps.MOVE_ATTEMPTS; i++) {
            try {
                try {
                    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(from.toPath(), to.toPath());
                }
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(100L << Math.min(i, 4));
                } catch (InterruptedException ie) {
                    Thread.currentThread()
                        .interrupt();
                    throw new IOException("interrupted", ie);
                }
            }
        }
        throw last;
    }
}
