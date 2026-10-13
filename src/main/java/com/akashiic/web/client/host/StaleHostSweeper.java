package com.akashiic.web.client.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kills hosts left behind by an earlier game session (for example after the game was killed from Task Manager before
 * the host's 2 s parent poll noticed). Runs once, before the first launch. Three conditions, all required, before a
 * PID is touched: it is in our PID file, it still runs the recorded executable (a reused PID belongs to someone else),
 * and the game that started it is gone (a second game instance sharing the folder keeps its host).
 */
final class StaleHostSweeper {

    /** Linux truncates /proc/PID/comm to 15 characters. */
    private static final int LINUX_COMM_LENGTH = 15;

    private final HostPidFile pidFile;
    private final ProcessControl processes;
    private final long ourPid;

    StaleHostSweeper(HostPidFile pidFile, ProcessControl processes, long ourPid) {
        this.pidFile = pidFile;
        this.processes = processes;
        this.ourPid = ourPid;
    }

    /** Returns how many stale hosts were killed; entries still owned by a live game, or not killable, are kept. */
    int sweep() {
        List<HostPidFile.Entry> entries = pidFile.read();
        if (entries.isEmpty()) return 0;
        List<HostPidFile.Entry> keep = new ArrayList<>();
        int killed = 0;
        for (HostPidFile.Entry e : entries) {
            String image = processes.imageName(e.pid);
            if (image == null) continue; // already gone
            if (!sameImage(image, e.image)) continue; // PID reused by another program: never touch it, forget it
            if (e.parentPid > 0 && e.parentPid != ourPid && processes.imageName(e.parentPid) != null) {
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
