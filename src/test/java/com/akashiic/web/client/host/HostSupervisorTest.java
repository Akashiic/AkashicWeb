package com.akashiic.web.client.host;

import static com.akashiic.web.client.host.FakeHostFixture.anyLineStartsWith;
import static com.akashiic.web.client.host.FakeHostFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Review finding (medium): the old check ({@code imageName(pid) == null || hostPid() != pid}) was satisfied by the
     * instant relaunch alone, so it passed with killNow disabled and left orphaned hosts behind. It now follows the
     * first silent host's own PID (from its record line), which only killNow can end: the fake sleeps 120 s.
     */
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
            await("the first host's record", 10_000, () -> !lines(f).isEmpty());
            long first = firstPid(f);
            assertTrue(first > 0);
            ProcessControl pc = ProcessControl.forThisPlatform();
            await("the first silent host was killed", 10_000, () -> pc.imageName(first) == null);
            assertTrue(
                h.supervisor()
                    .lastFailureDetail()
                    .contains("exit code"),
                "stopProcess saw it end: " + h.supervisor()
                    .lastFailureDetail());
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
                    && f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_PROTOCOL_ERROR));
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
            // With the instant fake clock five failed launches trip the crash-loop guard almost at once. Not one of
            // them started, so the player is told the browser could not be started, not that it keeps crashing.
            await("crash loop of launch failures", 20_000, () -> h.status().kind == HostStatus.Kind.DISABLED);
            assertEquals(ReasonCode.HOST_LAUNCH_FAILED, h.status().reason);
            assertTrue(f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_LAUNCH_FAILED));
            assertFalse(f.statuses.saw(HostStatus.Kind.DISABLED, ReasonCode.HOST_CRASH_LOOP));
            assertEquals(
                HostFailure.LAUNCH_FAILED,
                h.supervisor()
                    .lastFailure());
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

    /**
     * Review finding (high), at the supervisor: an Error escaping into the reader thread (here from the inbound
     * handler, bypassing BrowserHostImpl's listener isolation) used to kill the reader without closing the connection:
     * READY forever, frozen frames, no relaunch. Now the run ends visibly and the host is relaunched.
     */
    @Test
    void anErrorOnTheReaderThreadEndsTheRunAndTheHostIsRelaunched() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "ok");
        AtomicInteger states = new AtomicInteger();
        HostConnection.Inbound inbound = new NullInbound() {

            @Override
            public void onState(int screenId, String json) {
                if (states.incrementAndGet() == 1) throw new NoClassDefFoundError("simulated linkage error on STATE");
            }
        };
        HostSupervisor s = new HostSupervisor(
            f.config()
                .build(),
            f.clock,
            ProcessControl.forThisPlatform(),
            inbound,
            new Callbacks(f, false));
        try {
            s.start();
            await("relaunch after the reader died", 60_000, () -> s.launches() == 2 && s.status() == HostStatus.READY);
            assertTrue(f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_EXITED), f.statuses.seen.toString());
            await("STATE from the second host", 20_000, () -> states.get() >= 2);
            assertEquals(Collections.singletonList(1000L), f.clock.sleeps());
        } finally {
            s.shutdown();
        }
    }

    /**
     * Review finding (medium): status listeners and the connect callback run on the supervisor thread. An Error from
     * them (only RuntimeException was caught) killed that thread, leaving STARTING or READY with no host and no
     * relaunch for the rest of the session.
     */
    @Test
    void errorsFromStatusListenersAndCallbacksNeverStopTheSupervisor() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "ok");
        Callbacks callbacks = new Callbacks(f, true);
        HostSupervisor s = new HostSupervisor(
            f.config()
                .build(),
            f.clock,
            ProcessControl.forThisPlatform(),
            new NullInbound(),
            callbacks);
        try {
            s.start();
            // First run: onConnected throws an AssertionError, so the run ends and the host is relaunched; every
            // onStatus throws a NoClassDefFoundError, yet each status is still published.
            await("READY on the second launch", 60_000, () -> s.status() == HostStatus.READY && s.launches() == 2);
            assertTrue(f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_EXITED), f.statuses.seen.toString());
            assertTrue(f.statuses.saw(HostStatus.Kind.READY, ReasonCode.OK));
            // The supervisor still handles a host exit afterwards.
            callbacks.connection.sendAction(1, "stop"); // the fake host "crashes" on stop
            await("third launch", 60_000, () -> s.launches() == 3 && s.status() == HostStatus.READY);
        } finally {
            s.shutdown();
        }
    }

    /**
     * Review finding (medium): a host whose main thread hangs while keeping its socket open was never acted on (one
     * log line, a stale round trip, READY with frozen frames forever). A PING unanswered for stallIntervals whole
     * ping intervals now closes the connection as HOST_STALLED, and the normal DOWN, backoff and crash-loop path
     * replaces the host.
     */
    @Test
    void aHostThatStopsAnsweringIsReplacedWithHostStalled() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "hang-once");
        BrowserHostImpl h = host(
            f,
            f.config()
                .pingIntervalMillis(200)
                .stallIntervals(8)
                .build());
        try {
            h.start();
            await("first READY", 60_000, () -> h.status().kind == HostStatus.Kind.READY);
            long readyAt = System.nanoTime();
            await(
                "/web doctor shows the overdue PONG",
                30_000,
                () -> h.diagnostics()
                    .stream()
                    .anyMatch(d -> d.contains("PONG overdue for")));
            await("DOWN(HOST_STALLED)", 30_000, () -> f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_STALLED));
            long tookMillis = (System.nanoTime() - readyAt) / 1_000_000L;
            assertTrue(tookMillis >= 8 * 200, "not before 8 unanswered intervals: " + tookMillis + " ms");
            assertTrue(lines(f).contains("hanging"), lines(f).toString());
            long hungPid = firstPid(f);
            await(
                "relaunched and READY",
                60_000,
                () -> h.supervisor()
                    .launches() == 2 && h.status().kind == HostStatus.Kind.READY);
            assertEquals(
                HostFailure.STALLED,
                h.supervisor()
                    .lastFailure());
            assertTrue(
                h.supervisor()
                    .lastFailureDetail()
                    .startsWith("no PONG for "),
                h.supervisor()
                    .lastFailureDetail());
            assertEquals(Collections.singletonList(1000L), f.clock.sleeps(), "the normal backoff path");
            ProcessControl pc = ProcessControl.forThisPlatform();
            await("the hung host was killed", 10_000, () -> pc.imageName(hungPid) == null);
        } finally {
            h.shutdown();
        }
    }

    /**
     * Review finding (low): killNow ended only the main process (SIGTERM, then SIGKILL of one pid). A host that does
     * not act on SIGTERM (a hung main thread) and has children of its own (Chromium's GPU, renderer and utility
     * processes) now has its whole tree ended: ProcessHandle descendants on Java 9+, the /proc tree on Java 8 Linux,
     * taskkill /T on Windows.
     */
    @Test
    void aHungHostThatIgnoresSigtermIsKilledWithItsChildren() throws Exception {
        assumeFalse(
            System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .startsWith("windows"),
            "uses sleep and SIGTERM");
        FakeHostFixture f = new FakeHostFixture(dir, "stubborn-hang-once");
        BrowserHostImpl h = host(
            f,
            f.config()
                .pingIntervalMillis(200)
                .stallIntervals(3)
                .build());
        try {
            h.start();
            await("DOWN(HOST_STALLED)", 30_000, () -> f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_STALLED));
            long hostPid = firstPid(f);
            long childPid = -1;
            for (String l : lines(f)) if (l.startsWith("child ")) childPid = Long.parseLong(l.substring(6));
            assertTrue(childPid > 0, lines(f).toString());
            long child = childPid;
            await(
                "the stubborn host and its child are both gone",
                15_000,
                () -> !FakeHostFixture.running(hostPid) && !FakeHostFixture.running(child));
            await(
                "relaunched and READY",
                60_000,
                () -> h.supervisor()
                    .launches() == 2 && h.status().kind == HostStatus.Kind.READY);
        } finally {
            h.shutdown();
        }
    }

    /** The other side of the stall rule: a host that answers late, but within the limit, is left alone. */
    @Test
    void slowPongsWithinTheStallLimitKeepTheHost() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "slow-pong:300");
        BrowserHostImpl h = host(
            f,
            f.config()
                .pingIntervalMillis(200)
                .stallIntervals(6)
                .build());
        try {
            h.start();
            await("READY", 60_000, () -> h.status().kind == HostStatus.Kind.READY);
            await(
                "a measured round trip",
                30_000,
                () -> h.diagnostics()
                    .stream()
                    .anyMatch(d -> d.matches("ping round trip: \\d+ ms.*")));
            Thread.sleep(3000); // 15 ping intervals, every PONG 1.5 intervals late
            assertEquals(HostStatus.Kind.READY, h.status().kind);
            assertEquals(
                1,
                h.supervisor()
                    .launches());
            assertFalse(f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_STALLED));
        } finally {
            h.shutdown();
        }
    }

    /** Inbound that ignores everything (frames discarded). */
    static class NullInbound implements HostConnection.Inbound {

        @Override
        public FramePipeline framesFor(int screenId) {
            return null;
        }

        @Override
        public void onPcm(int screenId, PcmChunk chunk) {}

        @Override
        public void onState(int screenId, String json) {}

        @Override
        public void onMedia(int screenId, String json) {}

        @Override
        public void onEvent(int screenId, String json) {}
    }

    /** Sends WELCOME and one CREATE per connection; optionally throws Errors like a broken game listener would. */
    static final class Callbacks implements HostSupervisor.Callbacks {

        final FakeHostFixture f;
        final boolean hostile;
        final AtomicInteger connects = new AtomicInteger();
        volatile HostConnection connection;

        Callbacks(FakeHostFixture f, boolean hostile) {
            this.f = f;
            this.hostile = hostile;
        }

        @Override
        public void onConnected(HostConnection c, HelloInfo hello) {
            if (hostile && connects.incrementAndGet() == 1) throw new AssertionError("simulated callback failure");
            c.sendWelcome(null, "pt-BR", null);
            c.sendCreate(
                1,
                new CreateSpec(16, 16, 30, "about:blank", CreateSpec.Session.SHARED, CreateSpec.Audio.OS, 0f));
            connection = c;
        }

        @Override
        public void onDisconnected(HostConnection c) {}

        @Override
        public void onStatus(HostStatus status) {
            f.statuses.onStatus(status);
            if (hostile) throw new NoClassDefFoundError("simulated status listener failure");
        }
    }

    /** PID of the first fake host launched in this test (its record lines start with "pid=N"). */
    private static long firstPid(FakeHostFixture f) throws java.io.IOException {
        String key = f.records()
            .keySet()
            .iterator()
            .next();
        return Long.parseLong(key.substring("pid=".length()));
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
