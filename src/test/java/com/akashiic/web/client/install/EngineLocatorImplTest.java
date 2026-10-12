package com.akashiic.web.client.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.akashiic.web.common.ReasonCode;

/**
 * The state machine end to end against a loopback server: consent, download, install, restart, host app update,
 * Smart App Control, manual import, disk space, damage, quarantine, rollback. Tasks run synchronously
 * ({@code Runnable::run}) so each call returns with its final state.
 */
class EngineLocatorImplTest {

    @TempDir
    File tmp;

    private TestServer server;
    private byte[] zip;
    private EngineTarget target;
    private File base;
    private final AtomicLong clock = new AtomicLong(1_000_000);

    @BeforeEach
    void start() throws Exception {
        server = new TestServer();
        zip = TestSupport.zip(TestSupport.linuxLikeEntries(7));
        server.body = zip;
        target = TestSupport.target("linux-x64", server.canonicalUrl("electron-v44.7.0-linux-x64.zip"), zip, false);
        base = new File(tmp, "AkashicWeb");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private EngineLocatorImpl.Builder builder(PlatformFacts facts, EngineTarget t) {
        return EngineLocatorImpl.builder().facts(facts)
            .gameDir(new File(tmp, "instance"))
            .modVersion("test")
            .hostApp(TestSupport.defaultHostApp())
            .executor(Runnable::run)
            .baseDir(base)
            .targets(k -> t)
            .sleeper(ms -> {})
            .clock(() -> clock.incrementAndGet())
            .sacProbe(f -> SmartAppControl.State.OFF)
            .usableSpace(f -> 10L * 1024 * 1024 * 1024);
    }

    private EngineLocatorImpl started(EngineLocatorImpl.Builder b) {
        EngineLocatorImpl l = b.build();
        l.start();
        return l;
    }

    private EngineLocatorImpl installed() {
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        l.requestInstall();
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        return l;
    }

    private File engineDir() {
        return new File(base, "engine/" + target.dirName());
    }

    @Test
    void consentDownloadInstallThenReadyWithoutNetworkOnRestart() throws Exception {
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.CONSENT_NEEDED, l.state().kind);
        assertEquals(ReasonCode.ENGINE_ABSENT, l.state().reason);
        assertNull(l.hostExecutable());
        assertEquals(0, server.requestCount(), "nothing is fetched before consent");
        ConsentInfo info = l.consentInfo();
        assertEquals(zip.length, info.downloadBytes);
        assertEquals("127.0.0.1", info.sourceHost);
        assertTrue(info.component.startsWith("Electron 44.7.0"));

        l.requestInstall();
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        File exe = l.hostExecutable();
        assertEquals(new File(engineDir(), "AkashicWebHost"), exe);
        assertTrue(exe.canExecute());
        assertTrue(new File(engineDir(), "resources/app/package.json").isFile());
        assertFalse(new File(engineDir(), "resources/default_app.asar").exists());
        assertTrue(new File(engineDir(), LocalManifest.MARKER).isFile());
        assertEquals("electron-44.7.0-linux-x64", l.engineId());
        assertEquals(0, new File(base, "download").list().length, "the downloaded zip is deleted after install");
        File profile = l.userDataDir();
        assertEquals(new File(base, "profiles"), profile.getParentFile());

        int requests = server.requestCount();
        EngineLocatorImpl again = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.READY, again.state().kind, again.detail());
        assertEquals(exe, again.hostExecutable());
        assertEquals(profile, again.userDataDir(), "same instance, same profile");
        assertEquals(requests, server.requestCount(), "a restart only checks sizes and the marker");
    }

    @Test
    void unsupportedPlatformsNeverTouchTheNetworkOrDisk() {
        PlatformFacts mac = TestSupport.facts("Mac OS X", "aarch64", "64", TestSupport.env());
        EngineLocatorImpl l = started(builder(mac, target));
        assertEquals(EngineState.Kind.UNSUPPORTED, l.state().kind);
        assertEquals(ReasonCode.UNSUPPORTED_OS, l.state().reason);
        assertNull(l.consentInfo());
        l.requestInstall();
        assertEquals(EngineState.Kind.UNSUPPORTED, l.state().kind);
        assertEquals(0, server.requestCount());
        assertFalse(base.exists());

        PlatformFacts arm = TestSupport.facts("Linux", "aarch64", "64", TestSupport.env("DISPLAY", ":0"));
        assertEquals(ReasonCode.UNSUPPORTED_ARCH, started(builder(arm, target)).state().reason);
    }

    @Test
    void smartAppControlEnforceBlocksBeforeConsent() {
        EngineTarget win = TestSupport.target("win32-x64", target.url, zip, true);
        EngineLocatorImpl l = started(
            builder(TestSupport.windows10(), win).sacProbe(f -> SmartAppControl.State.ENFORCE));
        assertEquals(EngineState.Kind.FAILED, l.state().kind);
        assertEquals(ReasonCode.SAC_BLOCKED, l.state().reason);
        l.requestInstall(); // a retry re-checks SAC and is blocked again
        assertEquals(ReasonCode.SAC_BLOCKED, l.state().reason);
        assertEquals(0, server.requestCount());
        assertTrue(l.detail().contains("SAC ENFORCE"), l.detail());
    }

    @Test
    void smartAppControlEvaluationModeDoesNotBlock() throws Exception {
        Map<String, byte[]> entries = TestSupport.windowsLikeEntries(8);
        byte[] winZip = TestSupport.zip(entries);
        server.body = winZip;
        EngineTarget win = TestSupport.target("win32-x64", target.url, winZip, true);
        EngineLocatorImpl l = started(
            builder(TestSupport.windows10(), win).sacProbe(f -> SmartAppControl.State.EVALUATION));
        assertEquals(EngineState.Kind.CONSENT_NEEDED, l.state().kind);
        l.requestInstall();
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        assertEquals("AkashicWebHost.exe", l.hostExecutable().getName());
        assertArrayEquals(entries.get("electron.exe"), Files.readAllBytes(l.hostExecutable().toPath()));
    }

    @Test
    void manualImportInstallsWithoutNetworkAndKeepsThePlayersFile() throws Exception {
        File importDir = new File(base, "import");
        importDir.mkdirs();
        File imported = new File(importDir, "baixado pelo navegador.zip");
        Files.write(imported.toPath(), zip);
        Files.write(new File(importDir, "other.zip").toPath(), new byte[] { 1, 2, 3 });
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        assertTrue(l.detail().contains("import"), l.detail());
        assertEquals(0, server.requestCount());
        assertTrue(imported.isFile());
    }

    @Test
    void importWithTheWrongHashIsIgnored() throws Exception {
        File importDir = new File(base, "import");
        importDir.mkdirs();
        byte[] wrong = zip.clone();
        wrong[wrong.length / 2] ^= 1;
        Files.write(new File(importDir, target.assetName).toPath(), wrong);
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.CONSENT_NEEDED, l.state().kind);
    }

    @Test
    void lowDiskSpaceIsDiskFullBeforeAnyByte() {
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target).usableSpace(f -> 500L * 1024 * 1024));
        l.requestInstall();
        assertEquals(EngineState.Kind.FAILED, l.state().kind);
        assertEquals(ReasonCode.DISK_FULL, l.state().reason);
        assertEquals(0, server.requestCount());
    }

    @Test
    void hashMismatchFromTheServerFails() {
        server.body = TestSupport.random(zip.length, 5);
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        l.requestInstall();
        assertEquals(ReasonCode.HASH_MISMATCH, l.state().reason);
        assertFalse(engineDir().exists());
    }

    @Test
    void networkFailureIsDownloadFailedAndRetryWorks() {
        server.persistentStatus = 503;
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        l.requestInstall();
        assertEquals(EngineState.Kind.FAILED, l.state().kind);
        assertEquals(ReasonCode.DOWNLOAD_FAILED, l.state().reason);
        server.persistentStatus = 0;
        l.requestInstall();
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
    }

    @Test
    void interruptedDownloadResumesOnNextStartWithoutAskingAgain() throws Exception {
        File downloads = new File(base, "download");
        downloads.mkdirs();
        // What a previous session left: half the file plus its state record (consent was given then).
        byte[] half = java.util.Arrays.copyOf(zip, zip.length / 2);
        Files.write(new File(downloads, target.assetName + ".part").toPath(), half);
        File state = new File(downloads, target.assetName + ".state.json");
        new DownloadState(target.url, "\"v1\"", target.size).write(state);
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        assertEquals("bytes=" + (zip.length / 2) + "-", server.entityRequests().get(0).range);
    }

    @Test
    void progressIsReportedWhileDownloadingOnTheInstallerThread() throws Exception {
        server.gateAt = zip.length / 2;
        // The production executor: a daemon "AkashicWeb-Engine-Installer" thread.
        EngineLocatorImpl l = builder(TestSupport.linuxDesktop(), target).executor(null).build();
        l.start();
        l.requestInstall();
        assertTrue(server.reached.await(20, TimeUnit.SECONDS), "download never reached the middle");
        long deadline = System.currentTimeMillis() + 10_000;
        while (l.state().progress < 0.3f && System.currentTimeMillis() < deadline) Thread.sleep(10);
        EngineState mid = l.state();
        assertEquals(EngineState.Kind.DOWNLOADING, mid.kind);
        assertTrue(mid.progress > 0.3f && mid.progress < 0.85f, "progress " + mid.progress);
        l.requestInstall(); // a second click while downloading changes nothing
        server.release.countDown();
        deadline = System.currentTimeMillis() + 20_000;
        while (l.state().kind == EngineState.Kind.DOWNLOADING && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        assertEquals(1f, l.state().progress);
        assertEquals(1, server.canonicalHits.get());
    }

    @Test
    void newHostAppInTheJarIsInstalledWithoutDownloadingTheEngine() throws Exception {
        installed();
        int requests = server.requestCount();
        Map<String, String> v2 = new LinkedHashMap<>();
        v2.put("package.json", "{\"name\":\"akashicweb-host\",\"main\":\"src/main.js\",\"version\":\"2\"}\n");
        v2.put("src/main.js", "'use strict';\n// test host app v2\n");
        v2.put("src/capture/preload.js", "// new file in v2\n");
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target).hostApp(TestSupport.hostApp(v2)));
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        assertEquals(requests, server.requestCount());
        File app = new File(engineDir(), "resources/app");
        assertTrue(new File(app, "src/capture/preload.js").isFile());
        assertFalse(new File(app, "src/protocol.js").exists(), "files dropped from the jar are dropped from the app");
        assertTrue(new String(Files.readAllBytes(new File(app, "src/main.js").toPath()), StandardCharsets.UTF_8)
            .contains("v2"));
        assertTrue(EngineStore.inspect(engineDir(), true).intact(), "manifest and marker were rewritten");
    }

    @Test
    void damagedEngineFailsAtStartupAndRequestInstallRepairsIt() throws Exception {
        installed();
        Files.write(new File(engineDir(), "libffmpeg.so").toPath(), new byte[] { 1, 2, 3 });
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.FAILED, l.state().kind);
        assertEquals(ReasonCode.HASH_MISMATCH, l.state().reason);
        l.requestInstall();
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        assertTrue(EngineStore.inspect(engineDir(), true).intact());
    }

    @Test
    void quarantinedExecutableIsAvBlocked() {
        installed();
        assertTrue(new File(engineDir(), "AkashicWebHost").delete());
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.FAILED, l.state().kind);
        assertEquals(ReasonCode.AV_BLOCKED, l.state().reason);
    }

    @Test
    void damagedHostAppIsRepairedFromTheJarAtStartup() throws Exception {
        installed();
        File main = new File(engineDir(), "resources/app/src/main.js");
        Files.write(main.toPath(), "evil()".getBytes(StandardCharsets.UTF_8));
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        assertTrue(EngineStore.inspect(engineDir(), true).intact());
    }

    @Test
    void loadFailureWithIntactEngineKeepsReady() {
        EngineLocatorImpl l = installed();
        l.verifyAfterLoadFailure();
        assertEquals(EngineState.Kind.READY, l.state().kind);
    }

    @Test
    void loadFailureRollsBackToThePreviousEngine() throws Exception {
        // Engine A installed first, then a re-pin to engine B (same platform, different zip).
        EngineLocatorImpl a = installed();
        File dirA = engineDir();
        byte[] zipB = TestSupport.zip(TestSupport.linuxLikeEntries(70));
        server.body = zipB;
        server.etag = "\"b\"";
        EngineTarget targetB = new EngineTarget("linux-x64", "44.8.0", "electron-v44.8.0-linux-x64.zip",
            server.canonicalUrl("electron-v44.8.0-linux-x64.zip"), zipB.length, Sha256.of(zipB), "electron",
            "AkashicWebHost", false, target.unixExecutables);
        EngineLocatorImpl b = started(builder(TestSupport.linuxDesktop(), targetB));
        b.requestInstall();
        assertEquals(EngineState.Kind.READY, b.state().kind, b.detail());
        File dirB = b.hostExecutable().getParentFile();
        assertTrue(dirA.isDirectory(), "the previous engine is kept for rollback");

        // B's binary is corrupted in place (same size): the startup check cannot see it, the full re-hash does.
        File exeB = new File(dirB, "AkashicWebHost");
        byte[] bytes = Files.readAllBytes(exeB.toPath());
        bytes[0] ^= 1;
        Files.write(exeB.toPath(), bytes);
        b.verifyAfterLoadFailure();
        assertEquals(EngineState.Kind.READY, b.state().kind, b.detail());
        assertEquals(dirA, b.hostExecutable().getParentFile());
        assertTrue(b.detail().contains("rolled back"), b.detail());
        assertEquals("electron-44.7.0-linux-x64", b.engineId());
        assertNull(EngineStore.readMarker(dirB), "the damaged engine is never used again");
        assertNotNull(a);
    }

    @Test
    void loadFailureWithoutRollbackTargetFails() throws Exception {
        EngineLocatorImpl l = installed();
        File exe = l.hostExecutable();
        byte[] bytes = Files.readAllBytes(exe.toPath());
        bytes[0] ^= 1;
        Files.write(exe.toPath(), bytes);
        l.verifyAfterLoadFailure();
        assertEquals(EngineState.Kind.FAILED, l.state().kind);
        assertEquals(ReasonCode.HASH_MISMATCH, l.state().reason);
        l.requestInstall();
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
    }

    @Test
    void onlyTheCurrentAndOnePreviousEngineAreKept() throws Exception {
        installed();
        File first = engineDir();
        List<File> dirs = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            byte[] z = TestSupport.zip(TestSupport.linuxLikeEntries(100 + i));
            server.body = z;
            server.etag = "\"e" + i + "\"";
            EngineTarget t = new EngineTarget("linux-x64", "44.8." + i, "electron-v44.8." + i + "-linux-x64.zip",
                server.canonicalUrl("electron-v44.8." + i + ".zip"), z.length, Sha256.of(z), "electron",
                "AkashicWebHost", false, target.unixExecutables);
            EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), t));
            l.requestInstall();
            assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
            dirs.add(l.hostExecutable().getParentFile());
        }
        assertFalse(first.exists(), "the oldest engine was removed");
        assertTrue(dirs.get(0).isDirectory());
        assertTrue(dirs.get(1).isDirectory());
    }

    @Test
    void userDataDirIsPerInstance() {
        EngineLocatorImpl one = started(builder(TestSupport.linuxDesktop(), target));
        EngineLocatorImpl two = started(builder(TestSupport.linuxDesktop(), target).gameDir(new File(tmp, "other")));
        assertEquals(one.userDataDir().getParentFile(), two.userDataDir().getParentFile());
        assertEquals(new File(base, "profiles"), one.userDataDir().getParentFile());
        assertFalse(one.userDataDir().equals(two.userDataDir()), "two instances never share a Chromium profile");
    }
}
