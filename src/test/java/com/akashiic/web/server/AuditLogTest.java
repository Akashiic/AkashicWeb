package com.akashiic.web.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Audit log review findings (low): rotation during a run, secrets in logged URLs, Unicode line separators. */
class AuditLogTest {

    @TempDir
    Path dir;

    @Test
    void urlsAreLoggedWithoutTheirQueryOrFragment() {
        String logged = AuditLog.url("https://login.example/cb?code=SECRET123&state=x#access_token=TOKEN456");
        assertTrue(logged.startsWith("https://login.example/cb?[query "), logged);
        assertTrue(logged.contains("]#[fragment "), logged);
        assertFalse(logged.contains("SECRET123"));
        assertFalse(logged.contains("TOKEN456"));
        assertEquals("https://www.youtube.com/watch", AuditLog.url("https://www.youtube.com/watch"));
        assertEquals(
            AuditLog.url("https://www.youtube.com/watch?v=abc"),
            AuditLog.url("https://www.youtube.com/watch?v=abc"),
            "the same URL logs the same way, so moderators can still match lines");
        assertNotEquals(
            AuditLog.url("https://www.youtube.com/watch?v=abc"),
            AuditLog.url("https://www.youtube.com/watch?v=abd"));
        assertEquals(
            8,
            AuditLog.shortHash("anything")
                .length());
        assertEquals("", AuditLog.url(null));
    }

    /** The mutation that dropped the U+2028/U+2029 replacement went unnoticed before. */
    @Test
    void unicodeLineSeparatorsCannotStartAFakeLine() {
        assertEquals("a b c", AuditLog.clean("a\u2028b\u2029c"));
    }

    @Test
    void theFileRotatesDuringARunNotOnlyAtStartUp() throws Exception {
        File d = dir.toFile();
        AuditFile f = new AuditFile(d, "audit.log", 100);
        String line = "0123456789012345678901234567890123456789"; // 41 bytes with the newline
        f.write(line);
        f.write(line);
        assertFalse(new File(d, "audit.log.1").exists(), "82 bytes: still under the limit");
        f.write(line); // 123 bytes: over the limit, closed
        f.write("next");
        File old = new File(d, "audit.log.1");
        assertTrue(old.exists(), "rotated in the same run");
        assertEquals(
            3,
            Files.readAllLines(old.toPath(), StandardCharsets.UTF_8)
                .size());
        assertEquals(
            "next\n",
            new String(
                Files.readAllBytes(
                    f.file()
                        .toPath()),
                StandardCharsets.UTF_8));
        f.close();
        AuditFile again = new AuditFile(d, "audit.log", 100);
        again.write("after restart");
        again.close();
        assertEquals(
            "next\nafter restart\n",
            new String(
                Files.readAllBytes(
                    f.file()
                        .toPath()),
                StandardCharsets.UTF_8),
            "a small file is appended to");
    }
}
