package com.akashiic.web.server;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * file, checked after every line ({@link AuditFile}). URLs are logged through {@link #url}, without their query and
 * fragment. Main thread only.
 * <p>
 * Copied from AkashicFM (same owner) {@code com.akashiic.fm.server.AuditLog}; file name and messages changed.
 */
public final class AuditLog {

    static final String FILE = "akashicweb-audit.log";
    static final long ROTATE_BYTES = 10L * 1024 * 1024;

    private static AuditFile file;
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

    /**
     * A URL as the audit lines show it: scheme, host and path, with the query and the fragment each replaced by a
     * short hash. Queries and fragments carry OAuth codes, access tokens and magic-link secrets, and since D-016 the
     * controller's browser reports every page it reaches, so the full URL would copy them into the audit file and the
     * server log (review finding). The hashes still tell equal URLs apart; the screen's current full URL stays visible
     * to admins in {@code /web info}.
     */
    public static String url(String url) {
        if (url == null) return "";
        String rest = url;
        String fragment = null;
        int hashAt = rest.indexOf('#');
        if (hashAt >= 0) {
            fragment = rest.substring(hashAt + 1);
            rest = rest.substring(0, hashAt);
        }
        String query = null;
        int queryAt = rest.indexOf('?');
        if (queryAt >= 0) {
            query = rest.substring(queryAt + 1);
            rest = rest.substring(0, queryAt);
        }
        StringBuilder b = new StringBuilder(rest);
        if (query != null) b.append("?[query ")
            .append(shortHash(query))
            .append(']');
        if (fragment != null) b.append("#[fragment ")
            .append(shortHash(fragment))
            .append(']');
        return b.toString();
    }

    /** First 8 hex digits of SHA-256: enough to tell values apart, far too short to recover a secret from. */
    static String shortHash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(8);
            for (int i = 0; i < 4; i++) b.append(String.format(Locale.ROOT, "%02x", d[i] & 0xFF));
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            return "-"; // every Java 8 runtime has SHA-256; without it the value is simply not shown
        }
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
            if (file == null) file = new AuditFile(logsDir(), FILE, ROTATE_BYTES);
            file.write(line);
        } catch (IOException e) {
            failed = true; // without the file, the server log still has every line
            AkashicWeb.LOG.warn("AkashicWeb: audit file unavailable ({}); server log only", e.toString());
        }
    }

    private static File logsDir() {
        MinecraftServer server = MinecraftServer.getServer();
        return server != null ? server.getFile("logs") : new File("logs");
    }

    /** Server stopping. */
    public static void close() {
        if (file != null) {
            try {
                file.close();
            } catch (IOException e) {
                AkashicWeb.LOG.warn("AkashicWeb: closing the audit file failed: {}", e.toString());
            }
        }
        file = null;
        failed = false;
    }
}
