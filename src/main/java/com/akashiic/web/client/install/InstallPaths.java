package com.akashiic.web.client.install;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Where the engine lives (ADR-011): outside {@code mods/} (Technic deletes it on pack updates), outside the game and
 * working directories, never in Documents or OneDrive (synced, and Controlled Folder Access guards Documents), shared
 * by every instance and mod version of this user.
 * <ul>
 * <li>Windows: {@code %LOCALAPPDATA%\AkashicWeb}. If that path has non-ASCII characters (C:\Users\João), its 8.3
 * short form is tried, then {@code %PROGRAMDATA%\AkashicWeb}, and only then the non-ASCII path itself.</li>
 * <li>Linux: {@code $XDG_DATA_HOME/AkashicWeb}, or {@code ~/.local/share/AkashicWeb}.</li>
 * </ul>
 * Layout under the base: {@code engine/<engineId>-<hash8>/}, {@code download/}, {@code import/},
 * {@code install.lock}; under the per-user base (the same folder unless the ProgramData fallback was used):
 * {@code profiles/<instance hash>/}.
 */
public final class InstallPaths {

    public static final String APP_FOLDER = "AkashicWeb";

    /** Everything the resolver needs, so the decision is testable on any OS. */
    public static final class Inputs {

        boolean windows;
        PlatformFacts facts;
        String userHome;
        /** The Minecraft instance folder (its {@code mods/} is inside it); also the usual working directory. */
        File gameDir;
        /** True when the folder exists or was created and a probe file could be written and deleted in it. */
        Predicate<File> usable = InstallPaths::probeWritable;
        /** Windows 8.3 short path of an existing folder, or null. */
        Function<File, String> shortPath = f -> null;

        public Inputs(boolean windows, PlatformFacts facts, String userHome, File gameDir) {
            this.windows = windows;
            this.facts = facts;
            this.userHome = userHome;
            this.gameDir = gameDir;
        }

        public Inputs usable(Predicate<File> usable) {
            this.usable = usable;
            return this;
        }

        public Inputs shortPath(Function<File, String> shortPath) {
            this.shortPath = shortPath;
            return this;
        }
    }

    /** Engine, downloads, the install lock and the primary import folder. */
    public final File base;
    /** Per-user folder for browser profiles and a second import folder; equals {@link #base} except on fallback. */
    public final File userBase;
    /** How the base was chosen, for {@code /web doctor} (e.g. "LOCALAPPDATA", "8.3 short path"). */
    public final String how;

    public InstallPaths(File base, File userBase, String how) {
        this.base = base;
        this.userBase = userBase;
        this.how = how;
    }

    public File engineRoot() {
        return new File(base, "engine");
    }

    public File downloadDir() {
        return new File(base, "download");
    }

    /**
     * Manual import folders (ADR-011): the player saves the official zip in {@code %LOCALAPPDATA%\AkashicWeb\import}
     * when the download itself cannot work. On the ProgramData fallback both folders are scanned, so the pt-BR guide
     * stays right.
     */
    public List<File> importDirs() {
        File primary = new File(base, "import");
        File user = new File(userBase, "import");
        return primary.equals(user) ? Collections.singletonList(primary) : Arrays.asList(primary, user);
    }

    /** Root of the per-instance browser profiles; always per user. */
    public File profilesDir() {
        return new File(userBase, "profiles");
    }

    /** Held while installing so two game instances never extract into the same folders at once. */
    public File lockFile() {
        return new File(base, "install.lock");
    }

    /**
     * Picks the base folder. Never returns a path inside the game directory or a relative path.
     *
     * @throws InstallException DOWNLOAD_FAILED when no candidate can be written
     */
    public static InstallPaths resolve(Inputs in) throws InstallException {
        return in.windows ? resolveWindows(in) : resolveLinux(in);
    }

