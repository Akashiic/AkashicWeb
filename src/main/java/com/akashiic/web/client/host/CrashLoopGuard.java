package com.akashiic.web.client.host;

import java.util.ArrayDeque;

/**
 * Counts host exits in a sliding window and trips after {@link #DEFAULT_LIMIT} exits within
 * {@link #DEFAULT_WINDOW_MILLIS} (ADR-001: 5 exits in 10 minutes). It counts host exits in this game session, not game
 * crashes. Once tripped the supervisor stays DISABLED until {@link #clear} (an explicit re-enable).
 */
public final class CrashLoopGuard {

    public static final int DEFAULT_LIMIT = 5;
    public static final long DEFAULT_WINDOW_MILLIS = 10 * 60_000L;

    private final int limit;
    private final long windowMillis;
    private final ArrayDeque<Long> exits = new ArrayDeque<>();

    public CrashLoopGuard() {
        this(DEFAULT_LIMIT, DEFAULT_WINDOW_MILLIS);
    }

    public CrashLoopGuard(int limit, long windowMillis) {
        if (limit < 1 || windowMillis <= 0) throw new IllegalArgumentException("bad crash-loop limits");
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    /** Records one exit at {@code nowMillis}; returns true when this exit trips the guard. */
    public synchronized boolean recordExit(long nowMillis) {
        exits.addLast(nowMillis);
        prune(nowMillis);
        return exits.size() >= limit;
    }

    /** Exits still inside the window at {@code nowMillis}. */
    public synchronized int recentExits(long nowMillis) {
        prune(nowMillis);
        return exits.size();
    }

    public synchronized void clear() {
        exits.clear();
    }

    private void prune(long nowMillis) {
        while (!exits.isEmpty() && nowMillis - exits.peekFirst() >= windowMillis) exits.removeFirst();
    }
}
