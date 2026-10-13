package com.akashiic.web.client.install;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The list of every file an engine install keeps, with size and SHA-256 ({@code akw-manifest.sha256} in the engine
 * folder). Written once at install (and rewritten when a mod update brings a new host app), then used for:
 * <ul>
 * <li>{@link #quickCheck}: at every game start, sizes only plus "no unexpected files" (ADR-011: cheap);</li>
 * <li>{@link #fullCheck}: a full re-hash right after install and after the host failed to load.</li>
 * </ul>
 * Format, one file per line, sorted: {@code <sha256> <size> <relative/path>}; header lines start with {@code #}.
 */
public final class LocalManifest {

    public static final String FILE = "akw-manifest.sha256";
    /** Written last, after the atomic rename: an engine folder without it is never used. */
    public static final String MARKER = ".complete";
    static final String HEADER = "# AkashicWeb engine manifest v1";

    public static final class Entry {

        public final String path;
        public final long size;
        public final String sha256;

        public Entry(String path, long size, String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    /** What a check found wrong; null from a check means the folder is intact. */
    public static final class Problem {

        public final InstallFailure failure;
        public final String detail;
        /** True when every problem is inside resources/app, which can be rewritten from the jar without a download. */
        public final boolean hostAppOnly;

        Problem(InstallFailure failure, String detail, boolean hostAppOnly) {
            this.failure = failure;
            this.detail = detail;
            this.hostAppOnly = hostAppOnly;
        }

        @Override
        public String toString() {
            return failure + ": " + detail;
        }
    }

    public final String engineId;
    public final String zipSha256;
    public final List<Entry> entries;

    public LocalManifest(String engineId, String zipSha256, List<Entry> entries) {
        this.engineId = engineId;
        this.zipSha256 = zipSha256;
        List<Entry> sorted = new ArrayList<>(entries);
        Collections.sort(sorted, Comparator.comparing(e -> e.path));
        this.entries = Collections.unmodifiableList(sorted);
    }

    public String render() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        sb.append("# engine ")
            .append(engineId)
            .append('\n');
        sb.append("# zip-sha256 ")
            .append(zipSha256)
            .append('\n');
        for (Entry e : entries) {
            sb.append(e.sha256)
                .append(' ')
                .append(e.size)
                .append(' ')
                .append(e.path)
                .append('\n');
        }
        return sb.toString();
    }

    public void write(File engineDir) throws IOException {
        FileOps.writeAtomic(new File(engineDir, FILE), render().getBytes(StandardCharsets.UTF_8));
    }

    /** @throws InstallException HASH_MISMATCH when the file is missing or malformed */
    public static LocalManifest read(File engineDir) throws InstallException {
        File f = new File(engineDir, FILE);
        List<String> lines;
        try {
            lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InstallException(InstallFailure.HASH_MISMATCH, "engine manifest unreadable: " + e, e);
        }
        return parse(lines);
    }

    static LocalManifest parse(List<String> lines) throws InstallException {
        if (lines.isEmpty() || !HEADER.equals(lines.get(0))) {
            throw new InstallException(InstallFailure.HASH_MISMATCH, "engine manifest has no header");
        }
        String engineId = null;
        String zip = null;
        List<Entry> entries = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.startsWith("# engine ")) {
                engineId = line.substring("# engine ".length());
            } else if (line.startsWith("# zip-sha256 ")) {
                zip = line.substring("# zip-sha256 ".length());
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                String[] p = line.split(" ", 3);
                if (p.length != 3 || !p[0].matches("[0-9a-f]{64}")
                    || !p[1].matches("\\d{1,12}")
                    || !SafePath.isSafeRelative(p[2])) {
                    throw new InstallException(InstallFailure.HASH_MISMATCH, "engine manifest line malformed");
                }
                entries.add(new Entry(p[2], Long.parseLong(p[1]), p[0]));
            }
        }
        if (engineId == null || zip == null || entries.isEmpty()) {
            throw new InstallException(InstallFailure.HASH_MISMATCH, "engine manifest incomplete");
        }
        return new LocalManifest(engineId, zip, entries);
    }

    /** SHA-256 of the rendered manifest; the {@link #MARKER} records it so the two cannot drift apart. */
    public String digest() {
        return Sha256.ofUtf8(render());
    }

    /** Startup check: every listed file exists with its size, and nothing unexpected was added. */
    public Problem quickCheck(File engineDir, String hostExecutable) {
        return check(engineDir, hostExecutable, false, false);
    }

    /** Full re-hash of every listed file, plus the unexpected-file scan. */
    public Problem fullCheck(File engineDir, String hostExecutable) {
        return check(engineDir, hostExecutable, true, false);
    }

    /** Full re-hash of the host app files only (a few KB, so it runs at every start). */
    public Problem hostAppCheck(File engineDir) {
        return check(engineDir, null, true, true);
    }

    private Problem check(File engineDir, String hostExecutable, boolean hash, boolean hostAppScope) {
        List<String> bad = new ArrayList<>();
        boolean exeGone = false;
        boolean appOnly = true;
        Set<String> listed = new HashSet<>();
        for (Entry e : entries) {
            listed.add(e.path);
            if (hostAppScope && !isHostApp(e.path)) continue;
            File f = new File(engineDir, e.path);
            String why = null;
            if (!f.isFile()) {
                why = "missing";
            } else if (f.length() != e.size) {
                why = "size " + f.length() + " != " + e.size;
            } else if (hash) {
                try {
                    if (!e.sha256.equals(Sha256.of(f))) why = "sha256 changed";
                } catch (IOException ex) {
                    why = "unreadable (" + ex.getMessage() + ")";
                }
            }
            if (why != null) {
                bad.add(e.path + " " + why);
                appOnly &= isHostApp(e.path);
                // A missing or emptied executable right after we wrote it is how quarantine looks from here.
                if (e.path.equals(hostExecutable) && (!f.isFile() || f.length() == 0)) exeGone = true;
            }
        }
        List<String> extra = new ArrayList<>();
        if (hostAppScope) {
            scanUnexpected(new File(engineDir, EngineManifest.APP_DIR), EngineManifest.APP_DIR + "/", listed, extra);
        } else {
            scanUnexpected(engineDir, "", listed, extra);
        }
        for (String x : extra) {
            bad.add(x + " unexpected");
            appOnly &= isHostApp(x);
        }
        if (bad.isEmpty()) return null;
        String detail = bad.size() + " file(s): " + String.join(", ", bad.subList(0, Math.min(4, bad.size())));
        return new Problem(exeGone ? InstallFailure.AV_BLOCKED : InstallFailure.HASH_MISMATCH, detail, appOnly);
    }

    /**
     * Nothing should ever add files to the engine folder. A file that appears is either junk or a planted DLL next to
     * the exe, and Electron prefers a planted {@code resources/app.asar} over our {@code resources/app}, so any
     * unexpected file fails the check. Our own bookkeeping files and logs are ignored.
     */
    private static void scanUnexpected(File dir, String prefix, Set<String> listed, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            String rel = prefix + c.getName();
            if (c.isDirectory()) {
                // Our own temporary folders (host app refresh); nothing loads code from them.
                if (!c.getName()
                    .startsWith(".akw-")) scanUnexpected(c, rel + "/", listed, out);
            } else if (!listed.contains(rel) && !ignorable(rel)) {
                out.add(rel);
            }
        }
    }

    static boolean ignorable(String rel) {
        if (rel.equals(FILE) || rel.equals(MARKER)) return true;
        String name = rel.substring(rel.lastIndexOf('/') + 1)
            .toLowerCase(Locale.ROOT);
        return name.startsWith(".akw-") || name.contains(".tmp-") || name.endsWith(".log") || name.endsWith(".dmp");
    }

    static boolean isHostApp(String rel) {
        return rel.startsWith(EngineManifest.APP_DIR + "/");
    }

    /** Vendor entries only (everything outside resources/app). */
    public List<Entry> vendorEntries() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (!isHostApp(e.path)) out.add(e);
        }
        return out;
    }
}
