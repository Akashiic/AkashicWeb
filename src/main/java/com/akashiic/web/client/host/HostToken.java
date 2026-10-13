package com.akashiic.web.client.host;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * The per-launch secret that proves a loopback connection comes from the host we spawned (ADR-003). 128 random bits
 * as 32 lower-case hex characters; a new token for every launch, so a leaked token dies with its host. Never logged.
 */
public final class HostToken {

    public static final int HEX_CHARS = 32;

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private HostToken() {}

    public static String generate() {
        byte[] bytes = new byte[HEX_CHARS / 2];
        RANDOM.nextBytes(bytes);
        char[] out = new char[HEX_CHARS];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }

    /**
     * Constant-time comparison: the loop always runs over the whole expected token, so the time taken does not tell a
     * local attacker how many leading characters were right. A null or differently sized candidate fails.
     */
    public static boolean matches(String expected, String candidate) {
        if (expected == null || candidate == null) return false;
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = candidate.getBytes(StandardCharsets.UTF_8);
        int diff = a.length ^ b.length;
        for (int i = 0; i < a.length; i++) {
            byte other = b.length == 0 ? 0 : b[i % b.length];
            diff |= a[i] ^ other;
        }
        return diff == 0 && a.length > 0;
    }
}
