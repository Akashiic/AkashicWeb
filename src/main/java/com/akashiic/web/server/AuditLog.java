package com.akashiic.web.server;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import com.akashiic.web.AkashicWeb;

/**
 * Audit log in {@code logs/akashicweb-audit.log}: URL changes, rights changes, admin actions, screens formed or
 * dissolved and rejected packets. One line per event, UTC time; also mirrored to the server log. Player text is
 * flattened (no line breaks or control characters: nobody can forge a fake line). Rotates at 10 MB keeping one old
 * file. Main thread only.
 * <p>
 * Copied from AkashicFM (same owner) {@code com.akashiic.fm.server.AuditLog}; file name and messages changed.
 */
public final class AuditLog {

    static final String FILE = "akashicweb-audit.log";
    static final long ROTATE_BYTES = 10L * 1024 * 1024;

    private static Writer writer;
    private static boolean failed;

    private AuditLog() {}

    /** {@code actor} may be null (console, system). */
    public static void log(String actor, UUID actorId, String action, String details) {
        String line = format(System.currentTimeMillis(), actor, actorId, action, details);
        AkashicWeb.LOG.info("[audit] {}", line.substring(line.indexOf(' ') + 1));
        write(line);
    }

    /** "2026-10-12T17:00:00Z Name (uuid) action: details". */
    static String format(long ms, String actor, UUID actorId, String action, String details) {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder b = new StringBuilder(iso.format(new Date(ms))).append(' ');
        b.append(actor == null || actor.isEmpty() ? "console" : clean(actor));
        if (actorId != null) b.append(" (")
            .append(actorId)
            .append(')');
        b.append(' ')
            .append(clean(action));
        if (details != null && !details.isEmpty()) b.append(": ")
            .append(clean(details));
        return b.toString();
    }

    /** One line only: control characters (line breaks included) become spaces; cut at 512. */
    static String clean(String s) {
        StringBuilder b = new StringBuilder(Math.min(s.length(), 512));
        for (int i = 0; i < s.length() && b.length() < 512; i++) {
            char c = s.charAt(i);
            b.append(Character.isISOControl(c) || c == 0x2028 || c == 0x2029 ? ' ' : c);
        }
        return b.toString();
    }

    private static void write(String line) {
        if (failed) return;
        try {
            if (writer == null) writer = open();
            writer.write(line);
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            failed = true; // without the file, the server log still has every line
            AkashicWeb.LOG.warn("AkashicWeb: audit file unavailable ({}); server log only", e.toString());
        }
    }

    private static Writer open() throws IOException {
        MinecraftServer server = MinecraftServer.getServer();
        File dir = server != null ? server.getFile("logs") : new File("logs");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File f = new File(dir, FILE);
        if (f.length() > ROTATE_BYTES) {
            File old = new File(dir, FILE + ".1");
            if (old.exists() && !old.delete()) throw new IOException("cannot delete " + old);
            if (!f.renameTo(old)) throw new IOException("cannot rotate " + f);
        }
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8));
    }

    /** Server stopping. */
    public static void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                AkashicWeb.LOG.warn("AkashicWeb: closing the audit file failed: {}", e.toString());
            }
        }
        writer = null;
        failed = false;
    }
}
