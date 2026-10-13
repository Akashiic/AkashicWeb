package com.akashiic.web.client.host;

import java.util.Arrays;

/**
 * Windows on Java 8: {@code tasklist} for the image name and {@code taskkill /T /F} for the sweep (ADR-001). Only the
 * CSV shape of tasklist is parsed, never its localised messages (a pt-BR Windows prints "INFORMAÇÕES: ...").
 *
 * <p>
 * Both tools are started by their full path in {@code %SystemRoot%\System32}. A bare name would let CreateProcess
 * look in the application directory and the current directory (the Minecraft instance folder) first, so a planted
 * {@code tasklist.exe} there would run with the game's rights.
 */
final class WindowsProcessControl implements ProcessControl {

    private static final String TASKLIST = systemTool("tasklist.exe", systemRoot());
    private static final String TASKKILL = systemTool("taskkill.exe", systemRoot());

    @Override
    public String imageName(long pid) {
        if (pid <= 0) return null;
        ToolRunner.Result r = ToolRunner.run(Arrays.asList(TASKLIST, "/FI", "PID eq " + pid, "/FO", "CSV", "/NH"));
        if (r == null || r.exitCode != 0) return "";
        return parseTasklistImage(r.output);
    }

    @Override
    public boolean kill(long pid) {
        if (pid <= 0) return false;
        // /T also ends the renderer and GPU children, /F because the host does not handle WM_CLOSE.
        ToolRunner.Result r = ToolRunner.run(Arrays.asList(TASKKILL, "/PID", Long.toString(pid), "/T", "/F"));
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

    /** {@code <systemRoot>\System32\<exe>}; C:\Windows when the variable is missing. */
    static String systemTool(String exe, String systemRoot) {
        String root = systemRoot == null || systemRoot.trim()
            .isEmpty() ? "C:\\Windows" : systemRoot.trim();
        while (root.endsWith("\\") || root.endsWith("/")) root = root.substring(0, root.length() - 1);
        return root + "\\System32\\" + exe;
    }

    private static String systemRoot() {
        String root = System.getenv("SystemRoot");
        return root != null ? root : System.getenv("windir");
    }
}
