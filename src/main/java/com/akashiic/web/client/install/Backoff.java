package com.akashiic.web.client.install;

/**
 * Exponential backoff between download attempts (ADR-011): {@code base, 2*base, 4*base ...} capped at {@code max}.
 * Deterministic on purpose: one client fetching one file from GitHub needs no jitter, and tests stay exact.
 */
public final class Backoff {

    public static final Backoff DEFAULT = new Backoff(1000, 60_000);

    public final long baseMs;
    public final long maxMs;

    public Backoff(long baseMs, long maxMs) {
        if (baseMs < 0 || maxMs < baseMs) throw new IllegalArgumentException("bad backoff " + baseMs + "/" + maxMs);
        this.baseMs = baseMs;
        this.maxMs = maxMs;
    }

    /** Delay before the next attempt after {@code failures} consecutive failures (1-based). */
    public long delayMs(int failures) {
        if (failures <= 0) return 0;
        int shift = Math.min(failures - 1, 30);
        long d = baseMs << shift;
        return d < 0 || d > maxMs ? maxMs : d;
    }
}
