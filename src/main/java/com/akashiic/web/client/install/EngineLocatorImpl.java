package com.akashiic.web.client.install;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.akashiic.web.Tags;
import com.akashiic.web.common.ReasonCode;

/**
 * The engine installer's state machine (docs/CONTRACTS.md {@link EngineLocator}; ADR-006, ADR-011, ADR-014).
 *
 * <pre>
 * ABSENT --probe--> UNSUPPORTED(reason)            platform gate: nothing is ever downloaded
 *               +-> FAILED(SAC_BLOCKED)            Smart App Control enforce (checked at every Windows start)
 *               +-> READY                          engine folder passes the quick check (host app refreshed)
 *               +-> FAILED(reason)                 damaged engine, no install folder
 *               +-> DOWNLOADING -> READY | FAILED  a zip in the import folder, or an install the player agreed to
 *                                                  in an earlier session that did not finish (ConsentRecord)
 *               +-> CONSENT_NEEDED
 * CONSENT_NEEDED | FAILED --requestInstall()--> DOWNLOADING --> READY | FAILED
 * any but UNSUPPORTED/DOWNLOADING --repair()--> (full re-hash) READY | DOWNLOADING --> READY | FAILED
 * DOWNLOADING | CONSENT_NEEDED | FAILED --cancelInstall()--> CONSENT_NEEDED (partial download deleted)
 * READY --verifyAfterLoadFailure()--> READY (intact, repaired app, or rolled back) | FAILED
 * </pre>
 *
 * All file and network work runs on one background thread ({@code AkashicWeb-Engine-Installer}); the getters are
 * cheap, thread-safe reads of volatile fields, safe to call every frame from the render thread. Nothing is downloaded
 * without the player's consent: {@link #requestInstall()} or {@link #repair()} in this session, or a
 * {@link ConsentRecord} for exactly this pin left by an install that was interrupted (the game closed) in an earlier
 * one. A zip the player saved into the import folder is installed without asking: putting it there is their own act.
 */
public final class EngineLocatorImpl implements EngineLocator {

    private static final Logger LOG = LogManager.getLogger("AkashicWeb/Installer");

    private final Builder cfg;
    private final Executor executor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean installQueued = new AtomicBoolean();
    /** Bumped by every {@link #cancelInstall()}; an install task started before a bump stops at its next check. */
    private final AtomicInteger cancels = new AtomicInteger();

    private volatile EngineState state = new EngineState(EngineState.Kind.ABSENT, 0f, ReasonCode.ENGINE_ABSENT);
    private volatile String detail = "not probed yet";
    private volatile PlatformGate.Decision decision;
    private volatile EngineTarget target;
    private volatile InstallPaths paths;
    private volatile File engineDir;
    private volatile EngineStore.Marker engineMarker;
    private volatile SmartAppControl.State sac = SmartAppControl.State.UNKNOWN;
    private volatile float lastProgress = -1f;
    /** The download in progress, so {@link #cancelInstall()} can stop it from another thread. */
    private volatile Downloader activeDownloader;
    /** This instance's shared hold on the READY engine folder ({@link AppUseLock}); null when not READY. */
    private volatile AppUseLock.Hold appHold;

    private EngineLocatorImpl(Builder cfg) {
        this.cfg = cfg;
        this.executor = cfg.executor != null ? cfg.executor : newWorker();
    }

