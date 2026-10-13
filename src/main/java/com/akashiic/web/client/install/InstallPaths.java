package com.akashiic.web.client.install;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Where the engine lives (ADR-011): outside {@code mods/} (Technic deletes it on pack updates), outside the game and
 * working directories, never in Documents or OneDrive (synced, and Controlled Folder Access guards Documents), shared
 * by every instance and mod version of this user, and private to this user.
 * <ul>
 * <li>Windows: {@code %LOCALAPPDATA%\AkashicWeb}. When that path has non-ASCII characters (C:\Users\João), its 8.3
 * short form is used if Windows has one, else the non-ASCII path itself: Java and Electron both use the wide-character
 * Windows APIs, and the browser profile has to live in that folder anyway. If LOCALAPPDATA is missing, relative, or
 * inside OneDrive or Documents, {@code %USERPROFILE%\AppData\Local} is tried instead.</li>
 * <li>Linux: {@code $XDG_DATA_HOME/AkashicWeb}, or {@code ~/.local/share/AkashicWeb}.</li>
 * </ul>
 * There is deliberately no machine-wide fallback such as {@code %PROGRAMDATA%}: by default every local user may create
 * folders there, so another account could pre-create the folder, or plant files in it, whose executable this player
 * then runs. Layout under the base: {@code engine/<engineId>-<hash8>/}, {@code download/}, {@code import/},
 * {@code profiles/<instance hash>/}, {@code install.lock}.
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

    /** Engine, downloads, the import folder, browser profiles and the install lock. */
    public final File base;
    /** How the base was chosen, for {@code /web doctor} (e.g. "LOCALAPPDATA", "LOCALAPPDATA (8.3 short path)"). */
    public final String how;
    /**
     * The base as players and logs see it, without the expanded profile path (which usually holds the player's real
     * name): {@code %LOCALAPPDATA%\AkashicWeb}, {@code ~/.local/share/AkashicWeb}. Explorer's address bar accepts the
     * Windows form as it is.
     */
    public final String display;
    /** The user's home folder, replaced by a placeholder in {@link #redact}; null to leave it. */
    private final String userHome;

    public InstallPaths(File base, String how, String display) {
        this(base, how, display, null);
    }

    InstallPaths(File base, String how, String display, String userHome) {
        this.base = base;
        this.how = how;
        this.display = display;
        this.userHome = userHome;
    }

    public File engineRoot() {
        return new File(base, "engine");
    }

    public File downloadDir() {
        return new File(base, "download");
    }

    /**
     * Manual import folder (ADR-011): the player saves the official zip in {@code %LOCALAPPDATA%\AkashicWeb\import}
     * when the download itself cannot work.
     */
    public File importDir() {
        return new File(base, "import");
    }

    /** Root of the per-instance browser profiles. */
    public File profilesDir() {
        return new File(base, "profiles");
    }

    /** Held while installing so two game instances never extract into the same folders at once. */
    public File lockFile() {
        return new File(base, "install.lock");
    }

    /** A folder under the base as players see it, e.g. {@code %LOCALAPPDATA%\AkashicWeb\import}. */
    public String display(File f) {
        return redact(f.getPath());
    }

    /**
     * {@code text} with every spelling of the base path replaced by {@link #display} and the user's home folder by a
     * placeholder, so logs and {@code /web doctor} lines that players paste for support do not carry their name.
     */
    public String redact(String text) {
        if (text == null) return null;
        String out = text;
        for (String path : spellings(base)) {
            if (!path.isEmpty()) out = out.replace(path, display);
        }
        if (userHome != null && userHome.length() > 3) {
            for (String path : spellings(new File(userHome))) out = out.replace(path, homePlaceholder());
        }
        return out;
    }

    private String homePlaceholder() {
        return display.startsWith("%") ? "%USERPROFILE%" : "~";
    }

    /** The path as given and, if different, canonical (an 8.3 short base expands to its long form there). */
    private static List<String> spellings(File f) {
        List<String> out = new ArrayList<>();
        out.add(f.getPath());
        try {
            String canonical = f.getCanonicalPath();
            if (!canonical.equals(f.getPath())) out.add(canonical);
        } catch (IOException e) {
            // Boundary: a path that cannot be canonicalized is only redacted in the form it was given.
        }
        // Longest first, so a base inside the home folder is replaced as a whole before the home part.
        out.sort((a, b) -> b.length() - a.length());
        return out;
    }

    /**
     * Picks the base folder. Never returns a path inside the game directory, a relative path, or a folder other local
     * users can write to.
     *
     * @throws InstallException INSTALL_DIR_NOT_WRITABLE when no candidate can be used
     */
    public static InstallPaths resolve(Inputs in) throws InstallException {
        return in.windows ? resolveWindows(in) : resolveLinux(in);
    }

    private static InstallPaths resolveWindows(Inputs in) throws InstallException {
        List<String> refused = new ArrayList<>();
        String local = absolute(in.facts.env("LOCALAPPDATA"), true);
        if (local != null) {
            InstallPaths p = tryLocalAppData(in, local, "LOCALAPPDATA", "%LOCALAPPDATA%", refused);
            if (p != null) return p;
        }
        if (in.userHome != null && absolute(in.userHome, true) != null) {
            String profileLocal = child(child(in.userHome, "AppData", true), "Local", true);
            if (local == null || !normalize(profileLocal).equals(normalize(local))) {
                InstallPaths p = tryLocalAppData(
                    in,
                    profileLocal,
                    "user profile",
                    "%USERPROFILE%\\AppData\\Local",
                    refused);
                if (p != null) return p;
            }
        }
        if (refused.isEmpty()) refused.add("LOCALAPPDATA and the user profile are unknown");
        throw new InstallException(
            InstallFailure.INSTALL_DIR_NOT_WRITABLE,
            "no usable install folder: " + String.join("; ", refused));
    }

    /** The base under one Local AppData candidate, or null with the reason added to {@code refused}. */
    private static InstallPaths tryLocalAppData(Inputs in, String local, String how, String displayRoot,
        List<String> refused) {
        String base = child(local, APP_FOLDER, true);
        String display = displayRoot + "\\" + APP_FOLDER;
        if (!allowed(base, in)) {
            refused.add(displayRoot + " is inside OneDrive, Documents or the game folder");
            return null;
        }
        if (isAscii(base)) {
            if (in.usable.test(new File(base))) return new InstallPaths(new File(base), how, display, in.userHome);
            refused.add(display + " is not writable");
            return null;
        }
        String shortLocal = in.shortPath.apply(new File(local));
        if (shortLocal != null && isAscii(shortLocal)) {
            String shortBase = child(shortLocal, APP_FOLDER, true);
            if (allowed(shortBase, in) && in.usable.test(new File(shortBase))) {
                return new InstallPaths(new File(shortBase), how + " (8.3 short path)", display, in.userHome);
            }
        }
        // Untested on Windows (ADR-014 P1 cell "C:\Users\João Conceição"): expected to work, see the class comment.
        if (in.usable.test(new File(base))) {
            return new InstallPaths(new File(base), how + " (non-ASCII)", display, in.userHome);
        }
        refused.add(display + " is not writable");
        return null;
    }

    private static InstallPaths resolveLinux(Inputs in) throws InstallException {
        String data = absolute(in.facts.env("XDG_DATA_HOME"), false); // XDG: relative values are invalid
        String how = "XDG_DATA_HOME";
        String displayRoot = "$XDG_DATA_HOME";
        if (data == null && in.userHome != null) {
            data = child(child(in.userHome, ".local", false), "share", false);
            how = "~/.local/share";
            displayRoot = "~/.local/share";
        }
        String base = data == null ? null : child(data, APP_FOLDER, false);
        String display = displayRoot + "/" + APP_FOLDER;
        if (base != null && allowed(base, in) && in.usable.test(new File(base))) {
            return new InstallPaths(new File(base), how, display, in.userHome);
        }
        throw new InstallException(
            InstallFailure.INSTALL_DIR_NOT_WRITABLE,
            "no writable install folder (" + display + ")");
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
        String p = path.replace('\\', '/')
            .toLowerCase(Locale.ROOT);
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
