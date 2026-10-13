package com.akashiic.web.client.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The PID file and the start-up sweep (ADR-001): only recorded PIDs, only the recorded image, only orphans. */
class StaleHostSweeperTest {

    @TempDir
    File dir;

    /** In-memory processes: pid -> image. */
    static class FakeProcesses implements ProcessControl {

        final Map<Long, String> running = new HashMap<>();
        final List<Long> killed = new ArrayList<>();
        boolean killFails;

        @Override
        public String imageName(long pid) {
            return running.get(pid);
        }

        @Override
        public boolean kill(long pid) {
            if (killFails) return false;
            killed.add(pid);
            running.remove(pid);
            return true;
        }

        @Override
        public long pidOf(Process process) {
            return -1;
        }
    }

    @Test
    void pidFileRoundTripsAndSkipsGarbage() throws Exception {
        File f = new File(dir, "state/host-pids.txt");
        HostPidFile pids = new HostPidFile(f);
        assertTrue(
            pids.read()
                .isEmpty());
        pids.add(new HostPidFile.Entry(10, 1, 100, "AkashicWebHost.exe"));
        pids.add(new HostPidFile.Entry(11, 1, 101, "AkashicWebHost.exe"));
        pids.add(new HostPidFile.Entry(10, 2, 102, "AkashicWebHost.exe")); // same pid replaces
        assertEquals(
            2,
            pids.read()
                .size());
        pids.remove(11);
        List<HostPidFile.Entry> left = pids.read();
        assertEquals(1, left.size());
        assertEquals(2, left.get(0).parentPid);
        Files.write(
            f.toPath(),
            Arrays.asList("garbage", "1\t2\t3", "-4\t1\t1\tx", "5\tx\t1\tx", "6\t1\t1\tAkashic Web Host.exe"),
            StandardCharsets.UTF_8);
        List<HostPidFile.Entry> parsed = pids.read();
        assertEquals(1, parsed.size());
        assertEquals("Akashic Web Host.exe", parsed.get(0).image);
        assertFalse(new File(dir, "state/host-pids.txt.tmp").exists());
    }

    @Test
    void killsOnlyRecordedOrphansStillRunningTheRecordedImage() {
        HostPidFile pids = new HostPidFile(new File(dir, "pids.txt"));
        FakeProcesses p = new FakeProcesses();
        long ourPid = 500;
        p.running.put(10L, "AkashicWebHost.exe"); // orphan of a dead game: kill
        p.running.put(11L, "notepad.exe"); // PID reused: never touch, forget
        p.running.put(13L, "AkashicWebHost.exe"); // its game (900) still runs: keep
        p.running.put(900L, "javaw.exe");
        p.running.put(14L, "akashicwebhost.EXE"); // case and suffix differences are the same image
        p.running.put(15L, "AkashicWebHost"); // Linux name of the same executable
        p.running.put(99L, "AkashicWebHost.exe"); // not recorded: never touch
        pids.write(
            Arrays.asList(
                new HostPidFile.Entry(10, 400, 0, "AkashicWebHost.exe"),
                new HostPidFile.Entry(11, 400, 0, "AkashicWebHost.exe"),
                new HostPidFile.Entry(12, 400, 0, "AkashicWebHost.exe"), // already gone
                new HostPidFile.Entry(13, 900, 0, "AkashicWebHost.exe"),
                new HostPidFile.Entry(14, ourPid, 0, "AkashicWebHost.exe"), // our pid reused: still stale
                new HostPidFile.Entry(15, 0, 0, "AkashicWebHost")));
        int killed = new StaleHostSweeper(pids, p, ourPid).sweep();
        assertEquals(3, killed);
        assertEquals(Arrays.asList(10L, 14L, 15L), p.killed);
        assertTrue(p.running.containsKey(11L));
        assertTrue(p.running.containsKey(13L));
        assertTrue(p.running.containsKey(99L));
        List<HostPidFile.Entry> left = pids.read();
        assertEquals(1, left.size());
        assertEquals(13, left.get(0).pid);
    }