    /** The production locator for a Minecraft client whose instance folder is {@code gameDir}. */
    public static EngineLocatorImpl forClient(File gameDir) {
        return builder().facts(PlatformFacts.fromSystem())
            .gameDir(gameDir)
            .modVersion(Tags.VERSION)
            .hostApp(HostApp.classpath(EngineLocatorImpl.class.getClassLoader()))
            .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Starts the background probe once; later calls do nothing. */
    public void start() {
        if (started.compareAndSet(false, true)) executor.execute(guarded("probe", this::probe));
    }

    @Override
    public EngineState state() {
        return state;
    }

    /**
     * Lets go of the engine folder so another game instance may update its host app. The game never needs this: the OS
     * drops the hold when the JVM exits. For tests, and for a client that tears its browser support down for good.
     */
    public void release() {
        AppUseLock.Hold h = appHold;
        appHold = null;
        if (h != null) h.close();
    }

    @Override
    public File hostExecutable() {
        File dir = engineDir;
        EngineStore.Marker m = engineMarker;
        return state.kind == EngineState.Kind.READY && dir != null && m != null ? new File(dir, m.hostExecutable)
            : null;
    }

    @Override
    public File userDataDir() {
        InstallPaths p = paths;
        File gameDir = cfg.gameDir.getAbsoluteFile();
        File root = p != null ? p.profilesDir() : new File(new File(gameDir, "akashicweb"), "profiles");
        // One profile per Minecraft instance: two instances must never share a Chromium profile (it is locked).
        return new File(
            root,
            Sha256.ofUtf8(canonical(gameDir))
                .substring(0, 12));
    }

    @Override
    public String engineId() {
        EngineStore.Marker m = engineMarker;
        if (state.kind == EngineState.Kind.READY && m != null) return m.engineId;
        EngineTarget t = target;
        return t != null ? t.engineId() : "electron-" + EngineManifest.ELECTRON_VERSION + "-unsupported";
    }

    @Override
    public String detail() {
        StringBuilder sb = new StringBuilder(detail);
        InstallPaths p = paths;
        if (p != null) sb.append(" [")
            .append(p.how)
            .append(": ")
            .append(p.display)
            .append(']');
        PlatformGate.Decision d = decision;
        if (d != null && d.kind == PlatformGate.Kind.WINDOWS_X64 && sac != SmartAppControl.State.UNKNOWN) {
            sb.append(" [SAC ")
                .append(sac)
                .append(']');
        }
        return sb.toString();
    }

    @Override
    public ConsentInfo consentInfo() {
        EngineTarget t = target;
        return t == null ? null : ConsentInfo.of(t, paths);
    }

    @Override
    public void requestInstall() {
        EngineState.Kind k = state.kind;
        if (k == EngineState.Kind.UNSUPPORTED || k == EngineState.Kind.READY || k == EngineState.Kind.DOWNLOADING) {
            LOG.info("Engine install request ignored: state is {}", state);
            return;
        }
        queue("install", epoch -> installTask(false, epoch));
    }

    @Override
    public void repair() {
        EngineState.Kind k = state.kind;
        if (k == EngineState.Kind.UNSUPPORTED || k == EngineState.Kind.DOWNLOADING) {
            LOG.info("Engine repair request ignored: state is {}", state);
            return;
        }
        queue("repair", epoch -> installTask(true, epoch));
    }

    @Override
    public void cancelInstall() {
        cancels.incrementAndGet();
        Downloader d = activeDownloader;
        if (d != null) d.cancel();
        executor.execute(guarded("cancel", this::discardDownload));
    }

    @Override
    public void verifyAfterLoadFailure() {
        verifyAfterLoadFailure(null);
    }

    @Override
    public void verifyAfterLoadFailure(String launchError) {
        InstallFailure blocked = LaunchFailures.classify(launchError);
        executor.execute(guarded("verify", () -> {
            verify();
            // The files are intact, yet Windows refused to start them: say so instead of letting the host relaunch.
            if (blocked != null && state.kind == EngineState.Kind.READY) {
                fail(EngineState.Kind.FAILED, blocked, LaunchFailures.describe(blocked, launchError));
            }
        }));
    }

    /** An install-like task, run once at a time; it gets the cancel epoch it was queued in. */
    private interface Task {

        void run(int epoch);
    }

    private void queue(String name, Task task) {
        if (!installQueued.compareAndSet(false, true)) {
            LOG.info("Engine {} request ignored: another one is already queued", name);
            return;
        }
        final int epoch = cancels.get();
        start(); // the probe runs first if nobody started it; the task then re-checks the state
        executor.execute(guarded(name, () -> {
            try {
                task.run(epoch);
            } finally {
                installQueued.set(false);
            }
        }));
    }

    private boolean cancelled(int epoch) {
        return cancels.get() != epoch;
    }

    // ---------------------------------------------------------------- tasks (installer thread only)

    private void probe() {
        if (!prepare(true)) return;
        // Every Windows start, also with an engine installed: evaluation mode can switch itself to enforce later.
        if (windows() && sacBlocks()) return;
        if (useInstalledEngine(false)) return;
        File imported = ImportScanner.find(paths.importDir(), target);
        if (imported != null) {
            install(imported, false, cancels.get()); // the player saved the official zip there themselves
            return;
        }
        if (ConsentRecord.matches(paths.downloadDir(), target)) {
            LOG.info("Resuming the engine install agreed to in an earlier session");
            install(null, false, cancels.get());
            return;
        }
        set(new EngineState(EngineState.Kind.CONSENT_NEEDED, 0f, ReasonCode.ENGINE_ABSENT), "consent needed");
    }

    /**
     * Decides platform, pinned target and install folder. Runs again when {@code force} is set or an earlier attempt
     * failed (a folder that was not writable may be now), so a Repair or Install click is never a silent no-op.
     *
     * @return false when the state was settled as UNSUPPORTED or FAILED
     */
    private boolean prepare(boolean force) {
        if (!force && decision != null && target != null && paths != null) return true;
        decision = PlatformGate.decide(cfg.facts);
        if (!decision.supported()) {
            fail(EngineState.Kind.UNSUPPORTED, decision.failure, decision.detail);
            return false;
        }
        target = cfg.targets.apply(decision.kind);
        try {
            paths = resolvePaths();
            FileOps.ensureDir(paths.importDir()); // so players can find the folder
        } catch (InstallException e) {
            paths = null;
            fail(EngineState.Kind.FAILED, e.failure, e.detail);
            return false;
        }
        return true;
    }

    private void installTask(boolean repair, int epoch) {
        if (!prepare(repair)) return;
        if (windows() && sacBlocks()) return;
        if (repair && useInstalledEngine(true) && state.kind == EngineState.Kind.READY) return;
        if (cancelled(epoch)) return;
        try {
            // The player pressed Install (or Repair, shown with the same facts): remember it until the install ends.
            ConsentRecord.write(paths.downloadDir(), target, cfg.clock.getAsLong());
        } catch (InstallException e) {
            fail(EngineState.Kind.FAILED, e.failure, e.detail);
            return;
        }
        install(null, repair, epoch);
    }

    /**
     * True when the state was settled from an existing engine folder (READY or FAILED). {@code full} re-hashes every
     * file (Repair); otherwise sizes only, plus the host app's hashes (a few KB).
     */
    private boolean useInstalledEngine(boolean full) {
        EngineStore store = new EngineStore(paths.engineRoot());
        File dir = store.dirFor(target);
        EngineStore.Inspection in = EngineStore.inspect(dir, full);
        if (!in.installed()) return false;
        if (in.problem != null && !in.problem.hostAppOnly) {
            fail(EngineState.Kind.FAILED, in.problem.failure, "engine damaged: " + in.problem.detail);
            return true;
        }
        try {
            File appDir = new File(dir, EngineManifest.APP_DIR);
            if (in.problem == null && HostApp.isCurrent(cfg.hostApp, appDir) && in.manifest.hostAppCheck(dir) == null) {
                // The common start: nothing to write, so no lock (another instance may hold it for a long download).
                ready(dir, in.marker, full ? "re-hashed: intact" : "installed");
                return true;
            }
            try (InstallLock lock = InstallLock.acquire(paths.lockFile())) {
                newInstaller().refreshHostApp(dir, in.marker, appHold);
                EngineStore.Inspection again = EngineStore.inspect(dir, full);
                if (!again.intact()) {
                    String why = again.problem != null ? again.problem.detail : "marker missing";
                    InstallFailure f = again.problem != null ? again.problem.failure : InstallFailure.HASH_MISMATCH;
                    fail(EngineState.Kind.FAILED, f, "engine damaged: " + why);
                    return true;
                }
                ready(dir, again.marker, "installed, host app updated");
            }
        } catch (InstallException e) {
            fail(EngineState.Kind.FAILED, e.failure, e.detail);
        }
        return true;
    }

    private boolean windows() {
        PlatformGate.Decision d = decision;
        return d != null && d.kind == PlatformGate.Kind.WINDOWS_X64;
    }

    private boolean sacBlocks() {
        sac = cfg.sacProbe.apply(cfg.facts);
        if (sac == SmartAppControl.State.ENFORCE) {
            fail(EngineState.Kind.FAILED, InstallFailure.SAC_BLOCKED, "Smart App Control is in enforce mode");
            return true;
        }
        return false;
    }

    /**
     * Installs from {@code importZip}, or downloads. {@code replace}: the folder failed a full re-hash (Repair), so an
     * engine that merely passes the quick check is not reused.
     */
    private void install(File importZip, boolean replace, int epoch) {
        EngineState.Kind k = state.kind;
        if (k == EngineState.Kind.READY || k == EngineState.Kind.UNSUPPORTED || target == null || paths == null) {
            return;
        }
        set(new EngineState(EngineState.Kind.DOWNLOADING, 0f, ReasonCode.OK), "installing " + target.engineId());
        try (InstallLock lock = InstallLock.acquire(paths.lockFile())) {
            if (cancelled(epoch)) return; // the cancel task that follows settles the state and the files
            EngineStore store = new EngineStore(paths.engineRoot());
            store.cleanStaging();
            // Another game instance may have finished the same install while we waited for the lock.
            EngineStore.Inspection done = EngineStore.inspect(store.dirFor(target), false);
            if (done.intact() && !replace) {
                ConsentRecord.delete(paths.downloadDir(), target);
                ready(done.dir, done.marker, "installed by another instance");
                return;
            }
            requireReplaceable(store.dirFor(target));
            if (windows() && sacBlocks()) return;
            File zip = importZip != null ? importZip : ImportScanner.find(paths.importDir(), target);
            EngineInstaller installer = newInstaller();
            activeDownloader = installer.downloader();
            if (cancelled(epoch)) return;
            EngineInstaller.Installed r = installer.install(zip, this::progress);
            ConsentRecord.delete(paths.downloadDir(), target);
            ready(r.dir, r.marker, zip != null ? "installed from import" : "downloaded and installed");
        } catch (InstallException e) {
            if (cancelled(epoch)) return; // stopped by the player, not a failure
            // A failed install asks again at the next start; the partial download stays for a resume after a "yes".
            ConsentRecord.delete(paths.downloadDir(), target);
            fail(EngineState.Kind.FAILED, e.failure, e.detail);
        } finally {
            activeDownloader = null;
        }
    }

    /**
     * The folder an install is about to replace must not be in use: let go of our own hold on it (Windows cannot rename
     * a folder with an open file, our lock file included), then make sure nobody else holds it. A share taken between
     * this check and the rename is not excluded, but an instance shares only a folder with a marker, and the rename
     * removes the marker first.
     */
    private void requireReplaceable(File dir) throws InstallException {
        if (!dir.exists()) return;
        AppUseLock.Hold h = appHold;
        if (h != null && h.engineDir.equals(dir)) release();
        if (!AppUseLock.exclusively(dir, null, () -> {})) {
            throw new InstallException(
                InstallFailure.ENGINE_IN_USE,
                "another game instance is using the browser engine being replaced: close it, then press Repair");
        }
    }

    /** After {@link #cancelInstall()}: forget the consent and the partial download, under the install lock. */
    private void discardDownload() {
        InstallPaths p = paths;
        EngineTarget t = target;
        if (p != null && t != null) {
            try (InstallLock lock = InstallLock.acquire(p.lockFile())) {
                File dl = p.downloadDir();
                ConsentRecord.delete(dl, t);
                for (String suffix : new String[] { ".part", ".state.json", "" }) {
                    File f = new File(dl, t.assetName + suffix);
                    if (f.exists() && !f.delete()) LOG.warn("Could not delete {}", f.getName());
                }
            } catch (InstallException e) {
                LOG.warn("Cancelled install: leftovers not removed ({})", e.detail);
            }
        }
        // Only a stopped install changes the state: "Not now" on a FAILED screen must not hide the failure's reason.
        if (state.kind == EngineState.Kind.DOWNLOADING) {
            set(new EngineState(EngineState.Kind.CONSENT_NEEDED, 0f, ReasonCode.ENGINE_ABSENT), "install cancelled");
        }
    }

    private void verify() {
        File dir = engineDir;
        if (dir == null || paths == null) return;
        if (windows() && sacBlocks()) return;
        EngineStore.Inspection full = EngineStore.inspect(dir, true);
        boolean appCurrent;
        try {
            appCurrent = HostApp.isCurrent(cfg.hostApp, new File(dir, EngineManifest.APP_DIR));
        } catch (InstallException e) {
            fail(EngineState.Kind.FAILED, e.failure, e.detail);
            return;
        }
        if (full.intact() && appCurrent) {
            LOG.info("Engine {} re-hashed after a load failure: intact", dir.getName());
            return;
        }
        LOG.warn(
            "Engine {} re-hashed after a load failure: {}",
            dir.getName(),
            full.problem != null ? full.problem : "host app is not this mod version's");
        try (InstallLock lock = InstallLock.acquire(paths.lockFile())) {
            EngineInstaller installer = newInstaller();
            if (full.installed() && (full.problem == null || full.problem.hostAppOnly)) {
                installer.refreshHostApp(dir, full.marker, appHold);
                EngineStore.Inspection again = EngineStore.inspect(dir, true);
                if (again.intact()) {
                    ready(dir, again.marker, "host app repaired after a load failure");
                    return;
                }
            }
            EngineStore.removeMarker(dir); // never use this folder again; a reinstall replaces it
            for (File other : installer.store()
                .installedEngines()) {
                if (other.equals(dir) || !samePlatform(other)) continue;
                EngineStore.Inspection prev = EngineStore.inspect(other, true);
                if (!prev.intact() && !(prev.installed() && prev.problem.hostAppOnly)) continue;
                installer.refreshHostApp(other, prev.marker, appHold);
                EngineStore.Inspection ok = EngineStore.inspect(other, true);
                if (ok.intact()) {
                    ready(other, ok.marker, "rolled back to " + ok.marker.engineId + " after a load failure");
                    return;
                }
            }
            InstallFailure f = full.problem != null ? full.problem.failure : InstallFailure.HASH_MISMATCH;
            fail(EngineState.Kind.FAILED, f, "engine damaged: " + (full.problem != null ? full.problem.detail : "?"));
        } catch (InstallException e) {
            fail(EngineState.Kind.FAILED, e.failure, e.detail);
        }
    }

    private boolean samePlatform(File dir) {
        EngineStore.Marker m = EngineStore.readMarker(dir);
        return m != null && m.engineId.endsWith("-" + target.platform);
    }

    // ---------------------------------------------------------------- helpers

    private InstallPaths resolvePaths() throws InstallException {
        if (cfg.baseDirOverride != null) {
            return new InstallPaths(cfg.baseDirOverride, "override", cfg.baseDirOverride.getPath());
        }
        boolean windows = decision.kind == PlatformGate.Kind.WINDOWS_X64;
        InstallPaths.Inputs in = new InstallPaths.Inputs(
            windows,
            cfg.facts,
            System.getProperty("user.home"),
            cfg.gameDir.getAbsoluteFile());
        if (windows) {
            final String systemRoot = cfg.facts.env("SystemRoot");
            in.shortPath(f -> WindowsShortPath.of(f, cfg.runner, systemRoot));
        }
        return InstallPaths.resolve(in);
    }

    private EngineInstaller newInstaller() {
        HttpFetcher http = new HttpFetcher(
            HttpFetcher.userAgent(cfg.modVersion),
            cfg.tls,
            cfg.allowPlainHttp,
            HttpFetcher.CONNECT_TIMEOUT_MS,
            HttpFetcher.READ_TIMEOUT_MS);
        Downloader dl = new Downloader(http, cfg.backoff, cfg.sleeper, cfg.usableSpace, cfg.maxAttempts);
        return new EngineInstaller(paths, target, dl, cfg.hostApp, cfg.usableSpace, cfg.clock);
    }

    private void progress(float f) {
        // Avoid churning EngineState objects for every 64 KiB chunk: 0.1% steps are plenty for a progress bar.
        if (f - lastProgress < 0.001f && f < 1f) return;
        lastProgress = f;
        state = new EngineState(EngineState.Kind.DOWNLOADING, f, ReasonCode.OK);
    }

    private void ready(File dir, EngineStore.Marker marker, String why) {
        try {
            holdApp(dir);
        } catch (InstallException e) {
            fail(EngineState.Kind.FAILED, e.failure, e.detail);
            return;
        }
        engineDir = dir;
        engineMarker = marker;
        set(new EngineState(EngineState.Kind.READY, 1f, ReasonCode.OK), why + ": " + dir.getName());
    }

    /** Shares the folder this instance may now run the host from, letting go of a previous one. */
    private void holdApp(File dir) throws InstallException {
        AppUseLock.Hold h = appHold;
        if (h != null && h.engineDir.equals(dir)) return;
        appHold = AppUseLock.share(dir);
        if (h != null) h.close();
    }

    private void fail(EngineState.Kind kind, InstallFailure failure, String why) {
        release(); // nothing will run from the engine folder now; never block another instance's update
        set(new EngineState(kind, 0f, failure.reason), why);
    }

    private void set(EngineState s, String why) {
        InstallPaths p = paths;
        // Details end up in logs and /web doctor lines that players paste for support: no expanded profile paths.
        String safe = p != null ? p.redact(why) : why;
        lastProgress = -1f;
        detail = safe;
        state = s;
        LOG.info("Engine state {} ({})", s, safe);
    }

    /**
     * Documented boundary: a task must never die silently on its thread. Anything unexpected becomes FAILED with
     * DOWNLOAD_FAILED and the exception in the detail, so the placeholder and /web doctor show it.
     */
    private Runnable guarded(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException | Error e) {
                LOG.error("Engine installer task '{}' crashed", name, e);
                fail(EngineState.Kind.FAILED, InstallFailure.DOWNLOAD_FAILED, name + " crashed: " + e);
                if (e instanceof Error && !(e instanceof LinkageError)) throw (Error) e;
            }
        };
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AkashicWeb-Engine-Installer");
            t.setDaemon(true); // a download in progress must not keep the game from exiting; the .part file resumes
            t.setPriority(Thread.NORM_PRIORITY - 2); // hashing 300 MB should not stutter the menu
            return t;
        });
    }

    /** Dependencies, with production defaults. Tests replace the network, disk and platform facts. */
    public static final class Builder {

        PlatformFacts facts;
        File gameDir = new File(".");
        String modVersion = "dev";
        HostApp.Source hostApp;
        CommandRunner runner = CommandRunner.SYSTEM;
        Function<PlatformFacts, SmartAppControl.State> sacProbe;
        ToLongFunction<File> usableSpace = File::getUsableSpace;
        Downloader.Sleeper sleeper = Thread::sleep;
        Backoff backoff = Backoff.DEFAULT;
        int maxAttempts = Downloader.MAX_ATTEMPTS;
        InstallerTls tls = new InstallerTls();
        LongSupplier clock = System::currentTimeMillis;
        Executor executor;
        File baseDirOverride;
        Function<PlatformGate.Kind, EngineTarget> targets = PlatformGate::target;
        boolean allowPlainHttp;

        private Builder() {}

        public Builder facts(PlatformFacts facts) {
            this.facts = facts;
            return this;
        }

        public Builder gameDir(File gameDir) {
            this.gameDir = gameDir;
            return this;
        }

        public Builder modVersion(String modVersion) {
            this.modVersion = modVersion;
            return this;
        }

        public Builder hostApp(HostApp.Source hostApp) {
            this.hostApp = hostApp;
            return this;
        }

        public Builder runner(CommandRunner runner) {
            this.runner = runner;
            return this;
        }

        Builder sacProbe(Function<PlatformFacts, SmartAppControl.State> sacProbe) {
            this.sacProbe = sacProbe;
            return this;
        }

        Builder usableSpace(ToLongFunction<File> usableSpace) {
            this.usableSpace = usableSpace;
            return this;
        }

        Builder sleeper(Downloader.Sleeper sleeper) {
            this.sleeper = sleeper;
            return this;
        }

        Builder backoff(Backoff backoff) {
            this.backoff = backoff;
            return this;
        }

        Builder clock(LongSupplier clock) {
            this.clock = clock;
            return this;
        }

        /** Runs tasks on the given executor instead of the installer thread (tests: {@code Runnable::run}). */
        Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /** Skips the install-folder resolution (tests: a temporary folder). */
        Builder baseDir(File base) {
            this.baseDirOverride = base;
            return this;
        }

        /** Replaces the pinned assets (tests: a local server); plain HTTP is then allowed. */
        Builder targets(Function<PlatformGate.Kind, EngineTarget> targets) {
            this.targets = targets;
            this.allowPlainHttp = true;
            return this;
        }

        public EngineLocatorImpl build() {
            if (facts == null) throw new IllegalStateException("platform facts missing");
            if (hostApp == null) throw new IllegalStateException("host app source missing");
            if (sacProbe == null) {
                final CommandRunner r = runner;
                sacProbe = f -> SmartAppControl.query(r, f.env("SystemRoot"));
            }
            return new EngineLocatorImpl(this);
        }
    }
}
