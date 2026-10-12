package com.akashiic.web.client.install;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 helpers. Every JRE ships SHA-256, so its absence is a broken runtime and surfaces as an Error. */
public final class Sha256 {

    static final int BUFFER = 64 * 1024;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Sha256() {}

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from the JRE", e);
        }
    }

    public static String of(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return of(in);
        }
    }

    public static String of(InputStream in) throws IOException {
        MessageDigest md = newDigest();
        byte[] buf = new byte[BUFFER];
        int n;
        while ((n = in.read(buf)) != -1) {
            md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    public static String of(byte[] bytes) {
        return hex(newDigest().digest(bytes));
    }

    public static String ofUtf8(String s) {
        return of(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }

    /** Constant-time comparison is not needed (nothing secret), but case must not matter for pasted pins. */
    public static boolean same(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }
}
