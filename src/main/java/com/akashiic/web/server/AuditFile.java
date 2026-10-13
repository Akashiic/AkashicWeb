package com.akashiic.web.server;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The audit log file with size-based rotation, keeping one old file ({@code <name>.1}). The size is checked after
 * every line, not only when the file is opened: a server runs for weeks, and checking once per start let the file
 * grow without bound (review finding). Each line is flushed so a crash loses nothing. Not thread-safe: the
 * {@link AuditLog} main-thread rule applies.
 */
final class AuditFile {

    private final File dir;
    private final String name;
    private final long limitBytes;
    private OutputStream out;
    private long size;

    AuditFile(File dir, String name, long limitBytes) {
        this.dir = dir;
        this.name = name;
        this.limitBytes = limitBytes;
    }

    /** Appends one line. Past the limit the file is closed, and the next line rotates it. */
    void write(String line) throws IOException {
        if (out == null) open();
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        out.write(bytes);
        out.flush();
        size += bytes.length;
        if (size > limitBytes) close();
    }

    void close() throws IOException {
        OutputStream o = out;
        out = null;
        if (o != null) o.close();
    }

    File file() {
        return new File(dir, name);
    }

    private void open() throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File f = file();
        if (f.length() > limitBytes) {
            File old = new File(dir, name + ".1");
            if (old.exists() && !old.delete()) throw new IOException("cannot delete " + old);
            if (!f.renameTo(old)) throw new IOException("cannot rotate " + f);
        }
        out = new BufferedOutputStream(new FileOutputStream(f, true));
        size = f.length();
    }
}
