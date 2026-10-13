package com.akashiic.web.client.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.SitePolicy;

/**
 * End-to-end against the REAL host: the host app in {@code host/} of this repository, run by an official Electron
 * build through {@link BrowserHostImpl}, {@link HostSupervisor} and a real {@link HostLaunchConfig}. Runs only when
 * {@code AKW_ELECTRON} names an Electron executable; skipped otherwise.
 *
 * <p>
 * Linux: the host needs a display. Run the test under {@code xvfb-run}; when DISPLAY is unset the test starts a
 * private Xvfb itself. {@code --no-sandbox} is added only when running as root on Linux (Chromium refuses its sandbox
 * as root; the shipped host refuses the switch on Windows). The page is a local {@code file:} page, allowed only by
 * the host's own test-only switch {@code --akw-test-allow-file} (honoured by an unpackaged {@code electron}) and by
 * BrowserHostImpl's package-private test seam.
 *
 * <p>
 * Checks: READY; FRAMEs into the ring with the page's pixels; an INPUT the page reacts to (pixels and STATE); a
 * blocked internal URL, refused both by the client (local EVENT, URL_INTERNAL) and by the host (page-initiated
 * navigation, host EVENT); killing the host gives DOWN, a relaunch, READY and the screen re-created with its page;
 * shutdown leaves no process of either host running: every process of both hosts' trees (recorded from /proc, by pid
 * and start time, before the kill and before shutdown) must be gone. Prints the measured times as {@code AKW-E2E}
 * lines. Tolerant of additive protocol changes (unknown HELLO, PONG or EVENT fields are ignored). The process checks
 * read /proc, so they are Linux-only.
 */
@Timeout(300)
class RealHostEndToEndTest {

    private static final boolean LINUX = System.getProperty("os.name", "")
        .toLowerCase(Locale.ROOT)
        .startsWith("linux");
    private static final boolean WINDOWS = System.getProperty("os.name", "")
        .toLowerCase(Locale.ROOT)
        .startsWith("windows");

    private static final int W = 320;
    private static final int H = 200;

    @TempDir
    File dir;

    /** One status as it arrived. */
    static final class Seen {

        final HostStatus status;
        final long nanos;

        Seen(HostStatus status, long nanos) {
            this.status = status;
            this.nanos = nanos;
        }
    }

    /** Everything the host tells the game, with arrival times. */
    static final class Recorder implements HostListener {

        final List<Seen> statuses = new CopyOnWriteArrayList<>();
        final List<String> states = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onStatus(HostStatus s) {
            statuses.add(new Seen(s, System.nanoTime()));
        }

        @Override
        public void onState(int screenId, String json) {
            states.add(json);
        }

        @Override
        public void onEvent(int screenId, String json) {
            events.add(json);
        }

        /** The first status of this kind at or after index {@code from}, or null. */
        Seen firstAfter(int from, HostStatus.Kind kind) {
            List<Seen> all = new ArrayList<>(statuses);
            for (int i = from; i < all.size(); i++) if (all.get(i).status.kind == kind) return all.get(i);
            return null;
        }
    }

