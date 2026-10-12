package com.akashiic.web.client.host;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The hosts this game started, one per line: {@code pid <TAB> parentPid <TAB> startedMillis <TAB> imageName}. The
 * stale-host sweep may only ever kill PIDs listed here (ADR-001), and only while they still run the recorded image.
 * Best effort: an I/O failure is logged and never stops a launch. Rewritten atomically (temp file + move).
 */
final class HostPidFile {

    private static final Logger LOG = LogManager.getLogger("AkashicWeb/Host");
    private static final int MAX_ENTRIES = 64;

    static final class Entry {

        final long pid;
        final long parentPid;
        final long startedMillis;
        final String image;

        Entry(long pid, long parentPid, long startedMillis, String image) {
            this.pid = pid;
            this.parentPid = parentPid;
            this.startedMillis = startedMillis;
            this.image = image;
        }

        String line() {
            return pid + "\t" + parentPid + "\t" + startedMillis + "\t" + image;
        }

        static Entry parse(String line) {
            String[] f = line.split("\t", 4);
            if (f.length != 4 || f[3].isEmpty()) return null;
            try {
                long pid = Long.parseLong(f[0].trim());
                if (pid <= 0) return null;
                return new Entry(pid, Long.parseLong(f[1].trim()), Long.parseLong(f[2].trim()), f[3].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private final File file;

    HostPidFile(File file) {
        this.file = file;
    }

    synchronized List<Entry> read() {
        if (!file.isFile()) return Collections.emptyList();
        try {
            List<Entry> out = new ArrayList<>();
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                Entry e = Entry.parse(line);
                if (e != null && out.size() < MAX_ENTRIES) out.add(e);
            }
            return out;
        } catch (IOException e) {
            LOG.warn("AkashicWeb: cannot read the host PID file {}: {}", file, e.toString());
            return Collections.emptyList();
        }
    }

    synchronized void add(Entry entry) {
        List<Entry> all = new ArrayList<>(read());
        all.removeIf(e -> e.pid == entry.pid);
        all.add(entry);
        write(all);
    }

    synchronized void remove(long pid) {
        List<Entry> all = new ArrayList<>(read());
        if (all.removeIf(e -> e.pid == pid)) write(all);
    }

    synchronized void write(List<Entry> entries) {
        try {
            File dir = file.getAbsoluteFile()
                .getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            List<String> lines = new ArrayList<>(entries.size());
            for (Entry e : entries) lines.add(e.line());
            File tmp = new File(dir, file.getName() + ".tmp");
            Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("AkashicWeb: cannot write the host PID file {}: {}", file, e.toString());
        }
    }
}
