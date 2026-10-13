package com.akashiic.web.client.host;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Linux and macOS on Java 8: /proc or {@code ps} for the image, {@code kill -9} for the sweep. The tools are started
 * from /bin or /usr/bin by full path, not looked up in the game's PATH.
 */
final class UnixProcessControl implements ProcessControl {

    private static final String PS = tool("ps");
    private static final String KILL = tool("kill");

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
        ToolRunner.Result r = ToolRunner.run(Arrays.asList(KILL, "-9", Long.toString(pid)));
        return r != null && r.exitCode == 0;
    }

    @Override
    public long pidOf(Process process) {
        return ProcessControl.reflectivePid(process);
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