    @Test
    void realHostRendersReactsRefusesInternalUrlsSurvivesAKillAndShutsDownClean() throws Exception {
        String electron = System.getenv("AKW_ELECTRON");
        assumeTrue(electron != null && !electron.isEmpty(), "AKW_ELECTRON is not set: real-host test skipped");
        File electronFile = new File(electron);
        assertTrue(electronFile.canExecute(), "AKW_ELECTRON is not an executable file: " + electron);
        File hostDir = findHostDir();
        assertNotNull(hostDir, "host/ (the host app) not found above " + System.getProperty("user.dir"));

        String marker = "AKW_E2E_MARKER";
        String markerValue = randomHex();
        Map<String, String> env = new HashMap<>(System.getenv());
        // Finds this test's host main processes by environment even after they lost their parent. Chromium's children
        // overwrite their environment area with their process title, so they are tracked through the process tree.
        env.put(marker, markerValue);
        Process xvfb = null;
        if (LINUX && (System.getenv("DISPLAY") == null || System.getenv("DISPLAY")
            .isEmpty())) {
            xvfb = new ProcessBuilder("Xvfb", "-displayfd", "1", "-screen", "0", "1280x1024x24", "-nolisten", "tcp")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
            BufferedReader r = new BufferedReader(
                new InputStreamReader(xvfb.getInputStream(), StandardCharsets.US_ASCII));
            String display = r.readLine();
            assertNotNull(display, "Xvfb did not report a display; run the test under xvfb-run");
            env.put("DISPLAY", ":" + display.trim());
            log("started Xvfb on :" + display.trim() + " (DISPLAY was unset)");
        }

        List<String> command = new ArrayList<>();
        command.add(electronFile.getAbsolutePath());
        command.add(hostDir.getAbsolutePath()); // Electron's app path: the host app as it is in this repository
        command.add("--akw-test-allow-file");
        if (LINUX && runningAsRoot()) command.add("--no-sandbox");
        File page = writePage();
        String pageUrl = "file://" + page.getAbsolutePath();

        HostLaunchConfig config = HostLaunchConfig
            .builder(
                HostExecutable.fixed(command, new File(dir, "profile")),
                new File(dir, "logs"),
                new File(dir, "state"))
            .connectTimeoutMillis(60_000)
            .pingIntervalMillis(500) // exercise the real host's PONG within the test; stall only after 10 intervals
            .stallIntervals(10)
            .parentEnvironment(env)
            .build();
        BrowserHostImpl h = new BrowserHostImpl(config, HostClock.SYSTEM, ProcessControl.forThisPlatform(), true);
        Recorder rec = new Recorder();
        h.addListener(rec);
        h.policy(SitePolicy.blocklist(Collections.<String>emptyList())); // the server's default: public sites allowed
        // Every host process seen (pid -> start time): the main processes, their whole trees, marked processes.
        Map<Long, String> seen = new LinkedHashMap<>();
        try {
            log("electron " + electronFile + ", host app " + hostDir + ", command " + command);
            long t0 = System.nanoTime();
            h.start();
            await("READY", 90_000, () -> h.status().kind == HostStatus.Kind.READY, h, config);
            long ready = System.nanoTime();
            log("launch -> READY " + ms(t0, ready) + " ms; " + line(h, "host: "));

            // 1. A screen with a local page: FRAMEs reach the ring with the page's pixels.
            int id = h
                .createScreen(new CreateSpec(W, H, 30, pageUrl, CreateSpec.Session.SHARED, CreateSpec.Audio.OS, 0f));
            FrameRing3 ring = (FrameRing3) h.frames(id);
            long created = System.nanoTime();
            int[] first = awaitFrame(ring, "a red page", px -> near(px, 0, 0, 255), 30_000, h, config);
            long red = System.nanoTime();
            assertEquals(W, first[4], "FRAME width");
            assertEquals(H, first[5], "FRAME height");
            log("CREATE -> first red frame " + ms(created, red) + " ms, page " + first[4] + "x" + first[5]);
            awaitTrue(
                "STATE with the page title",
                15_000,
                () -> any(rec.states, s -> s.contains("\"title\":\"akw-e2e\"")));
            long pid = h.supervisor()
                .hostPid();
            assertTrue(pid > 0, "host pid");
            Map<Long, String> tree1 = snapshot(pid, markerValue, seen);
            log("host 1 process tree (" + tree1.size() + "): " + describe(tree1.keySet()));
            await(
                "a PING answered by the real host",
                15_000,
                () -> line(h, "ping round trip: ").matches("ping round trip: \\d+ ms.*"),
                h,
                config);
            log(line(h, "ping round trip: "));

            // 2. INPUT: a click on the left half turns the page green and sets its title.
            long clicked = System.nanoTime();
            click(h, id, 40, 50);
            awaitFrame(ring, "the page turned green", px -> near(px, 0, 255, 0), 15_000, h, config);
            long green = System.nanoTime();
            awaitTrue("STATE after the click", 15_000, () -> any(rec.states, s -> s.contains("clicked 40,50")));
            log("INPUT -> green frame " + ms(clicked, green) + " ms");

            // 3a. A blocked internal URL from the game: refused before it leaves the JVM.
            int before = rec.events.size();
            h.navigate(id, "http://127.0.0.1/akw-e2e"); // default port: a low port alone would be URL_INVALID
            assertEquals(before + 1, rec.events.size(), "local refusal is synchronous");
            String local = rec.events.get(before);
            assertTrue(local.contains("\"code\":\"URL_INTERNAL\"") && local.contains("\"via\":\"client\""), local);
            // 3b. The page itself tries an internal URL (a click on the right half): the real host refuses it.
            long nav = System.nanoTime();
            click(h, id, 280, 100);
            awaitTrue(
                "the host's blocked-url EVENT",
                15_000,
                () -> any(
                    rec.events,
                    e -> e.contains("\"kind\":\"blocked-url\"") && !e.contains("\"via\":\"client\"")
                        && e.contains("\"reason\":\"internal\"")));
            String hostEvent = null;
            for (String e : rec.events)
                if (!e.contains("\"via\":\"client\"") && e.contains("blocked-url")) hostEvent = e;
            log("page-initiated internal navigation -> host EVENT " + ms(nav, System.nanoTime()) + " ms: " + hostEvent);

            // 4. Kill the host: DOWN, relaunch, READY, and the screen re-created with its page. The tree is recorded
            // right before the kill, so children orphaned by it are still checked at the end.
            snapshot(pid, markerValue, seen);
            int mark = rec.statuses.size();
            long killedAt = System.nanoTime();
            killHard(pid);
            await("DOWN after the kill", 30_000, () -> rec.firstAfter(mark, HostStatus.Kind.DOWN) != null, h, config);
            HostStatus down = rec.firstAfter(mark, HostStatus.Kind.DOWN).status;
            assertEquals(ReasonCode.HOST_EXITED, down.reason, "a killed host is reported as exited");
            await(
                "READY after the relaunch",
                90_000,
                () -> h.supervisor()
                    .launches() == 2 && h.status().kind == HostStatus.Kind.READY
                    && rec.firstAfter(mark, HostStatus.Kind.READY) != null,
                h,
                config);
            long downAt = rec.firstAfter(mark, HostStatus.Kind.DOWN).nanos;
            long readyAgain = rec.firstAfter(mark, HostStatus.Kind.READY).nanos;
            awaitFrame(ring, "the re-created page (red again)", px -> near(px, 0, 0, 255), 30_000, h, config);
            long redAgain = System.nanoTime();
            assertTrue(
                h.supervisor()
                    .hostPid() != pid,
                "a new host process");
            log(
                "kill -> DOWN " + ms(killedAt, downAt)
                    + " ms, DOWN -> READY "
                    + ms(downAt, readyAgain)
                    + " ms (1 s backoff included), kill -> re-created page "
                    + ms(killedAt, redAgain)
                    + " ms; reason "
                    + down.reason
                    + "; "
                    + line(h, "last failure: "));
            long pid2 = h.supervisor()
                .hostPid();
            Map<Long, String> tree2 = snapshot(pid2, markerValue, seen);
            log("host 2 process tree (" + tree2.size() + "): " + describe(tree2.keySet()));
            assertEquals(
                2,
                h.supervisor()
                    .launches(),
                "no stall or other relaunch besides the kill");

            // 5. Shutdown: QUIT, BYE, exit, and nothing of ours left running: not the main processes, not one child
            // of either host (the first one was killed with SIGKILL), not a marked process.
            snapshot(pid2, markerValue, seen);
            long stop = System.nanoTime();
            h.shutdown();
            long stopped = System.nanoTime();
            assertEquals(HostStatus.Kind.DOWN, h.status().kind);
            long gone = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!stillRunning(seen).isEmpty() && System.nanoTime() < gone) Thread.sleep(50);
            List<Long> left = stillRunning(seen);
            log(
                "shutdown " + ms(stop, stopped)
                    + " ms; host processes seen during the test: "
                    + seen.size()
                    + ", still running after shutdown (waited up to 15 s): "
                    + left.size()
                    + (left.isEmpty() ? "" : " " + describe(left))
                    + "; zombies among them: "
                    + zombies(seen));
            if (new File("/proc/self/stat").isFile()) {
                assertTrue(left.isEmpty(), "host processes left running: " + describe(left));
                assertTrue(seen.size() > 2, "the scan saw the hosts' child processes, not only the main ones");
            } else {
                log("process-tree checks skipped: no /proc on this system");
            }
        } catch (AssertionError | RuntimeException e) {
            dumpLogs(config);
            throw e;
        } finally {
            h.shutdown();
            // Never leave anything behind, even on failure.
            for (long p : stillRunning(seen)) killHard(p);
            for (long p : markedProcesses(markerValue)) killHard(p);
            if (xvfb != null) xvfb.destroy();
        }
    }

    // --- process bookkeeping (Linux /proc) -------------------------------------------------------------------------

    /** Adds the host's current tree and every marked process to {@code seen}; returns the tree. */
    private static Map<Long, String> snapshot(long hostPid, String markerValue, Map<Long, String> seen) {
        Map<Long, String> tree = tree(hostPid);
        seen.putAll(tree);
        for (long p : markedProcesses(markerValue)) {
            String[] f = stat(p);
            if (f != null) seen.put(p, f[19]);
        }
        return tree;
    }

    /** /proc/PID/stat fields from field 3 on ([0] state, [1] ppid, [19] start time), or null when it is gone. */
    static String[] stat(long pid) {
        try {
            String s = new String(
                Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()),
                StandardCharsets.UTF_8);
            int close = s.lastIndexOf(')');
            return close < 0 ? null
                : s.substring(close + 2)
                    .trim()
                    .split(" ");
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Process {@code root} and all its descendants now: pid -> start time (from the /proc parent links). */
    static Map<Long, String> tree(long root) {
        Map<Long, List<Long>> children = new HashMap<>();
        Map<Long, String> start = new HashMap<>();
        File[] procs = new File("/proc").listFiles();
        if (procs != null) {
            for (File p : procs) {
                if (!p.getName()
                    .matches("\\d+")) continue;
                long pid = Long.parseLong(p.getName());
                String[] f = stat(pid);
                if (f == null || f.length < 20) continue;
                start.put(pid, f[19]);
                children.computeIfAbsent(Long.parseLong(f[1]), k -> new ArrayList<>())
                    .add(pid);
            }
        }
        Map<Long, String> out = new LinkedHashMap<>();
        ArrayDeque<Long> todo = new ArrayDeque<>();
        if (start.containsKey(root)) todo.add(root);
        while (!todo.isEmpty()) {
            long p = todo.removeFirst();
            if (out.put(p, start.get(p)) != null) continue;
            List<Long> c = children.get(p);
            if (c != null) todo.addAll(c);
        }
        return out;
    }

    /** Recorded processes still running: same pid and start time, and not a zombie. */
    static List<Long> stillRunning(Map<Long, String> seen) {
        List<Long> out = new ArrayList<>();
        for (Map.Entry<Long, String> e : seen.entrySet()) {
            String[] f = stat(e.getKey());
            if (f != null && f.length >= 20 && f[19].equals(e.getValue()) && !f[0].equals("Z")) out.add(e.getKey());
        }
        return out;
    }

    /**
     * Recorded processes that exited but were not reaped yet, with their parent: "pid(parent N)". Not running; a
     * container whose PID 1 does not reap orphans keeps them.
     */
    private static List<String> zombies(Map<Long, String> seen) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Long, String> e : seen.entrySet()) {
            String[] f = stat(e.getKey());
            if (f != null && f.length >= 20 && f[19].equals(e.getValue()) && f[0].equals("Z"))
                out.add(e.getKey() + "(parent " + f[1] + ")");
        }
        return out;
    }

    /** "pid:kind" for each process; kind is Chromium's --type (renderer, zygote, gpu-process, utility) or "main". */
    static String describe(Iterable<Long> pids) {
        StringBuilder sb = new StringBuilder("[");
        for (long p : pids) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(p)
                .append(':')
                .append(kind(p));
        }
        return sb.append(']')
            .toString();
    }

    private static String kind(long pid) {
        try {
            String cmd = new String(
                Files.readAllBytes(new File("/proc/" + pid + "/cmdline").toPath()),
                StandardCharsets.UTF_8);
            // Chromium rewrites its children's titles into one space-separated string: search the whole text.
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("--type=([a-z-]+)")
                .matcher(cmd);
            if (m.find()) return m.group(1);
            return cmd.isEmpty() ? "?" : "main";
        } catch (IOException | RuntimeException e) {
            return "gone";
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    /** Waits for a frame whose center pixel matches; returns {b, g, r, a, width, height} of that frame. */
    private static int[] awaitFrame(FrameRing3 ring, String what, Predicate<int[]> center, long millis,
        BrowserHostImpl h, HostLaunchConfig config) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        int[] last = null;
        while (System.nanoTime() < deadline) {
            FrameSlot s = ring.takeLatest();
            if (s != null) {
                try {
                    last = pixel(s, s.width / 2, s.height / 2);
                    if (center.test(last)) {
                        // The whole page, not only its center: the four corners too.
                        for (int[] xy : new int[][] { { 0, 0 }, { s.width - 1, 0 }, { 0, s.height - 1 },
                            { s.width - 1, s.height - 1 } }) {
                            int[] c = pixel(s, xy[0], xy[1]);
                            assertTrue(center.test(c), what + ": corner " + xy[0] + "," + xy[1] + " " + str(c));
                        }
                        return last;
                    }
                } finally {
                    ring.release(s);
                }
            }
            Thread.sleep(20);
        }
        dumpLogs(config);
        fail(
            "timed out waiting for " + what
                + "; last center pixel "
                + (last == null ? "none" : str(last))
                + "; "
                + h.diagnostics());
        return null;
    }

    /** BGRA bytes of pixel (x, y) plus the slot size. */
    private static int[] pixel(FrameSlot s, int x, int y) {
        int i = (y * s.width + x) * 4;
        return new int[] { s.bgra.get(i) & 0xFF, s.bgra.get(i + 1) & 0xFF, s.bgra.get(i + 2) & 0xFF,
            s.bgra.get(i + 3) & 0xFF, s.width, s.height };
    }

    private static boolean near(int[] px, int b, int g, int r) {
        return Math.abs(px[0] - b) <= 8 && Math.abs(px[1] - g) <= 8 && Math.abs(px[2] - r) <= 8 && px[3] >= 247;
    }

    private static String str(int[] px) {
        return "BGRA(" + px[0] + "," + px[1] + "," + px[2] + "," + px[3] + ")";
    }

    private static void click(BrowserHostImpl h, int id, int x, int y) {
        h.input(id, new InputEvent(InputEvent.Type.MOUSE_MOVE, x, y, null, 0, 0, 0, null, 0, null));
        h.input(id, new InputEvent(InputEvent.Type.MOUSE_DOWN, x, y, "left", 1, 0, 0, null, 0, null));
        h.input(id, new InputEvent(InputEvent.Type.MOUSE_UP, x, y, "left", 1, 0, 0, null, 0, null));
    }

    private File writePage() throws IOException {
        String html = "<!doctype html>\n<html><head><meta charset=\"utf-8\"><title>akw-e2e</title>\n"
            + "<style>html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#ff0000}</style>\n"
            + "</head><body><script>\n"
            + "addEventListener('mousedown', function (e) {\n"
            + "  if (e.clientX < innerWidth / 2) {\n"
            + "    document.body.style.background = '#00ff00';\n"
            + "    document.title = 'clicked ' + e.clientX + ',' + e.clientY;\n"
            + "  } else {\n"
            + "    location.href = 'http://127.0.0.1/akw-e2e-internal';\n"
            + "  }\n"
            + "});\n"
            + "</script></body></html>\n";
        File page = new File(dir, "page.html");
        Files.write(page.toPath(), html.getBytes(StandardCharsets.UTF_8));
        return page;
    }

    /** The repository's host/ directory: walks up from the working directory and from this class's location. */
    private static File findHostDir() {
        List<File> starts = new ArrayList<>();
        starts.add(new File(System.getProperty("user.dir")).getAbsoluteFile());
        try {
            starts.add(
                new File(
                    RealHostEndToEndTest.class.getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI()).getAbsoluteFile());
        } catch (URISyntaxException | RuntimeException e) {
            // The working directory is enough under Gradle.
        }
        for (File start : starts) {
            for (File d = start; d != null; d = d.getParentFile()) {
                File host = new File(d, "host");
                if (new File(host, "package.json").isFile() && new File(host, "src/main.js").isFile()) return host;
            }
        }
        return null;
    }

    /** Linux: effective uid 0, read from /proc/self/status (no Java 8 API for it). */
    private static boolean runningAsRoot() {
        try {
            for (String l : Files.readAllLines(new File("/proc/self/status").toPath(), StandardCharsets.US_ASCII)) {
                if (l.startsWith("Uid:")) {
                    String[] f = l.substring(4)
                        .trim()
                        .split("\\s+");
                    return f.length > 1 && f[1].equals("0");
                }
            }
        } catch (IOException e) {
            // Not Linux, or no /proc: assume not root.
        }
        return false;
    }

    /** Linux: PIDs whose initial environment holds {@code AKW_E2E_MARKER=<value>}, i.e. this test's hosts. */
    static List<Long> markedProcesses(String value) {
        List<Long> out = new ArrayList<>();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return out;
        byte[] needle = ("AKW_E2E_MARKER=" + value).getBytes(StandardCharsets.US_ASCII);
        for (File p : procs) {
            if (!p.getName()
                .matches("\\d+")) continue;
            try {
                if (indexOf(Files.readAllBytes(new File(p, "environ").toPath()), needle) >= 0)
                    out.add(Long.parseLong(p.getName()));
            } catch (IOException | RuntimeException e) {
                // Exited meanwhile, or a zombie (no environment left): not running.
            }
        }
        return out;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer: for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** SIGKILL (TerminateProcess on Windows) of exactly one process, like a crash: children are left to notice. */
    private static void killHard(long pid) throws IOException, InterruptedException {
        List<String> cmd = WINDOWS ? java.util.Arrays.asList("taskkill", "/PID", Long.toString(pid), "/F")
            : java.util.Arrays.asList("kill", "-9", Long.toString(pid));
        Process k = new ProcessBuilder(cmd).redirectErrorStream(true)
            .start();
        k.getInputStream()
            .close();
        k.waitFor(10, TimeUnit.SECONDS);
    }

    private static String randomHex() {
        byte[] b = new byte[8];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    private static boolean any(List<String> list, Predicate<String> p) {
        for (String s : list) if (p.test(s)) return true;
        return false;
    }

    private static String line(BrowserHostImpl h, String prefix) {
        for (String d : h.diagnostics()) if (d.startsWith(prefix)) return d;
        return prefix + "?";
    }

    private static long ms(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000L;
    }

    private static void log(String s) {
        System.out.println("AKW-E2E " + s);
    }

    private static void await(String what, long millis, BooleanSupplier condition, BrowserHostImpl h,
        HostLaunchConfig config) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                dumpLogs(config);
                fail("timed out waiting for " + what + "; status " + h.status() + "; " + h.diagnostics());
            }
            Thread.sleep(20);
        }
    }

    private static void awaitTrue(String what, long millis, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for " + what);
            Thread.sleep(20);
        }
    }

    /** Tails of the host's own log and of its stdout/stderr, for diagnosing a failure. */
    private static void dumpLogs(HostLaunchConfig config) {
        for (File f : new File[] { config.hostLogFile(), config.stdioLogFile() }) {
            try {
                if (!f.isFile()) continue;
                List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                System.out.println("AKW-E2E --- tail of " + f.getName());
                for (String l : lines.subList(Math.max(0, lines.size() - 40), lines.size()))
                    System.out.println("AKW-E2E | " + l);
            } catch (IOException | RuntimeException e) {
                System.out.println("AKW-E2E cannot read " + f + ": " + e);
            }
        }
    }
}