    private static InstallPaths resolveWindows(Inputs in) throws InstallException {
        String local = absolute(in.facts.env("LOCALAPPDATA"), true);
        if (local == null && in.userHome != null) local = child(child(in.userHome, "AppData", true), "Local", true);
        String localBase = local == null ? null : child(local, APP_FOLDER, true);
        boolean localAllowed = localBase != null && allowed(localBase, in);
        if (localAllowed && isAscii(localBase) && in.usable.test(new File(localBase))) {
            return new InstallPaths(new File(localBase), new File(localBase), "LOCALAPPDATA");
        }
        String shortBase = null;
        if (localAllowed && !isAscii(localBase)) {
            String shortLocal = in.shortPath.apply(new File(local));
            if (shortLocal != null && isAscii(shortLocal)) {
                String candidate = child(shortLocal, APP_FOLDER, true);
                if (allowed(candidate, in) && in.usable.test(new File(candidate))) shortBase = candidate;
            }
        }
        if (shortBase != null) {
            return new InstallPaths(new File(shortBase), new File(shortBase), "LOCALAPPDATA (8.3 short path)");
        }
        // The browser profile (cookies, logins) stays per user even when the engine falls back to ProgramData, whose
        // subfolders every local user can read.
        boolean localUsable = localAllowed && in.usable.test(new File(localBase));
        String programData = absolute(in.facts.env("PROGRAMDATA"), true);
        if (programData != null && isAscii(programData)) {
            String pdBase = child(programData, APP_FOLDER, true);
            if (allowed(pdBase, in) && in.usable.test(new File(pdBase))) {
                return new InstallPaths(new File(pdBase), new File(localUsable ? localBase : pdBase), "PROGRAMDATA");
            }
        }
        // Last resort: a non-ASCII path still works for Java and, as far as we know, for Electron; ASCII is preferred
        // only because some native tools mishandle such paths. Untested on Windows (ADR-014 P1 cell).
        if (localUsable) return new InstallPaths(new File(localBase), new File(localBase), "LOCALAPPDATA (non-ASCII)");
        throw new InstallException(
            InstallFailure.DOWNLOAD_FAILED,
            "no writable install folder (LOCALAPPDATA=" + local + ", PROGRAMDATA=" + programData + ")");
    }

    private static InstallPaths resolveLinux(Inputs in) throws InstallException {
        String data = absolute(in.facts.env("XDG_DATA_HOME"), false); // XDG: relative values are invalid
        String how = "XDG_DATA_HOME";
        if (data == null && in.userHome != null) {
            data = child(child(in.userHome, ".local", false), "share", false);
            how = "~/.local/share";
        }
        String base = data == null ? null : child(data, APP_FOLDER, false);
        if (base != null && allowed(base, in) && in.usable.test(new File(base))) {
            return new InstallPaths(new File(base), new File(base), how);
        }
        throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "no writable install folder (" + base + ")");
    }

    /** Rejects the game dir (and so mods/), Documents and OneDrive. */
    static boolean allowed(String base, Inputs in) {
        String p = normalize(base);
        if (in.gameDir != null) {
            String game = normalize(in.gameDir.getPath());
            if (p.equals(game) || p.startsWith(game + "/")) return false;
        }
        for (String segment : p.split("/")) {
            if (segment.startsWith("onedrive") || segment.equals("documents") || segment.equals("my documents")) {
                return false;
            }
        }
        return true;
    }

    static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    private static String normalize(String path) {
        String p = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        while (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        return p;
    }

    /**
     * The path if it is absolute for the target OS, else null. Decided on the string, not with File.isAbsolute, so
     * the Windows rules are testable on Linux. A relative value would resolve against the working directory.
     */
    static String absolute(String path, boolean windows) {
        if (path == null) return null;
        String p = path.trim();
        boolean abs = windows ? p.matches("[A-Za-z]:[\\\\/].*") || p.startsWith("\\\\") : p.startsWith("/");
        return abs ? p : null;
    }

    static String child(String parent, String name, boolean windows) {
        String sep = windows ? "\\" : "/";
        return parent.endsWith(sep) || parent.endsWith("/") ? parent + name : parent + sep + name;
    }

    /** Creates the folder if needed and proves it is writable (File.canWrite is unreliable for Windows folders). */
    static boolean probeWritable(File dir) {
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        File probe = new File(dir, ".akw-write-test-" + Long.toHexString(System.nanoTime()));
        try {
            if (!probe.createNewFile()) return false;
            return probe.delete();
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
