package com.akashiic.web.common;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Per-player token bucket: {@code ratePerSecond} tokens per second with a burst of up to 2x. Thread-safe: it is
 * consulted by the network handler before an action enters the tick queue.
 * <p>
 * Copied from AkashicFM (same owner) {@code com.akashiic.fm.common.RateLimiter}, unchanged except for comments.
 */
public final class RateLimiter {

    private static final class Bucket {

        double tokens;
        long lastNanos;
    }

    private final Map<UUID, Bucket> buckets = new HashMap<>();
    private final LongSupplier clock;

    public RateLimiter() {
        this(System::nanoTime);
    }

    /** Injectable clock for tests. */
    public RateLimiter(LongSupplier nanoClock) {
        this.clock = nanoClock;
    }

    /** Takes one token. Returns false if the player is over the limit. */
    public synchronized boolean tryAcquire(UUID player, int ratePerSecond) {
        if (player == null) return false;
        double rate = Math.max(1, ratePerSecond);
        double capacity = rate * 2;
        long now = clock.getAsLong();
        Bucket b = buckets.get(player);
        if (b == null) {
            b = new Bucket();
            b.tokens = capacity;
            b.lastNanos = now;
            buckets.put(player, b);
        } else {
            double elapsed = Math.max(0, now - b.lastNanos) / 1e9;
            b.tokens = Math.min(capacity, b.tokens + elapsed * rate);
            b.lastNanos = now;
        }
        if (b.tokens < 1) return false;
        b.tokens -= 1;
        return true;
    }

    public synchronized void forget(UUID player) {
        buckets.remove(player);
    }

    public synchronized void clear() {
        buckets.clear();
    }
}
