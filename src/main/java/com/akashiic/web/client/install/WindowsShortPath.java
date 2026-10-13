package com.akashiic.web.client.install;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * Asks Windows for the 8.3 short form of a folder (for example {@code C:\Users\JOOCON~1\AppData\Local}), used when the
 * player's profile path has non-ASCII characters (ADR-014 P1 cell "C:\Users\João Conceição"). Java has no API for
 * GetShortPathNameW, so this runs {@code cmd.exe /d /c for %I in (.) do @echo %~sI} with the folder as the working
 * directory: the path never appears on a command line, so no quoting or code page can mangle it. Short names exist
 * only when 8.3 generation is on for the volume; otherwise cmd prints the long path, which {@link #parse} rejects.
 */
final class WindowsShortPath {

    private WindowsShortPath() {}

    /** The ASCII short path of {@code dir}, or null if there is none. */
    static String of(File dir, CommandRunner runner, String systemRoot) {
        String root = systemRoot == null ? "C:\\Windows" : systemRoot;
        String cmd = root + File.separator + "System32" + File.separator + "cmd.exe";
        try {
            // Tokens are passed separately: none contains a space, so Java adds no quotes cmd would misread.
            CommandRunner.Result r = runner
                .run(Arrays.asList(cmd, "/d", "/c", "for", "%I", "in", "(.)", "do", "@echo", "%~sI"), dir, 5000);
            return r.exitCode == 0 ? parse(r.output) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Pure: the first line if it is an absolute, printable-ASCII drive path. */
    static String parse(String output) {
        if (output == null) return null;
        String line = output.trim();
        int nl = line.indexOf('\n');
        if (nl >= 0) line = line.substring(0, nl).trim();
        if (!line.matches("[A-Za-z]:\\\\[\\x20-\\x7E]*")) return null;
        return line;
    }
}
