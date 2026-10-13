package com.akashiic.web.client.host;

import static com.akashiic.web.client.host.FakeHostFixture.anyLineStartsWith;
import static com.akashiic.web.client.host.FakeHostFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.akashiic.web.client.install.EngineLocator;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.common.ReasonCode;

/**
 * End-to-end: HostSupervisor + HostConnection + BrowserHostImpl against {@link FakeHostMain} running as a real child
 * JVM. Backoff time is a fake clock, so relaunch schedules are asserted without waiting for them.
 */
@Timeout(120)
class HostSupervisorTest {

    @TempDir
    File dir;

    private BrowserHostImpl host(FakeHostFixture f, HostLaunchConfig config) {
        BrowserHostImpl h = new BrowserHostImpl(config, f.clock, ProcessControl.forThisPlatform());
        h.addListener(f.statuses);
        return h;
    }

    @Test
    void handshakeSucceedsWithSanitisedEnvironmentAndArguments() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "ok");
        BrowserHostImpl h = host(
            f,
            f.config()
                .build());
        try {
            h.start();
            await("READY", 60_000, () -> h.status().kind == HostStatus.Kind.READY);
            await(
                "WELCOME at the host",
                10_000,
                () -> lines(f).stream()
                    .anyMatch(l -> l.startsWith("recv 32 0 0 ")));
            List<String> lines = lines(f);
            // The parent had NODE_OPTIONS, ELECTRON_RUN_AS_NODE, mixed-case variants and a stale AKW_TOKEN.
            assertTrue(lines.contains("env bad=[] akw=true"), lines.toString());
            assertTrue(lines.contains("stdin-token len32"), lines.toString());
            assertTrue(lines.contains("env-token-equal true"), "AKW_TOKEN must be this launch's token: " + lines);
            String start = lines.stream()
                .filter(l -> l.startsWith("start "))
                .findFirst()
                .get();
            assertTrue(start.contains("--akw-port="), start);
            assertTrue(start.contains("--akw-parent-pid=" + HostCommandLine.currentPid()), start);
            assertTrue(start.contains("--akw-user-data=" + new File(dir, "userdata").getAbsolutePath()), start);
            assertTrue(start.contains("--akw-log=" + new File(dir, "logs/host.log").getAbsolutePath()), start);
            assertFalse(start.contains("inspect"), start);
            String welcome = lines.stream()
                .filter(l -> l.startsWith("recv 32 "))
                .findFirst()
                .get();
            assertTrue(
                welcome.endsWith(
                    "{\"protoMajor\":1,\"policy\":{\"mode\":\"allowlist\",\"allow\":[],\"block\":[]},\"locale\":\"pt-BR\"}"),
                welcome);
            assertEquals(
                1,
                h.supervisor()
                    .launches());
            assertTrue(
                h.diagnostics()
                    .stream()
                    .anyMatch(d -> d.contains("fake-1")),
                h.diagnostics()
                    .toString());
            assertFalse(
                String.join("\n", h.diagnostics())
                    .matches("(?s).*[0-9a-f]{32}.*"),
                "token in diagnostics");
        } finally {
            h.shutdown();
        }
    }

    @Test
    void wrongTokenIsRejectedAndTheHostRelaunched() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "bad-token");
        BrowserHostImpl h = host(
            f,
            f.config()
                .build());
        try {
            h.start();
            await(
                "DOWN(HOST_HANDSHAKE)",
                60_000,
                () -> f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_HANDSHAKE));
            assertEquals(
                HostFailure.BAD_TOKEN,
                h.supervisor()
                    .lastFailure());
            assertFalse(f.statuses.saw(HostStatus.Kind.READY, ReasonCode.OK));
            await(
                "relaunch after 1 s backoff",
                60_000,
                () -> h.supervisor()
                    .launches() >= 2);
            assertEquals(
                1000L,
                f.clock.sleeps()
                    .get(0)
                    .longValue());
            await("rejected host saw EOF", 10_000, () -> lines(f).contains("eof"));
        } finally {
            h.shutdown();
        }
    }

    @Test
    void protocolMajorMismatchDisablesWithoutRelaunch() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "bad-major");
        BrowserHostImpl h = host(
            f,
            f.config()
                .build());
        try {
            h.start();
            await("DISABLED(PROTOCOL_MISMATCH)", 60_000, () -> h.status().kind == HostStatus.Kind.DISABLED);
            assertEquals(ReasonCode.PROTOCOL_MISMATCH, h.status().reason);
            assertEquals(
                HostFailure.PROTOCOL_MAJOR,
                h.supervisor()
                    .lastFailure());
            Thread.sleep(300);
            assertEquals(
                1,
                h.supervisor()
                    .launches());
            assertTrue(
                f.clock.sleeps()
                    .isEmpty());
        } finally {
            h.shutdown();
        }
    }

    @Test
    void hostExitRelaunchesWithBackoffAndCrashLoopGuardDisablesUntilReenabled() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "exit-after-welcome");
        BrowserHostImpl h = host(
            f,
            f.config()
                .build());
        try {
            h.start();
            await("crash-loop guard", 110_000, () -> h.status().kind == HostStatus.Kind.DISABLED);
            assertEquals(ReasonCode.HOST_CRASH_LOOP, h.status().reason);
            assertEquals(
                5,
                h.supervisor()
                    .launches());
            assertEquals(Arrays.asList(1000L, 2000L, 4000L, 8000L), f.clock.sleeps());
            assertTrue(f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_EXITED));
            Thread.sleep(300);
            assertEquals(
                5,
                h.supervisor()
                    .launches(),
                "must stay disabled until re-enabled");

            f.mode("ok");
            h.reenable();
            await("READY after reenable", 60_000, () -> h.status().kind == HostStatus.Kind.READY);
            assertEquals(
                6,
                h.supervisor()
                    .launches());
        } finally {
            h.shutdown();
        }
    }

    @Test
    void healthyRunsResetTheBackoff() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "exit-after-welcome");
        BrowserHostImpl h = host(
            f,
            f.config()
                .stableRunMillis(0)
                .build());
        try {
            h.start();
            await(
                "three launches",
                90_000,
                () -> h.supervisor()
                    .launches() >= 3);
        } finally {
            h.shutdown();
        }
        List<Long> sleeps = f.clock.sleeps();
        assertTrue(sleeps.size() >= 2, sleeps.toString());
        for (long s : sleeps) assertEquals(1000L, s, sleeps.toString());
    }

    @Test
    void connectTimeoutKillsASilentHost() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "silent");
        BrowserHostImpl h = host(
            f,
            f.config()
                .connectTimeoutMillis(3000)
                .build());
        try {
            h.start();
            await(
                "CONNECT_TIMEOUT",
                60_000,
                () -> h.supervisor()
                    .lastFailure() == HostFailure.CONNECT_TIMEOUT
                    && f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_HANDSHAKE));
            long pid = h.supervisor()
                .hostPid();
            assertTrue(pid > 0);
            ProcessControl pc = ProcessControl.forThisPlatform();
            await(
                "silent host killed",
                10_000,
                () -> pc.imageName(pid) == null || h.supervisor()
                    .hostPid() != pid);
        } finally {
            h.shutdown();
        }
    }

    @Test
    void earlyExitIsReportedWithoutWaitingForTheConnectTimeout() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "die");
        BrowserHostImpl h = host(
            f,
            f.config()
                .connectTimeoutMillis(60_000)
                .build());
        try {
            long t0 = System.nanoTime();
            h.start();
            await(
                "EXITED_BEFORE_CONNECT",
                50_000,
                () -> h.supervisor()
                    .lastFailure() == HostFailure.EXITED_BEFORE_CONNECT
                    && f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_EXITED));
            assertTrue((System.nanoTime() - t0) / 1_000_000L < 50_000);
            assertTrue(
                h.supervisor()
                    .lastFailureDetail()
                    .contains("exit code 3"),
                h.supervisor()
                    .lastFailureDetail());
        } finally {
            h.shutdown();
        }
    }

    @Test
    void tokenFromTheChildEnvironmentIsAccepted() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "env-token");
        BrowserHostImpl h = host(
            f,
            f.config()
                .build());
        try {
            h.start();
            await("READY", 60_000, () -> h.status().kind == HostStatus.Kind.READY);
        } finally {
            h.shutdown();
        }
    }

    @Test
    void protocolErrorAfterHandshakeEndsTheRun() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "garbage");
        BrowserHostImpl h = host(
            f,
            f.config()
                .build());
        try {
            h.start();
            await(
                "PROTOCOL_ERROR",
                60_000,
                () -> h.supervisor()
                    .lastFailure() == HostFailure.PROTOCOL_ERROR
                    && f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_EXITED));
        } finally {
            h.shutdown();
        }
    }

    @Test
    void missingExecutableIsALaunchFailure() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "ok");
        HostLaunchConfig config = HostLaunchConfig
            .builder(
                HostExecutable.fixed(
                    Collections.singletonList(new File(dir, "no-such-host").getAbsolutePath()),
                    new File(dir, "userdata")),
                new File(dir, "logs"),
                new File(dir, "state"))
            .build();
        BrowserHostImpl h = host(f, config);
        try {
            h.start();
            // With the instant fake clock five failed launches trip the crash-loop guard almost at once.
            await("crash loop of launch failures", 20_000, () -> h.status().kind == HostStatus.Kind.DISABLED);
            assertEquals(ReasonCode.HOST_CRASH_LOOP, h.status().reason);
            assertTrue(f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_EXITED));
            assertTrue(
                h.supervisor()
                    .lastFailureDetail()
                    .startsWith("LAUNCH_FAILED: "),
                h.supervisor()
                    .lastFailureDetail());
            assertEquals(Arrays.asList(1000L, 2000L, 4000L, 8000L), f.clock.sleeps());
            assertEquals(
                0,
                h.supervisor()
                    .launches(),
                "nothing was started");
        } finally {
            h.shutdown();
        }
    }

    @Test
    void engineThatIsNotReadyDisablesWithTheInstallerReason() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "ok");
        StubEngine engine = new StubEngine(new EngineState(EngineState.Kind.FAILED, 0, ReasonCode.HASH_MISMATCH));
        HostLaunchConfig config = HostLaunchConfig
            .builder(HostExecutable.fromEngine(engine), new File(dir, "logs"), new File(dir, "state"))
            .build();
        BrowserHostImpl h = host(f, config);
        try {
            h.start();
            await("DISABLED", 10_000, () -> h.status().kind == HostStatus.Kind.DISABLED);
            assertEquals(ReasonCode.HASH_MISMATCH, h.status().reason);

            engine.state = new EngineState(EngineState.Kind.CONSENT_NEEDED, 0, ReasonCode.OK);
            h.reenable();
            await(
                "DISABLED(ENGINE_ABSENT)",
                10_000,
                () -> f.statuses.saw(HostStatus.Kind.DISABLED, ReasonCode.ENGINE_ABSENT));
            assertEquals(
                0,
                h.supervisor()
                    .launches());
        } finally {
            h.shutdown();
        }
    }

    @Test
    void shutdownSendsQuitAndTheHostExits() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "ok");
        BrowserHostImpl h = host(
            f,
            f.config()
                .build());
        h.start();
        await("READY", 60_000, () -> h.status().kind == HostStatus.Kind.READY);
        long pid = h.supervisor()
            .hostPid();
        h.shutdown();
        List<String> lines = lines(f);
        assertTrue(anyLineStartsWith(lines, "recv 43 0 "), lines.toString());
        assertTrue(lines.contains("bye"), lines.toString());
        assertEquals(HostStatus.Kind.DOWN, h.status().kind);
        assertEquals(ReasonCode.OK, h.status().reason);
        ProcessControl pc = ProcessControl.forThisPlatform();
        await("host process gone", 10_000, () -> pc.imageName(pid) == null);
        assertNull(h.frames(1));
    }

    private static List<String> lines(FakeHostFixture f) {
        try {
            return f.allRecords();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class StubEngine implements EngineLocator {

        volatile EngineState state;

        StubEngine(EngineState state) {
            this.state = state;
        }

        @Override
        public EngineState state() {
            return state;
        }

        @Override
        public File hostExecutable() {
            return null;
        }

        @Override
        public File userDataDir() {
            return new File("userdata");
        }

        @Override
        public String engineId() {
            return "stub";
        }

        @Override
        public void requestInstall() {}
    }
}
