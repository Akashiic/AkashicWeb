package com.akashiic.web.client.install;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * One install of one pinned engine, end to end (ADR-011): free-space check, download (or manual import), extraction
 * into staging, host app from the jar, local manifest, atomic rename, marker, full re-hash, retention. Also rewrites
 * the host app alone when a mod update ships a new one. Runs on the installer thread while holding the install lock.
 */
final class EngineInstaller {

    private static final Logger LOG = LogManager.getLogger("AkashicWeb/Installer");

    private final InstallPaths paths;
    private final EngineTarget target;
    private final EngineStore store;
    private final Downloader downloader;
    private final HostApp.Source hostApp;
    private final ToLongFunction<File> usableSpace;
    private final LongSupplier clock;

    EngineInstaller(InstallPaths paths, EngineTarget target, Downloader downloader, HostApp.Source hostApp,
        ToLongFunction<File> usableSpace, LongSupplier clock) {
        this.paths = paths;
        this.target = target;
        this.store = new EngineStore(paths.engineRoot());
        this.downloader = downloader;
        this.hostApp = hostApp;
        this.usableSpace = usableSpace;
        this.clock = clock;
    }

    EngineStore store() {
        return store;
    }

    /**
     * Installs from {@code importZip} when given (already verified by {@link ImportScanner}), else downloads.
     *
     * @return the engine folder, verified
     */
    File install(File importZip, ProgressListener.FractionSink progress) throws InstallException {
        FileOps.ensureDir(paths.base);
        requireFreeSpace();
        File zip = importZip;
        boolean downloaded = false;
        if (zip == null) {
            zip = downloader.download(target, paths.downloadDir(), ProgressListener.scaled(progress, 0f, 0.85f));
            downloaded = true;
        }
        File dir = installFromZip(zip, ProgressListener.scaled(progress, downloaded ? 0.85f : 0f, 1f));
        // The downloaded zip is ours and 158 MB; an imported one belongs to the player and stays.
        if (downloaded && !zip.delete()) LOG.warn("Could not delete {}", zip.getName());
        return dir;
    }

    private void requireFreeSpace() throws InstallException {
        long free = usableSpace.applyAsLong(paths.base);
        if (free < EngineManifest.MIN_FREE_BYTES) {
            long mib = 1024 * 1024;
            throw new InstallException(
                InstallFailure.DISK_FULL,
                (free / mib) + " MiB free, " + (EngineManifest.MIN_FREE_BYTES / mib) + " MiB needed");
        }
    }

    File installFromZip(File zip, ProgressListener progress) throws InstallException {
        File staging = store.newStaging(target);
        try {
            EngineExtractor.Result r = new EngineExtractor(usableSpace).extract(zip, target, staging, progress);
            List<LocalManifest.Entry> all = new ArrayList<>(r.entries);
            all.addAll(HostApp.install(hostApp, new File(staging, EngineManifest.APP_DIR)));
            LocalManifest manifest = new LocalManifest(target.engineId(), target.sha256, all);
            writeManifest(manifest, staging);
            File dir = store.dirFor(target);
            store.promote(staging, dir);
            store.writeMarker(dir, manifest, target.hostExecutable, clock.getAsLong());
            // Full re-hash right after install (ADR-011). Bytes we just wrote and hashed cannot legitimately differ,
            // so a missing or changed file here is an on-write antivirus action.
            LocalManifest.Problem p = manifest.fullCheck(dir, target.hostExecutable);
            if (p != null) {
                EngineStore.removeMarker(dir);
                throw new InstallException(InstallFailure.AV_BLOCKED, "files changed right after install: " + p.detail);
            }
            LOG.info(
                "Installed {} into {} ({} files, {} skipped, {} B)",
                target.engineId(),
                paths.display(dir),
                all.size(),
                r.skipped,
                r.bytesWritten);
            store.prune(dir);
            return dir;
        } finally {
            if (staging.exists() && !FileOps.deleteTree(staging)) LOG.warn("Staging folder left: {}", staging);
        }
    }

    /**
     * Makes {@code resources/app} match the jar. Returns false when it already did. The new app is written to a
     * sibling folder first and swapped in with two renames, so the engine never holds a half-copied app; the manifest
     * and marker are rewritten after the swap.
     */
    boolean refreshHostApp(File engineDir, EngineStore.Marker marker) throws InstallException {
        File appDir = new File(engineDir, EngineManifest.APP_DIR);
        LocalManifest old = LocalManifest.read(engineDir);
        if (HostApp.isCurrent(hostApp, appDir) && old.hostAppCheck(engineDir) == null) return false;
        String tag = Long.toHexString(System.nanoTime());
        File resources = appDir.getParentFile();
        File fresh = new File(resources, ".akw-app-new-" + tag);
        File retired = new File(resources, ".akw-app-old-" + tag);
        try {
            List<LocalManifest.Entry> appEntries = HostApp.install(hostApp, fresh);
            try {
                if (appDir.exists()) EngineStore.moveDir(appDir, retired);
                EngineStore.moveDir(fresh, appDir);
            } catch (IOException e) {
                throw new InstallException(InstallFailure.ENGINE_IN_USE, "host app folder in use: " + e, e);
            }
            List<LocalManifest.Entry> all = new ArrayList<>(old.vendorEntries());
            all.addAll(appEntries);
            LocalManifest manifest = new LocalManifest(old.engineId, old.zipSha256, all);
            writeManifest(manifest, engineDir);
            store.writeMarker(engineDir, manifest, marker.hostExecutable, clock.getAsLong());
            LOG.info("Host app in {} updated from the mod jar", engineDir.getName());
            return true;
        } finally {
            FileOps.deleteTree(fresh);
            FileOps.deleteTree(retired);
        }
    }

    private static void writeManifest(LocalManifest m, File dir) throws InstallException {
        try {
            m.write(dir);
        } catch (IOException e) {
            throw new InstallException(
                InstallFailure.INSTALL_DIR_NOT_WRITABLE,
                "cannot write the engine manifest: " + e,
                e);
        }
    }
}