    /**
     * Review finding (low): an entry whose game PID was unknown (recorded as 0) used to be killed at once, so a second
     * game instance sharing the folder could lose its live host. It is now kept unless older than a day.
     */
    @Test
    void entriesOfAnUnknownGameAreKeptUnlessOlderThanADay() {
        HostPidFile pids = new HostPidFile(new File(dir, "pids.txt"));
        FakeProcesses p = new FakeProcesses();
        long now = 10L * StaleHostSweeper.UNKNOWN_GAME_MAX_AGE_MILLIS;
        p.running.put(20L, "AkashicWebHost.exe");
        p.running.put(21L, "AkashicWebHost.exe");
        pids.write(
            Arrays.asList(
                new HostPidFile.Entry(20, 0, now - 3_600_000L, "AkashicWebHost.exe"), // an hour old: keep
                new HostPidFile.Entry(
                    21,
                    0,
                    now - StaleHostSweeper.UNKNOWN_GAME_MAX_AGE_MILLIS - 1,
                    "AkashicWebHost.exe")));
        int killed = new StaleHostSweeper(pids, p, 500, StaleHostSweeper.BUDGET_MILLIS, () -> now).sweep();
        assertEquals(1, killed);
        assertEquals(Arrays.asList(21L), p.killed);
        List<HostPidFile.Entry> left = pids.read();
        assertEquals(1, left.size());
        assertEquals(20, left.get(0).pid);
    }

    /**
     * Review finding (low): the sweep runs before the first launch, and with 64 entries and a hanging tasklist (5 s per
     * call) it could hold up the host for minutes. It now stops at its time budget and keeps the rest for next time.
     */
    @Test
    void theSweepStopsAtItsTimeBudget() {
        HostPidFile pids = new HostPidFile(new File(dir, "pids.txt"));
        FakeProcesses slow = new FakeProcesses() {

            @Override
            public String imageName(long pid) {
                try {
                    Thread.sleep(150); // a slow tasklist
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                }
                return super.imageName(pid);
            }
        };
        List<HostPidFile.Entry> entries = new ArrayList<>();
        for (long pid = 100; pid < 110; pid++) {
            slow.running.put(pid, "AkashicWebHost.exe");
            entries.add(new HostPidFile.Entry(pid, 400, 0, "AkashicWebHost.exe")); // all orphans of a dead game
        }
        pids.write(entries);
        long t0 = System.nanoTime();
        int killed = new StaleHostSweeper(pids, slow, 1, 400, System::currentTimeMillis).sweep();
        long tookMillis = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(killed >= 1 && killed < 10, "killed " + killed);
        assertTrue(tookMillis < 2_000, "took " + tookMillis + " ms");
        assertEquals(
            10 - killed,
            pids.read()
                .size(),
            "the entries not reached stay recorded");
    }

    @Test
    void failedKillsStayRecordedForTheNextSweep() {
        HostPidFile pids = new HostPidFile(new File(dir, "pids.txt"));
        FakeProcesses p = new FakeProcesses();
        p.killFails = true;
        p.running.put(10L, "AkashicWebHost.exe");
        pids.write(Arrays.asList(new HostPidFile.Entry(10, 400, 0, "AkashicWebHost.exe")));
        assertEquals(0, new StaleHostSweeper(pids, p, 1).sweep());
        assertEquals(
            1,
            pids.read()
                .size());
    }

    @Test
    void imageNamesCompareLikeTheOperatingSystemsDo() {
        assertTrue(StaleHostSweeper.sameImage("AkashicWebHost.exe", "AkashicWebHost.exe"));
        assertTrue(StaleHostSweeper.sameImage("C:\\x\\AKASHICWEBHOST.EXE", "AkashicWebHost.exe"));
        assertTrue(StaleHostSweeper.sameImage("/opt/x/AkashicWebHost", "AkashicWebHost"));
        assertTrue(StaleHostSweeper.sameImage("AkashicWebHostX", "AkashicWebHostXYZ"), "15-char Linux comm");
        assertFalse(StaleHostSweeper.sameImage("AkashicWeb", "AkashicWebHost"));
        assertFalse(StaleHostSweeper.sameImage("", "AkashicWebHost"));
        assertFalse(StaleHostSweeper.sameImage("java", "javaw"));
        assertFalse(StaleHostSweeper.sameImage(null, "x"));
    }

    /**
     * Review finding (low): tasklist/taskkill (and ps/kill) were started by bare name, so on Windows a planted
     * tasklist.exe in the instance folder would run first. They now come from %SystemRoot%\System32 and /bin or
     * /usr/bin.
     */
    @Test
    void systemToolsAreStartedByFullPath() {
        assertEquals("D:\\Win\\System32\\tasklist.exe", WindowsProcessControl.systemTool("tasklist.exe", "D:\\Win\\"));
        assertEquals("C:\\Windows\\System32\\taskkill.exe", WindowsProcessControl.systemTool("taskkill.exe", null));
        assertEquals("C:\\Windows\\System32\\taskkill.exe", WindowsProcessControl.systemTool("taskkill.exe", "  "));
        assertEquals("no-such-tool-akw", UnixProcessControl.tool("no-such-tool-akw"), "last resort only");
        assumeFalse(
            System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .startsWith("windows"));
        for (String t : new String[] { "kill", "ps" }) {
            String path = UnixProcessControl.tool(t);
            assertTrue(path.equals("/bin/" + t) || path.equals("/usr/bin/" + t), path);
        }
    }

