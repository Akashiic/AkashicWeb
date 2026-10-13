package com.akashiic.web.client.host;

import java.util.Arrays;

/**
 * Windows on Java 8: {@code tasklist} for the image name and {@code taskkill /T /F} for the sweep (ADR-001). Only the
 * CSV shape of tasklist is parsed, never its localised messages (a pt-BR Windows prints "INFORMAÇÕES: ...").
 */
final class WindowsProcessControl implements ProcessControl {

    @Override
    public String imageName(long pid) {
        if (pid <= 0) return null;
        ToolRunner.Result r = ToolRunner.run(Arrays.asList("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH"));
        if (r == null || r.exitCode != 0) return "";
        return parseTasklistImage(r.output);
    }

    @Override
    public boolean kill(long pid) {
        if (pid <= 0) return false;
        // /T also ends the renderer and GPU children, /F because the host does not handle WM_CLOSE.
        ToolRunner.Result r = ToolRunner.run(Arrays.asList("taskkill", "/PID", Long.toString(pid), "/T", "/F"));
        return r != null && r.exitCode == 0;
    }

    @Override
    public long pidOf(Process process) {
        return ProcessControl.reflectivePid(process);
    }

    /** First CSV field of the first quoted line ({@code "AkashicWebHost.exe","1234",...}), or null if none. */
    static String parseTasklistImage(String output) {
        for (String line : output.split("\r?\n")) {
            String t = line.trim();
            if (!t.startsWith("\"")) continue;
            int end = t.indexOf('"', 1);
            return end > 1 ? t.substring(1, end) : "";
        }
        return null;
    }
}
