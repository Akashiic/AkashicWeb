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
        return EngineLocatorImpl.builder()
            .facts(facts)
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
        assertTrue(
            l.detail()
                .contains("SAC ENFORCE"),
            l.detail());
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
        assertEquals(
            "AkashicWebHost.exe",
            l.hostExecutable()
                .getName());
        assertArrayEquals(
            entries.get("electron.exe"),
            Files.readAllBytes(
                l.hostExecutable()
                    .toPath()));
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
        assertTrue(
            l.detail()
                .contains("import"),
            l.detail());
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
    void anUnwritableInstallFolderIsInstallDirNotWritable() throws Exception {
        // A regular file where the AkashicWeb folder should be: no sub-folder can be created, even by root.
        assertTrue(
            base.getParentFile()
                .isDirectory()
                || base.getParentFile()
                    .mkdirs());
        assertTrue(base.createNewFile());
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.FAILED, l.state().kind);
        assertEquals(ReasonCode.INSTALL_DIR_NOT_WRITABLE, l.state().reason);
        assertEquals(0, server.requestCount());
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

    /** What a previous session left: half the file and its resume record. */
    private File leavePartialDownload() throws Exception {
        File downloads = new File(base, "download");
        downloads.mkdirs();
        byte[] half = java.util.Arrays.copyOf(zip, zip.length / 2);
        Files.write(new File(downloads, target.assetName + ".part").toPath(), half);
        new DownloadState(target.url, "\"v1\"", target.size)
            .write(new File(downloads, target.assetName + ".state.json"));
        return downloads;
    }

    @Test
    void anInstallInterruptedByClosingTheGameResumesAtTheNextStart() throws Exception {
        File downloads = leavePartialDownload();
        ConsentRecord.write(downloads, target, 1L); // the player pressed Install in that session
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        assertEquals(
            "bytes=" + (zip.length / 2) + "-",
            server.entityRequests()
                .get(0).range);
        assertFalse(
            ConsentRecord.file(downloads, target)
                .exists(),
            "consent is spent once the install is done");
    }

    /**
     * Review finding (medium, 4, probe 'stateonly'): a resume file alone (planted, stale, or left by another
     * instance) is not consent. Nothing is fetched until the player agrees; then the partial download resumes.
     */
    @Test
    void aResumeFileAloneIsNotConsent() throws Exception {
        leavePartialDownload();
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.CONSENT_NEEDED, l.state().kind, l.detail());
        assertEquals(0, server.requestCount());
        l.requestInstall();
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        assertEquals(
            "bytes=" + (zip.length / 2) + "-",
            server.entityRequests()
                .get(0).range,
            "after the yes, the kept half is reused");
    }

    @Test
    void consentForAnotherPinOrUrlIsNotConsent() throws Exception {
        File downloads = leavePartialDownload();
        EngineTarget otherUrl = TestSupport.withUrl(target, "https://mirror.invalid/" + target.assetName);
        ConsentRecord.write(downloads, otherUrl, 1L);
        assertEquals(
            EngineState.Kind.CONSENT_NEEDED,
            started(builder(TestSupport.linuxDesktop(), target)).state().kind);
        Files.write(
            ConsentRecord.file(downloads, target)
                .toPath(),
            ("sha256=" + Sha256.ofUtf8("other zip") + "\nurl=" + target.url + "\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(
            EngineState.Kind.CONSENT_NEEDED,
            started(builder(TestSupport.linuxDesktop(), target)).state().kind);
        assertEquals(0, server.requestCount());
    }

    /** Review finding (medium, 4): a failed install no longer re-downloads in the background at every start. */
    @Test
    void aFailedInstallAsksAgainAtTheNextStart() {
        server.persistentStatus = 503;
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        l.requestInstall();
        assertEquals(ReasonCode.DOWNLOAD_FAILED, l.state().reason);
        int requests = server.requestCount();
        EngineLocatorImpl next = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.CONSENT_NEEDED, next.state().kind, next.detail());
        assertEquals(requests, server.requestCount(), "no background download without a new yes");
    }

    /** Review finding (medium, 4): the player can stop a download; everything it left is removed. */
    @Test
    void cancelStopsTheDownloadAndForgetsIt() throws Exception {
        server.gateAt = zip.length / 2;
        EngineLocatorImpl l = builder(TestSupport.linuxDesktop(), target).executor(null)
            .build();
        l.start();
        l.requestInstall();
        assertTrue(server.reached.await(20, TimeUnit.SECONDS), "download never reached the middle");
        l.cancelInstall();
        server.release.countDown();
        long deadline = System.currentTimeMillis() + 20_000;
        while (l.state().kind != EngineState.Kind.CONSENT_NEEDED && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(EngineState.Kind.CONSENT_NEEDED, l.state().kind, l.detail());
        File downloads = new File(base, "download");
        assertEquals(
            0,
            downloads.list().length,
            "part, resume record and consent record are gone: " + java.util.Arrays.toString(downloads.list()));
        assertFalse(engineDir().exists());
        EngineLocatorImpl next = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.CONSENT_NEEDED, next.state().kind);
    }

    @Test
    void notNowOnAFailureKeepsTheReason() {
        server.persistentStatus = 404;
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        l.requestInstall();
        l.cancelInstall();
        assertEquals(EngineState.Kind.FAILED, l.state().kind);
        assertEquals(ReasonCode.DOWNLOAD_FAILED, l.state().reason);
    }

    /** Review finding (low, 8): a failed probe is run again by the next Install click. */
    @Test
    void installAfterAFailedProbeProbesAgain() throws Exception {
        assertTrue(
            base.getParentFile()
                .isDirectory()
                || base.getParentFile()
                    .mkdirs());
        assertTrue(base.createNewFile()); // not a folder: nothing can be created inside
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(ReasonCode.INSTALL_DIR_NOT_WRITABLE, l.state().reason);
        assertTrue(base.delete()); // the player fixed it
        l.requestInstall();
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
    }

    @Test
    void repairReinstallsDamageOnlyAFullRehashFinds() throws Exception {
        installed().release();
        File ffmpeg = new File(engineDir(), "libffmpeg.so");
        byte[] bytes = Files.readAllBytes(ffmpeg.toPath());
        bytes[10] ^= 1; // same size: the start-up check cannot see it
        Files.write(ffmpeg.toPath(), bytes);
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.READY, l.state().kind);
        int requests = server.requestCount();
        l.repair();
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        assertTrue(server.requestCount() > requests, "the engine was downloaded again");
        assertTrue(
            EngineStore.inspect(engineDir(), java.util.Collections.singletonList(target), true)
                .intact());
    }

    @Test
    void repairOfAnIntactEngineDownloadsNothing() {
        installed();
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        int requests = server.requestCount();
        l.repair();
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        assertTrue(
            l.detail()
                .contains("intact"),
            l.detail());
        assertEquals(requests, server.requestCount());
    }

    /** Review finding (low, 6): Smart App Control is read at every Windows start and after a load failure. */
    @Test
    void smartAppControlIsCheckedEvenWithAnEngineInstalled() throws Exception {
        byte[] winZip = TestSupport.zip(TestSupport.windowsLikeEntries(9));
        server.body = winZip;
        EngineTarget win = TestSupport.target("win32-x64", target.url, winZip, true);
        java.util.concurrent.atomic.AtomicReference<SmartAppControl.State> sacState = new java.util.concurrent.atomic.AtomicReference<>(
            SmartAppControl.State.EVALUATION);
        EngineLocatorImpl l = started(builder(TestSupport.windows10(), win).sacProbe(f -> sacState.get()));
        l.requestInstall();
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());

        sacState.set(SmartAppControl.State.ENFORCE); // evaluation mode switched itself to enforce
        l.verifyAfterLoadFailure();
        assertEquals(ReasonCode.SAC_BLOCKED, l.state().reason, l.detail());
        assertNull(l.hostExecutable());

        EngineLocatorImpl next = started(builder(TestSupport.windows10(), win).sacProbe(f -> sacState.get()));
        assertEquals(EngineState.Kind.FAILED, next.state().kind);
        assertEquals(ReasonCode.SAC_BLOCKED, next.state().reason);
    }

    /** Review finding (high, 0, M2 part): a Windows refusal in the launch error names the real cause. */
    @Test
    void aWindowsRefusalInTheLaunchErrorBecomesItsReason() {
        EngineLocatorImpl l = installed();
        l.verifyAfterLoadFailure("java.io.IOException: Cannot run program \"x\": CreateProcess error=4551, An App...");
        assertEquals(EngineState.Kind.FAILED, l.state().kind);
        assertEquals(ReasonCode.SAC_BLOCKED, l.state().reason);
        assertTrue(
            l.detail()
                .contains("error=4551"),
            l.detail());

        EngineLocatorImpl av = started(builder(TestSupport.linuxDesktop(), target));
        av.verifyAfterLoadFailure("CreateProcess error=225, Operation did not complete successfully");
        assertEquals(ReasonCode.AV_BLOCKED, av.state().reason);

        EngineLocatorImpl other = started(builder(TestSupport.linuxDesktop(), target));
        other.verifyAfterLoadFailure("exited before connecting");
        assertEquals(EngineState.Kind.READY, other.state().kind, "an intact engine with an unknown error stays READY");
    }

    @Test
    void consentInfoCarriesWhatTheConsentScreenAndImportHintNeed() {
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        ConsentInfo info = l.consentInfo();
        assertEquals(target.assetName, info.zipName);
        assertEquals(target.url, info.downloadUrl);
        assertEquals(new File(base, "import"), info.importDir);
        assertTrue(info.importDir.isDirectory(), "created so the player can find it");
        assertEquals(base, info.installDir);
        assertEquals(base.getPath(), info.installDirDisplay);
        assertTrue(info.importDirDisplay.endsWith("import"), info.importDirDisplay);
        assertTrue(info.diskBytes > 0 && info.requiredFreeBytes >= info.downloadBytes);
    }

    @Test
    void progressIsReportedWhileDownloadingOnTheInstallerThread() throws Exception {
        server.gateAt = zip.length / 2;
        // The production executor: a daemon "AkashicWeb-Engine-Installer" thread.
        EngineLocatorImpl l = builder(TestSupport.linuxDesktop(), target).executor(null)
            .build();
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
        installed().release(); // the game with the old mod version exited
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
        assertTrue(
            new String(Files.readAllBytes(new File(app, "src/main.js").toPath()), StandardCharsets.UTF_8)
                .contains("v2"));
        assertTrue(
            EngineStore.inspect(engineDir(), java.util.Collections.singletonList(target), true)
                .intact(),
            "manifest and marker were rewritten");
    }

    @Test
    void damagedEngineFailsAtStartupAndRequestInstallRepairsIt() throws Exception {
        installed().release();
        Files.write(new File(engineDir(), "libffmpeg.so").toPath(), new byte[] { 1, 2, 3 });
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.FAILED, l.state().kind);
        assertEquals(ReasonCode.HASH_MISMATCH, l.state().reason);
        l.requestInstall();
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        assertTrue(
            EngineStore.inspect(engineDir(), java.util.Collections.singletonList(target), true)
                .intact());
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
        installed().release();
        File main = new File(engineDir(), "resources/app/src/main.js");
        Files.write(main.toPath(), "evil()".getBytes(StandardCharsets.UTF_8));
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        assertTrue(
            EngineStore.inspect(engineDir(), java.util.Collections.singletonList(target), true)
                .intact());
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
        EngineTarget targetB = TestSupport
            .target("linux-x64", "44.8.0", server.canonicalUrl("electron-v44.8.0-linux-x64.zip"), zipB, false);
        // A is the previous pin: this mod version can still check it file by file.
        EngineLocatorImpl b = started(
            builder(TestSupport.linuxDesktop(), targetB).previousTargets(java.util.Collections.singletonList(target)));
        b.requestInstall();
        assertEquals(EngineState.Kind.READY, b.state().kind, b.detail());
        File dirB = b.hostExecutable()
            .getParentFile();
        assertTrue(dirA.isDirectory(), "the previous engine is kept for rollback");

        // B's binary is corrupted in place (same size): the startup check cannot see it, the full re-hash does.
        File exeB = new File(dirB, "AkashicWebHost");
        byte[] bytes = Files.readAllBytes(exeB.toPath());
        bytes[0] ^= 1;
        Files.write(exeB.toPath(), bytes);
        b.verifyAfterLoadFailure();
        assertEquals(EngineState.Kind.READY, b.state().kind, b.detail());
        assertEquals(
            dirA,
            b.hostExecutable()
                .getParentFile());
        assertTrue(
            b.detail()
                .contains("rolled back"),
            b.detail());
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
        installed().release();
        File first = engineDir();
        List<File> dirs = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            byte[] z = TestSupport.zip(TestSupport.linuxLikeEntries(100 + i));
            server.body = z;
            server.etag = "\"e" + i + "\"";
            EngineTarget t = TestSupport
                .target("linux-x64", "44.8." + i, server.canonicalUrl("electron-v44.8." + i + ".zip"), z, false);
            EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), t));
            l.requestInstall();
            assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
            dirs.add(
                l.hostExecutable()
                    .getParentFile());
            l.release();
        }
        assertFalse(first.exists(), "the oldest engine was removed");
        assertTrue(
            dirs.get(0)
                .isDirectory());
        assertTrue(
            dirs.get(1)
                .isDirectory());
    }

    @Test
    void userDataDirIsPerInstance() {
        EngineLocatorImpl one = started(builder(TestSupport.linuxDesktop(), target));
        EngineLocatorImpl two = started(builder(TestSupport.linuxDesktop(), target).gameDir(new File(tmp, "other")));
        assertEquals(
            one.userDataDir()
                .getParentFile(),
            two.userDataDir()
                .getParentFile());
        assertEquals(
            new File(base, "profiles"),
            one.userDataDir()
                .getParentFile());
        assertFalse(
            one.userDataDir()
                .equals(two.userDataDir()),
            "two instances never share a Chromium profile");
    }

    private static Map<String, String> hostAppV2() {
        Map<String, String> v2 = new LinkedHashMap<>();
        v2.put("package.json", "{\"name\":\"akashicweb-host\",\"main\":\"src/main.js\",\"version\":\"2\"}\n");
        v2.put("src/main.js", "'use strict';\n// test host app v2\n");
        return v2;
    }

    private String installedMainJs() throws Exception {
        return new String(
            Files.readAllBytes(new File(engineDir(), "resources/app/src/main.js").toPath()),
            StandardCharsets.UTF_8);
    }

    /**
     * Review finding (medium, 2, probe 'skew'): a second game instance with another mod version must not rewrite the
     * host app the first instance's host runs from. It reports ENGINE_IN_USE; once the first one exits, Repair updates.
     */
    @Test
    void anotherModVersionNeverRewritesTheHostAppOfARunningInstance() throws Exception {
        EngineLocatorImpl v1 = installed();
        EngineLocatorImpl v2 = started(
            builder(TestSupport.linuxDesktop(), target).hostApp(TestSupport.hostApp(hostAppV2())));
        assertEquals(EngineState.Kind.FAILED, v2.state().kind);
        assertEquals(ReasonCode.ENGINE_IN_USE, v2.state().reason, v2.detail());
        assertTrue(installedMainJs().contains("v1"), "the running instance's app is untouched");
        assertEquals(EngineState.Kind.READY, v1.state().kind);

        v1.release(); // the first game exits
        v2.repair();
        assertEquals(EngineState.Kind.READY, v2.state().kind, v2.detail());
        assertTrue(installedMainJs().contains("v2"));

        // The first version starts again while v2 runs: now it is the one told to wait, and nothing swaps back.
        EngineLocatorImpl again = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(ReasonCode.ENGINE_IN_USE, again.state().reason);
        assertTrue(installedMainJs().contains("v2"));
    }

    /** Review finding (medium, 2): verify() no longer calls a folder intact when its app is another version's. */
    @Test
    void verifyNoticesAHostAppThatIsNotThisVersions() throws Exception {
        EngineLocatorImpl v1 = installed();
        v1.release();
        EngineLocatorImpl v2 = started(
            builder(TestSupport.linuxDesktop(), target).hostApp(TestSupport.hostApp(hostAppV2())));
        assertEquals(EngineState.Kind.READY, v2.state().kind, v2.detail());
        // v1 still thinks it is READY (it was released behind its back in this test) and its host fails to load.
        v1.verifyAfterLoadFailure();
        assertEquals(ReasonCode.ENGINE_IN_USE, v1.state().reason, "v2 runs from the folder: v1 must not swap it");
        v2.release();
        v1.verifyAfterLoadFailure();
        assertEquals(EngineState.Kind.READY, v1.state().kind, v1.detail());
        assertTrue(installedMainJs().contains("v1"));
    }

    /** Review finding (medium, 2): the lock holds across processes (a second JVM), as two game instances are. */
    @Test
    void aHostAppHeldByAnotherProcessIsNotRewritten() throws Exception {
        installed().release();
        Process holder = AppLockHolder.start(AppUseLock.fileFor(engineDir()));
        try {
            EngineLocatorImpl v2 = started(
                builder(TestSupport.linuxDesktop(), target).hostApp(TestSupport.hostApp(hostAppV2())));
            assertEquals(ReasonCode.ENGINE_IN_USE, v2.state().reason, v2.detail());
            assertTrue(installedMainJs().contains("v1"));
            holder.destroy();
            assertTrue(holder.waitFor(10, TimeUnit.SECONDS));
            v2.repair();
            assertEquals(EngineState.Kind.READY, v2.state().kind, v2.detail());
            assertTrue(installedMainJs().contains("v2"));
        } finally {
            holder.destroy();
        }
    }

    @Test
    void anOldEngineStillInUseIsNotPruned() throws Exception {
        EngineLocatorImpl first = installed(); // keeps running from the oldest engine
        File oldest = engineDir();
        for (int i = 1; i <= 2; i++) {
            byte[] z = TestSupport.zip(TestSupport.linuxLikeEntries(200 + i));
            server.body = z;
            server.etag = "\"p" + i + "\"";
            EngineTarget t = TestSupport
                .target("linux-x64", "44.9." + i, server.canonicalUrl("electron-v44.9." + i + ".zip"), z, false);
            EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), t));
            l.requestInstall();
            assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
            l.release();
        }
        assertTrue(new File(oldest, LocalManifest.MARKER).isFile(), "the engine a running instance uses stays whole");
        assertEquals(EngineState.Kind.READY, first.state().kind);
    }

    /**
     * Review finding (medium, 1a, probe 'forged'): a hand-made engine folder with its own manifest and marker is not
     * trusted, because its files are not the pinned ones. Install replaces it with the real engine.
     */
    @Test
    void aForgedEngineFolderIsNotTrusted() throws Exception {
        File dir = engineDir();
        assertTrue(new File(dir, "resources").mkdirs());
        Files.write(
            new File(dir, "AkashicWebHost").toPath(),
            "#!/bin/sh\necho pwned\n".getBytes(StandardCharsets.UTF_8));
        List<LocalManifest.Entry> forged = new ArrayList<>(
            HostApp.install(TestSupport.defaultHostApp(), new File(dir, EngineManifest.APP_DIR)));
        forged.add(
            new LocalManifest.Entry(
                "AkashicWebHost",
                new File(dir, "AkashicWebHost").length(),
                Sha256.of(new File(dir, "AkashicWebHost"))));
        LocalManifest m = new LocalManifest(target.engineId(), target.sha256, forged);
        m.write(dir);
        new EngineStore(new File(base, "engine")).writeMarker(dir, m, "AkashicWebHost", 1L);

        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.FAILED, l.state().kind);
        assertEquals(ReasonCode.HASH_MISMATCH, l.state().reason);
        assertTrue(
            l.detail()
                .contains("not the pinned"),
            l.detail());
        assertNull(l.hostExecutable());
        l.requestInstall();
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        assertTrue(
            EngineStore.inspect(dir, java.util.Collections.singletonList(target), true)
                .intact());
    }

    /**
     * Review finding (medium, 1a): a self-consistent but foreign host app (manifest and marker rewritten) is replaced.
     */
    @Test
    void aSelfConsistentForeignHostAppIsReplacedFromTheJar() throws Exception {
        installed().release();
        File dir = engineDir();
        File main = new File(dir, "resources/app/src/main.js");
        Files.write(main.toPath(), "evil()".getBytes(StandardCharsets.UTF_8));
        LocalManifest old = LocalManifest.read(dir);
        List<LocalManifest.Entry> entries = new ArrayList<>();
        for (LocalManifest.Entry e : old.entries) {
            entries.add(
                e.path.equals("resources/app/src/main.js")
                    ? new LocalManifest.Entry(e.path, main.length(), Sha256.of(main))
                    : e);
        }
        LocalManifest forged = new LocalManifest(old.engineId, old.zipSha256, entries);
        forged.write(dir);
        new EngineStore(new File(base, "engine")).writeMarker(dir, forged, "AkashicWebHost", 2L);

        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.READY, l.state().kind, l.detail());
        assertTrue(installedMainJs().contains("test host app v1"), "the jar's app is back");
    }

    @Test
    void rollbackGoesOnlyToAnEngineThisVersionHasPinsFor() throws Exception {
        installed().release();
        File dirA = engineDir();
        byte[] zipB = TestSupport.zip(TestSupport.linuxLikeEntries(71));
        server.body = zipB;
        server.etag = "\"b2\"";
        EngineTarget targetB = TestSupport
            .target("linux-x64", "44.8.0", server.canonicalUrl("electron-v44.8.0-linux-x64.zip"), zipB, false);
        EngineLocatorImpl b = started(builder(TestSupport.linuxDesktop(), targetB)); // A's pins unknown to "this" jar
        b.requestInstall();
        assertEquals(EngineState.Kind.READY, b.state().kind, b.detail());
        File exeB = b.hostExecutable();
        byte[] bytes = Files.readAllBytes(exeB.toPath());
        bytes[0] ^= 1;
        Files.write(exeB.toPath(), bytes);
        b.verifyAfterLoadFailure();
        assertEquals(EngineState.Kind.FAILED, b.state().kind, b.detail());
        assertTrue(new File(dirA, LocalManifest.MARKER).isFile(), "the unpinned engine was left alone, not used");
    }

    /**
     * Review finding (medium, 5, mutant M5): the marker binds the manifest's content (its canonical rendering). A
     * manifest whose content changed after the install, here the zip it claims to come from, is not trusted.
     */
    @Test
    void aManifestThatNoLongerMatchesItsMarkerIsNotTrusted() throws Exception {
        installed().release();
        File manifest = new File(engineDir(), LocalManifest.FILE);
        String text = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
        Files.write(
            manifest.toPath(),
            text.replace("# zip-sha256 " + target.sha256, "# zip-sha256 " + Sha256.ofUtf8("another zip"))
                .getBytes(StandardCharsets.UTF_8));
        EngineLocatorImpl l = started(builder(TestSupport.linuxDesktop(), target));
        assertEquals(EngineState.Kind.FAILED, l.state().kind);
        assertEquals(ReasonCode.HASH_MISMATCH, l.state().reason);
        assertTrue(
            l.detail()
                .contains("manifest does not match marker"),
            l.detail());
    }
}
