package com.akashiic.web.client.install;

import java.io.File;
import java.io.IOException;

/**
 * Validation of relative paths taken from archives and manifests before they become files (zip-slip and friends).
 * A path is safe when it is non-empty, uses only {@code /} separators, has no {@code .}/{@code ..}/empty segments, no
 * drive letters, colons (NTFS streams), backslashes or control characters, and stays under Windows' MAX_PATH budget.
 * The canonical-path check in {@link #resolveInside} is the second, independent line of defence.
 */
final class SafePath {

    /** Leaves room under MAX_PATH (260) for {@code C:\Users\<name>\AppData\Local\AkashicWeb\engine\<dir>\}. */
    static final int MAX_RELATIVE_LENGTH = 120;

    private SafePath() {}

    static boolean isSafeRelative(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_RELATIVE_LENGTH) return false;
        if (path.startsWith("/") || path.endsWith("/")) return false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<'
                || c == '>' || c == '|') {
                return false;
            }
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
            // Windows silently strips trailing dots and spaces, which would alias two different names.
            if (segment.endsWith(".") || segment.endsWith(" ")) return false;
            if (isWindowsDeviceName(segment)) return false;
        }
        return true;
    }

    /** CON, NUL, COM1 ... open devices on Windows even with an extension ("nul.txt"). */
    static boolean isWindowsDeviceName(String segment) {
        int dot = segment.indexOf('.');
        String stem = (dot < 0 ? segment : segment.substring(0, dot)).toLowerCase(java.util.Locale.ROOT);
        return stem.matches("con|prn|aux|nul|com[1-9]|lpt[1-9]");
    }

    /**
     * The file for {@code relative} under {@code root}, after proving that its canonical path is inside the root's.
     *
     * @throws InstallException HASH_MISMATCH for an unsafe path (an official, hash-pinned archive never has one)
     */
    static File resolveInside(File root, String relative) throws InstallException {
        if (!isSafeRelative(relative)) {
            throw new InstallException(InstallFailure.HASH_MISMATCH, "unsafe path in archive: " + printable(relative));
        }
        File out = new File(root, relative.replace('/', File.separatorChar));
        try {
            String rootCanon = root.getCanonicalPath() + File.separator;
            if (!out.getCanonicalPath().startsWith(rootCanon)) {
                throw new InstallException(InstallFailure.HASH_MISMATCH, "path escapes target: " + printable(relative));
            }
        } catch (IOException e) {
            throw new InstallException(InstallFailure.HASH_MISMATCH, "cannot resolve " + printable(relative), e);
        }
        return out;
    }

    static String printable(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && i < 80; i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || c == 0x7F ? '?' : c);
        }
        return sb.toString();
    }
}