    @Test
    void tasklistOutputIsParsedByShapeNotLanguage() {
        assertEquals(
            "AkashicWebHost.exe",
            WindowsProcessControl
                .parseTasklistImage("\r\n\"AkashicWebHost.exe\",\"1234\",\"Console\",\"1\",\"85.432 K\"\r\n"));
        assertNull(
            WindowsProcessControl
                .parseTasklistImage("INFORMAÇÕES: Nenhuma tarefa em execução corresponde aos critérios.\r\n"));
        assertNull(
            WindowsProcessControl.parseTasklistImage("INFO: No tasks are running which match the specified criteria."));
    }

    /** Real processes (Linux/macOS): a recorded orphan dies, a reused PID and another game's host survive. */
    @Test
    @Timeout(60)
    void sweepKillsARealRecordedOrphanAndSparesTheRest() throws Exception {
        assumeFalse(
            System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .startsWith("windows"));
        ProcessControl pc = ProcessControl.forThisPlatform();
        Process orphan = new ProcessBuilder("sleep", "300").start();
        Process otherImage = new ProcessBuilder("sleep", "300").start();
        Process liveGame = new ProcessBuilder("sleep", "300").start();
        Process liveGamesHost = new ProcessBuilder("sleep", "300").start();
        Process gone = new ProcessBuilder("true").start();
        gone.waitFor(10, TimeUnit.SECONDS);
        long deadGame = pc.pidOf(gone);
        try {
            assertTrue(pc.pidOf(orphan) > 0, "pid discovery on this JVM");
            assertEquals("sleep", pc.imageName(pc.pidOf(orphan)));
            HostPidFile pids = new HostPidFile(new File(dir, "pids.txt"));
            pids.write(
                Arrays.asList(
                    new HostPidFile.Entry(pc.pidOf(orphan), deadGame, 0, "sleep"),
                    new HostPidFile.Entry(pc.pidOf(otherImage), deadGame, 0, "AkashicWebHost"),
                    new HostPidFile.Entry(pc.pidOf(liveGamesHost), pc.pidOf(liveGame), 0, "sleep")));
            assertEquals(1, new StaleHostSweeper(pids, pc, HostCommandLine.currentPid()).sweep());
            assertTrue(orphan.waitFor(10, TimeUnit.SECONDS), "orphan killed");
            assertTrue(otherImage.isAlive(), "a PID running another image is never touched");
            assertTrue(liveGamesHost.isAlive(), "a host whose game still runs is kept");
            assertEquals(
                1,
                pids.read()
                    .size());
        } finally {
            for (Process p : new Process[] { orphan, otherImage, liveGame, liveGamesHost }) p.destroyForcibly();
        }
    }

    /** Both process-control backends that can run here agree on a real child. */
    @Test
    @Timeout(60)
    void unixAndJdkBackendsSeeAndKillARealChild() throws Exception {
        assumeFalse(
            System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .startsWith("windows"));
        List<ProcessControl> backends = new ArrayList<>();
        backends.add(new UnixProcessControl());
        ProcessControl jdk = JdkProcessControl.tryCreate(new UnixProcessControl());
        if (jdk != null) backends.add(jdk); // Java 9+ only
        for (ProcessControl pc : backends) {
            Process child = new ProcessBuilder("sleep", "300").start();
            try {
                long pid = pc.pidOf(child);
                assertTrue(
                    pid > 0,
                    pc.getClass()
                        .getSimpleName());
                assertEquals(
                    "sleep",
                    pc.imageName(pid),
                    pc.getClass()
                        .getSimpleName());
                assertTrue(pc.kill(pid));
                assertTrue(child.waitFor(10, TimeUnit.SECONDS));
                assertNull(
                    pc.imageName(pid),
                    pc.getClass()
                        .getSimpleName());
            } finally {
                child.destroyForcibly();
            }
        }
        assertNull(new UnixProcessControl().imageName(-1));
        assertFalse(new UnixProcessControl().kill(0));
    }
}
