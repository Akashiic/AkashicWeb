package com.akashiic.web.client.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Kills hosts left behind by an earlier game session (for example after the game was killed from Task Manager before
 * the host's 2 s parent poll noticed). Runs once, before the first launch. Three conditions, all required, before a
 * PID is touched: it is in our PID file, it still runs the recorded executable (a reused PID belongs to someone else),
 * and the game that started it is gone (a second game instance sharing the folder keeps its host). An entry whose game
 * is unknown (its PID could not be read when it was recorded) is presumed to belong to a live game unless it is older
 * than {@link #UNKNOWN_GAME_MAX_AGE_MILLIS}. The whole sweep is bounded by {@link #BUDGET_MILLIS}, so a hanging
 * tasklist never holds up the first launch for long; entries not reached are kept for the next sweep.
 */
final class StaleHostSweeper {

    /** Linux truncates /proc/PID/comm to 15 characters. */
    private static final int LINUX_COMM_LENGTH = 15;
    /** An entry without a known game is killed only after this long (a game session is never this long). */
    static final long UNKNOWN_GAME_MAX_AGE_MILLIS = TimeUnit.HOURS.toMillis(24);
    /** Upper bound for one sweep. Each tool call is also bounded by {@link ToolRunner#TIMEOUT_MILLIS}. */
    static final long BUDGET_MILLIS = 10_000;

    private final HostPidFile pidFile;
    private final ProcessControl processes;
    private final long ourPid;
    private final long budgetMillis;
    private final LongSupplier wallClock;

    StaleHostSweeper(HostPidFile pidFile, ProcessControl processes, long ourPid) {
        this(pidFile, processes, ourPid, BUDGET_MILLIS, System::currentTimeMillis);
    }

    StaleHostSweeper(HostPidFile pidFile, ProcessControl processes, long ourPid, long budgetMillis,
        LongSupplier wallClock) {
        this.pidFile = pidFile;
        this.processes = processes;
        this.ourPid = ourPid;
        this.budgetMillis = budgetMillis;
        this.wallClock = wallClock;
    }

    /** Returns how many stale hosts were killed; entries still owned by a live game, or not killable, are kept. */
    int sweep() {
        List<HostPidFile.Entry> entries = pidFile.read();
        if (entries.isEmpty()) return 0;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        long now = wallClock.getAsLong();
        List<HostPidFile.Entry> keep = new ArrayList<>();
        int killed = 0;
        for (HostPidFile.Entry e : entries) {
            if (System.nanoTime() - deadline > 0) {
                keep.add(e); // out of time: the next session's sweep looks at it
                continue;
            }
            String image = processes.imageName(e.pid);
            if (image == null) continue; // already gone
            if (!sameImage(image, e.image)) continue; // PID reused by another program: never touch it, forget it
            if (e.parentPid <= 0) {
                // Unknown game: a second instance sharing this folder may still own it.
                if (now - e.startedMillis < UNKNOWN_GAME_MAX_AGE_MILLIS) {
                    keep.add(e);
                    continue;
                }
            } else if (e.parentPid != ourPid && processes.imageName(e.parentPid) != null) {
                keep.add(e); // its game still runs
                continue;
            }
            if (processes.kill(e.pid)) killed++;
            else keep.add(e);
        }
        pidFile.write(keep);
        return killed;
    }

    /** Image names match ignoring case and a ".exe" suffix; a 15-character Linux comm matches as a prefix. */
    static boolean sameImage(String actual, String recorded) {
        if (actual == null || recorded == null || actual.isEmpty() || recorded.isEmpty()) return false;
        String a = strip(ProcessControl.fileName(actual));
        String r = strip(ProcessControl.fileName(recorded));
        if (a.equals(r)) return true;
        return a.length() == LINUX_COMM_LENGTH && r.startsWith(a);
    }

    private static String strip(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".exe") ? n.substring(0, n.length() - 4) : n;
    }
}
