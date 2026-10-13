package com.akashiic.web.client.host;

import static com.akashiic.web.client.host.FakeHostFixture.anyLineStartsWith;
import static com.akashiic.web.client.host.FakeHostFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // --- host exit codes (docs/PROTOCOL.md section 1) ----------------------------------------------------------

    @Test
    void exitCodesMapToFailures() {
        assertEquals(HostFailure.BAD_ARGUMENT, HostFailure.forExitCode(64, false));
        assertEquals(HostFailure.FORBIDDEN_SWITCH, HostFailure.forExitCode(65, false));
        assertEquals(HostFailure.NO_TOKEN, HostFailure.forExitCode(66, false));
        assertEquals(HostFailure.CONNECT_FAILED, HostFailure.forExitCode(67, false));
        assertEquals(HostFailure.WELCOME_TIMEOUT, HostFailure.forExitCode(68, false));
        assertEquals(HostFailure.WELCOME_REFUSED, HostFailure.forExitCode(68, true));
        assertEquals(HostFailure.BYTES_REFUSED, HostFailure.forExitCode(69, true));
        for (int code : new int[] { 0, 1, 3, 70, 137, 143, -1 }) {
            assertNull(HostFailure.forExitCode(code, true), "exit " + code + " keeps the failure seen");
        }
        assertTrue(HostFailure.FORBIDDEN_SWITCH.disables(), "the same command line would be refused again");
        assertTrue(HostFailure.BAD_ARGUMENT.disables());
        assertFalse(HostFailure.CONNECT_FAILED.disables(), "relaunched with backoff");
        assertFalse(HostFailure.NO_TOKEN.disables());
        assertEquals(ReasonCode.HOST_LAUNCH_FAILED, HostFailure.BAD_ARGUMENT.reason());
        assertEquals(ReasonCode.HOST_FORBIDDEN_SWITCH, HostFailure.FORBIDDEN_SWITCH.reason());
        assertEquals(ReasonCode.HOST_CONNECT_FAILED, HostFailure.CONNECT_FAILED.reason());
        assertEquals(ReasonCode.HOST_HANDSHAKE, HostFailure.NO_TOKEN.reason());
        assertEquals(ReasonCode.HOST_HANDSHAKE, HostFailure.WELCOME_TIMEOUT.reason());
        assertEquals(ReasonCode.HOST_PROTOCOL_ERROR, HostFailure.WELCOME_REFUSED.reason());
        assertEquals(ReasonCode.HOST_PROTOCOL_ERROR, HostFailure.BYTES_REFUSED.reason());
        assertNull(HostFailure.describeExit(3));
        assertEquals(500L, HostLaunchConfig.DEFAULT_EXIT_CODE_WAIT_MILLIS, "a bounded wait on the supervisor thread");
    }

    /** The Java constants against the host's own table in host/src/args.js (skipped when host/ is not found). */
    @Test
    void exitCodesMatchTheHostsArgsJs() throws Exception {
        File host = RealHostEndToEndTest.findHostDir();
        assumeTrue(host != null, "host/ not found");
        String js = new String(Files.readAllBytes(new File(host, "src/args.js").toPath()), StandardCharsets.UTF_8);
        Matcher table = Pattern.compile("const EXIT = Object\\.freeze\\(\\{([^}]*)\\}\\)")
            .matcher(js);
        assertTrue(table.find(), "EXIT table in args.js");
        Map<String, Integer> codes = new LinkedHashMap<>();
        Matcher entry = Pattern.compile("([A-Z_]+):\\s*(\\d+)")
            .matcher(table.group(1));
        while (entry.find()) codes.put(entry.group(1), Integer.valueOf(entry.group(2)));
        Map<String, Integer> java = new LinkedHashMap<>();
        java.put("OK", HostFailure.EXIT_OK);
        java.put("BAD_ARGS", HostFailure.EXIT_BAD_ARGS);
        java.put("FORBIDDEN_SWITCH", HostFailure.EXIT_FORBIDDEN_SWITCH);
        java.put("NO_TOKEN", HostFailure.EXIT_NO_TOKEN);
        java.put("CONNECT_FAILED", HostFailure.EXIT_CONNECT_FAILED);
        java.put("HANDSHAKE", HostFailure.EXIT_HANDSHAKE);
        java.put("PROTOCOL", HostFailure.EXIT_PROTOCOL);
        java.put("INTERNAL", HostFailure.EXIT_INTERNAL);
        assertEquals(java, codes);
    }

    @Test
    void aForbiddenSwitchExitDisablesWithoutRelaunch() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "exit:65");
        BrowserHostImpl h = host(
            f,
            f.config()
                .build());
        try {
            h.start();
            await("DISABLED", 60_000, () -> h.status().kind == HostStatus.Kind.DISABLED);
            assertEquals(ReasonCode.HOST_FORBIDDEN_SWITCH, h.status().reason);
            assertEquals(
                HostFailure.FORBIDDEN_SWITCH,
                h.supervisor()
                    .lastFailure());
            String detail = h.supervisor()
                .lastFailureDetail();
            assertTrue(detail.contains("forbidden switch") && detail.contains("exit code 65"), detail);
            Thread.sleep(300);
            assertEquals(
                1,
                h.supervisor()
                    .launches(),
                "relaunching the same command line cannot help");
            assertTrue(
                f.clock.sleeps()
                    .isEmpty());
        } finally {
            h.shutdown();
        }
    }

    @Test
    void aBadArgumentExitDisablesAsALaunchFailure() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "exit:64");
        BrowserHostImpl h = host(
            f,
            f.config()
                .build());
        try {
            h.start();
            await("DISABLED", 60_000, () -> h.status().kind == HostStatus.Kind.DISABLED);
            assertEquals(ReasonCode.HOST_LAUNCH_FAILED, h.status().reason);
            assertEquals(
                HostFailure.BAD_ARGUMENT,
                h.supervisor()
                    .lastFailure());
            assertEquals(
                1,
                h.supervisor()
                    .launches());
        } finally {
            h.shutdown();
        }
    }

    @Test
    void aConnectFailureExitIsRelaunchedWithBackoff() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "exit:67");
        BrowserHostImpl h = host(
            f,
            f.config()
                .build());
        FailureLog failures = new FailureLog(h.supervisor());
        h.addListener(failures);
        try {
            h.start();
            await(
                "DOWN(HOST_CONNECT_FAILED) and a relaunch",
                60_000,
                () -> f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_CONNECT_FAILED) && h.supervisor()
                    .launches() >= 2);
            assertEquals(HostFailure.CONNECT_FAILED, failures.seen.get(0));
            assertEquals(
                1000L,
                f.clock.sleeps()
                    .get(0)
                    .longValue());
            assertFalse(f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_EXITED), f.statuses.seen.toString());
        } finally {
            h.shutdown();
        }
    }

    @Test
    void noTokenAndOtherExitCodesBeforeConnecting() throws Exception {
        assertEquals(HostFailure.NO_TOKEN, firstFailure(fixture("66", "exit:66"), ReasonCode.HOST_HANDSHAKE));
        assertEquals(
            HostFailure.EXITED_BEFORE_CONNECT,
            firstFailure(fixture("70", "exit:70"), ReasonCode.HOST_EXITED),
            "70 says no more than that the host ended");
    }

    /** The connection drops before HELLO: the supervisor waits briefly for the exit code instead of guessing. */
    @Test
    void aConnectionDroppedBeforeHelloIsJudgedByTheExitCode() throws Exception {
        // 66 cannot happen after connecting in the real host; it only shows that the code is waited for and used.
        assertEquals(
            HostFailure.NO_TOKEN,
            firstFailure(fixture("66", "close-before-hello:66"), ReasonCode.HOST_HANDSHAKE));
        assertEquals(
            HostFailure.BAD_HELLO,
            firstFailure(fixture("70", "close-before-hello:70"), ReasonCode.HOST_HANDSHAKE));
    }

    /** 68 after our WELCOME: the host refused it; 69: it read bytes that are not protocol v1. */
    @Test
    void exitsAfterWelcomeAreProtocolErrors() throws Exception {
        assertEquals(
            HostFailure.WELCOME_REFUSED,
            firstFailure(fixture("68", "exit-after-welcome:68"), ReasonCode.HOST_PROTOCOL_ERROR));
        assertEquals(
            HostFailure.BYTES_REFUSED,
            firstFailure(fixture("69", "exit-after-welcome:69"), ReasonCode.HOST_PROTOCOL_ERROR));
        assertEquals(
            HostFailure.CONNECTION_LOST,
            firstFailure(fixture("70", "exit-after-welcome:70"), ReasonCode.HOST_EXITED));
    }

    /** 68 while no WELCOME was written: the host gave up waiting for it, a handshake failure. */
    @Test
    void exit68BeforeOurWelcomeIsAHandshakeFailure() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "exit-after-hello:68");
        AtomicReference<HostSupervisor> supervisor = new AtomicReference<>();
        List<HostFailure> failures = new CopyOnWriteArrayList<>();
        HostSupervisor.Callbacks noWelcome = new HostSupervisor.Callbacks() {

            @Override
            public void onConnected(HostConnection connection, HelloInfo hello) {}

            @Override
            public void onDisconnected(HostConnection connection) {}

            @Override
            public void onStatus(HostStatus status) {
                f.statuses.onStatus(status);
                if (status.kind == HostStatus.Kind.DOWN) failures.add(
                    supervisor.get()
                        .lastFailure());
            }
        };
        HostSupervisor s = new HostSupervisor(
            f.config()
                .exitCodeWaitMillis(5000)
                .build(),
            f.clock,
            ProcessControl.forThisPlatform(),
            new NullInbound(),
            noWelcome);
        supervisor.set(s);
        try {
            s.start();
            await("the first DOWN", 60_000, () -> !failures.isEmpty());
            assertEquals(HostFailure.WELCOME_TIMEOUT, failures.get(0), f.statuses.seen.toString());
            assertTrue(f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_HANDSHAKE), f.statuses.seen.toString());
        } finally {
            s.shutdown();
        }
    }

    private FakeHostFixture fixture(String name, String mode) throws java.io.IOException {
        File sub = new File(dir, name);
        assertTrue(sub.mkdirs(), sub.toString());
        return new FakeHostFixture(sub, mode);
    }

    /**
     * Runs {@code f}'s mode until the first DOWN or DISABLED status and returns the failure the supervisor recorded
     * for it, after checking that status carried {@code reason}. The exit-code wait is long here so a loaded test
     * machine cannot turn the fake's delayed exit into a different answer; the game's default stays 500 ms.
     */
    private static HostFailure firstFailure(FakeHostFixture f, ReasonCode reason) throws Exception {
        BrowserHostImpl h = new BrowserHostImpl(
            f.config()
                .exitCodeWaitMillis(5000)
                .build(),
            f.clock,
            ProcessControl.forThisPlatform());
        FailureLog failures = new FailureLog(h.supervisor());
        h.addListener(f.statuses);
        h.addListener(failures);
        try {
            h.start();
            await("the first failure", 60_000, () -> !failures.seen.isEmpty());
            assertEquals(
                reason,
                failures.statuses.get(0).reason,
                failures.seen + " "
                    + h.supervisor()
                        .lastFailureDetail());
            return failures.seen.get(0);
        } finally {
            h.shutdown();
        }
    }

    /** The supervisor's lastFailure at each DOWN or DISABLED status (it is set before the status is published). */
    static final class FailureLog implements HostListener {

        final HostSupervisor supervisor;
        final List<HostFailure> seen = new CopyOnWriteArrayList<>();
        final List<HostStatus> statuses = new CopyOnWriteArrayList<>();

        FailureLog(HostSupervisor supervisor) {
            this.supervisor = supervisor;
        }

        @Override
        public void onStatus(HostStatus status) {
            if (status.kind != HostStatus.Kind.DOWN && status.kind != HostStatus.Kind.DISABLED) return;
            statuses.add(status);
            seen.add(supervisor.lastFailure());
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
