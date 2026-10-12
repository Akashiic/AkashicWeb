package com.akashiic.web.client.install;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *               +-> READY                          engine folder passes the quick check (host app refreshed)
 *               +-> FAILED(SAC_BLOCKED | reason)   Smart App Control enforce, damaged engine, no install folder
 *               +-> DOWNLOADING -> READY | FAILED  manual import found, or an earlier consented download resumes
 *               +-> CONSENT_NEEDED
 * CONSENT_NEEDED | FAILED --requestInstall()--> DOWNLOADING --> READY | FAILED
 * READY --verifyAfterLoadFailure()--> READY (intact, repaired app, or rolled back) | FAILED
 * </pre>
 *
 * All file and network work runs on one background thread ({@code AkashicWeb-Engine-Installer}); the getters are
 * cheap, thread-safe reads of volatile fields, safe to call every frame from the render thread.
 */
public final class EngineLocatorImpl implements EngineLocator {

    private static final Logger LOG = LogManager.getLogger("AkashicWeb/Installer");

    private final Builder cfg;
    private final Executor executor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean installQueued = new AtomicBoolean();

    private volatile EngineState state = new EngineState(EngineState.Kind.ABSENT, 0f, ReasonCode.ENGINE_ABSENT);
    private volatile String detail = "not probed yet";
    private volatile PlatformGate.Decision decision;
    private volatile EngineTarget target;
    private volatile InstallPaths paths;
    private volatile File engineDir;
    private volatile EngineStore.Marker engineMarker;
    private volatile SmartAppControl.State sac = SmartAppControl.State.UNKNOWN;
    private volatile float lastProgress = -1f;

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
        return new File(root, Sha256.ofUtf8(canonical(gameDir)).substring(0, 12));
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
        if (p != null) sb.append(" [").append(p.how).append(": ").append(p.base).append(']');
        PlatformGate.Decision d = decision;
        if (d != null && d.kind == PlatformGate.Kind.WINDOWS_X64 && sac != SmartAppControl.State.UNKNOWN) {
            sb.append(" [SAC ").append(sac).append(']');
        }
        return sb.toString();
    }

    @Override
    public ConsentInfo consentInfo() {
        EngineTarget t = target;
        InstallPaths p = paths;
        return t == null ? null : ConsentInfo.of(t, p == null ? null : p.engineRoot());
    }

    @Override
    public void requestInstall() {
        EngineState.Kind k = state.kind;
        if (k == EngineState.Kind.UNSUPPORTED || k == EngineState.Kind.READY || k == EngineState.Kind.DOWNLOADING) {
            LOG.info("Engine install not started: state is {}", state);
            return;
        }
        if (!installQueued.compareAndSet(false, true)) return; // a second click while queued
        start(); // the probe runs first if nobody started it; the install task then re-checks the state
        executor.execute(guarded("install", () -> {
            try {
                install(null);
            } finally {
                installQueued.set(false);
            }
        }));
    }

    @Override
    public void verifyAfterLoadFailure() {
        executor.execute(guarded("verify", this::verify));
    }

    // ---------------------------------------------------------------- tasks (installer thread only)

    private void probe() {
        decision = PlatformGate.decide(cfg.facts);
        if (!decision.supported()) {
            fail(EngineState.Kind.UNSUPPORTED, decision.failure, decision.detail);
            return;
        }
        target = cfg.targets.apply(decision.kind);
        try {
            paths = resolvePaths();
            for (File d : paths.importDirs()) FileOps.ensureDir(d); // so players can find the folder
        } catch (InstallException e) {
            fail(EngineState.Kind.FAILED, e.failure, e.detail);
            return;
        }
        if (useInstalledEngine()) return;
        if (decision.kind == PlatformGate.Kind.WINDOWS_X64 && sacBlocks()) return;
        File imported = ImportScanner.find(paths.importDirs(), target);
        boolean resumePending = new File(paths.downloadDir(), target.assetName + ".state.json").isFile();
        if (imported != null || resumePending) {
            // The player consented before (a partial download exists) or chose to import by hand.
            install(imported);
            return;
        }
        set(new EngineState(EngineState.Kind.CONSENT_NEEDED, 0f, ReasonCode.ENGINE_ABSENT), "consent needed");
    }

    /** True when the state was settled from an existing engine folder (READY or FAILED). */
    private boolean useInstalledEngine() {
        EngineStore store = new EngineStore(paths.engineRoot());
        File dir = store.dirFor(target);
        EngineStore.Inspection in = EngineStore.inspect(dir, false);
        if (!in.installed()) return false;
        if (in.problem != null && !in.problem.hostAppOnly) {
            fail(EngineState.Kind.FAILED, in.problem.failure, "engine damaged: " + in.problem.detail);
            return true;
        }
        try {
            File appDir = new File(dir, EngineManifest.APP_DIR);
            if (in.problem == null && HostApp.isCurrent(cfg.hostApp, appDir) && in.manifest.hostAppCheck(dir) == null) {
                // The common start: nothing to write, so no lock (another instance may hold it for a long download).
                ready(dir, in.marker, "installed");
                return true;
            }
            try (InstallLock lock = InstallLock.acquire(paths.lockFile())) {
                newInstaller().refreshHostApp(dir, in.marker);
                EngineStore.Inspection again = EngineStore.inspect(dir, false);
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

    private boolean sacBlocks() {
        sac = cfg.sacProbe.apply(cfg.facts);
        if (sac == SmartAppControl.State.ENFORCE) {
            fail(EngineState.Kind.FAILED, InstallFailure.SAC_BLOCKED, "Smart App Control is in enforce mode");
            return true;
        }
        return false;
    }

    private void install(File importZip) {
        EngineState.Kind k = state.kind;
        if (k == EngineState.Kind.READY || k == EngineState.Kind.UNSUPPORTED || target == null || paths == null) {
            return;
        }
        set(new EngineState(EngineState.Kind.DOWNLOADING, 0f, ReasonCode.OK), "installing " + target.engineId());
        try (InstallLock lock = InstallLock.acquire(paths.lockFile())) {
            EngineStore store = new EngineStore(paths.engineRoot());
            store.cleanStaging();
            // Another game instance may have finished the same install while we waited for the lock.
            EngineStore.Inspection done = EngineStore.inspect(store.dirFor(target), false);
            if (done.intact()) {
                ready(done.dir, done.marker, "installed by another instance");
                return;
            }
            if (decision.kind == PlatformGate.Kind.WINDOWS_X64 && sacBlocks()) return;
            File zip = importZip != null ? importZip : ImportScanner.find(paths.importDirs(), target);
            File dir = newInstaller().install(zip, this::progress);
            ready(dir, EngineStore.readMarker(dir), zip != null ? "installed from import" : "downloaded and installed");
        } catch (InstallException e) {
            fail(EngineState.Kind.FAILED, e.failure, e.detail);
        }
    }

    private void verify() {
        File dir = engineDir;
        if (dir == null || paths == null) return;
        EngineStore.Inspection full = EngineStore.inspect(dir, true);
        if (full.intact()) {
            LOG.info("Engine {} re-hashed after a load failure: intact", dir.getName());
            return;
        }
        LOG.warn("Engine {} re-hashed after a load failure: {}", dir.getName(), full.problem);
        try (InstallLock lock = InstallLock.acquire(paths.lockFile())) {
            EngineInstaller installer = newInstaller();
            if (full.installed() && full.problem.hostAppOnly) {
                installer.refreshHostApp(dir, full.marker);
                EngineStore.Inspection again = EngineStore.inspect(dir, true);
                if (again.intact()) {
                    ready(dir, again.marker, "host app repaired after a load failure");
                    return;
                }
            }
            EngineStore.removeMarker(dir); // never use this folder again; a reinstall replaces it
            for (File other : installer.store().installedEngines()) {
                if (other.equals(dir) || !samePlatform(other)) continue;
                EngineStore.Inspection prev = EngineStore.inspect(other, true);
                if (!prev.intact() && !(prev.installed() && prev.problem.hostAppOnly)) continue;
                installer.refreshHostApp(other, prev.marker);
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
            return new InstallPaths(cfg.baseDirOverride, cfg.baseDirOverride, "override");
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
        engineDir = dir;
        engineMarker = marker;
        set(new EngineState(EngineState.Kind.READY, 1f, ReasonCode.OK), why + ": " + dir.getName());
    }

    private void fail(EngineState.Kind kind, InstallFailure failure, String why) {
        set(new EngineState(kind, 0f, failure.reason), why);
    }

    private void set(EngineState s, String why) {
        lastProgress = -1f;
        detail = why;
        state = s;
        LOG.info("Engine state {} ({})", s, why);
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
