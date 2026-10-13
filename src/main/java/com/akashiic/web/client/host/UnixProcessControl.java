package com.akashiic.web.client.host;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Linux and macOS on Java 8: /proc or {@code ps} for the image, {@code kill -9} for the sweep and for a host that
 * ignored SIGTERM. On Linux the kill also ends the process's descendants (Chromium's zygote, GPU, renderer and
 * utility children), found through the /proc parent links before anything is killed; elsewhere only the process
 * itself. The tools are started from /bin or /usr/bin by full path, not looked up in the game's PATH.
 */
final class UnixProcessControl implements ProcessControl {

    private static final String PS = tool("ps");
    private static final String KILL = tool("kill");
    private static final File PROC = new File("/proc");

    @Override
    public String imageName(long pid) {
        if (pid <= 0) return null;
        File comm = new File("/proc/" + pid + "/comm");
        File proc = new File("/proc/self");
        if (proc.exists()) {
            if (!comm.exists()) return null;
            try {
                return new String(Files.readAllBytes(comm.toPath()), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                return ""; // exists but unreadable (another user's process)
            }
        }
        ToolRunner.Result r = ToolRunner.run(Arrays.asList(PS, "-p", Long.toString(pid), "-o", "comm="));
        if (r == null) return "";
        if (r.exitCode != 0) return null;
        String name = r.output.trim();
        return name.isEmpty() ? null : ProcessControl.fileName(name);
    }

    @Override
    public boolean kill(long pid) {
        if (pid <= 0) return false;
        List<String> cmd = new ArrayList<>(Arrays.asList(KILL, "-9", Long.toString(pid)));
        // Descendants are listed before the kill: once the parent is gone they are re-parented and lost.
        for (long child : descendants(pid, PROC)) cmd.add(Long.toString(child));
        ToolRunner.Result r = ToolRunner.run(cmd);
        return r != null && r.exitCode == 0;
    }

    @Override
    public long pidOf(Process process) {
        return ProcessControl.reflectivePid(process);
    }

    /**
     * Descendants of {@code root} (not {@code root} itself), from the parent pid in each {@code <proc>/<pid>/stat};
     * empty when {@code proc} does not exist (macOS).
     */
    static List<Long> descendants(long root, File proc) {
        File[] entries = proc.listFiles();
        if (entries == null) return new ArrayList<>();
        Map<Long, List<Long>> children = new HashMap<>();
        for (File e : entries) {
            long pid = digits(e.getName());
            if (pid <= 0) continue;
            long parent = parentOf(new File(e, "stat"));
            if (parent > 0) children.computeIfAbsent(parent, k -> new ArrayList<>())
                .add(pid);
        }
        List<Long> out = new ArrayList<>();
        ArrayDeque<Long> todo = new ArrayDeque<>();
        todo.add(root);
        while (!todo.isEmpty()) {
            List<Long> c = children.get(todo.removeFirst());
            if (c == null) continue;
            for (long child : c) {
                if (child == root || out.contains(child)) continue;
                out.add(child);
                todo.add(child);
            }
        }
        return out;
    }

    /** Field 4 of /proc/PID/stat, read after the last ')' because the command name may contain spaces. */
    private static long parentOf(File stat) {
        try {
            String s = new String(Files.readAllBytes(stat.toPath()), StandardCharsets.UTF_8);
            int close = s.lastIndexOf(')');
            if (close < 0) return -1;
            String[] f = s.substring(close + 1)
                .trim()
                .split(" ");
            return f.length > 1 ? digits(f[1]) : -1;
        } catch (IOException | RuntimeException e) {
            return -1; // exited meanwhile
        }
    }

    private static long digits(String s) {
        if (s.isEmpty() || s.length() > 18) return -1;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return -1;
        return Long.parseLong(s);
    }

    /** Full path of a system tool in /bin or /usr/bin; the bare name only when neither has it. */
    static String tool(String name) {
        for (String dir : new String[] { "/bin/", "/usr/bin/" }) {
            File f = new File(dir + name);
            if (f.isFile() && f.canExecute()) return f.getPath();
        }
        return name;
    }
}
