package com.akashiic.web.client.host;

import java.util.ArrayDeque;

/**
 * Counts host exits in a sliding window and trips after {@link #DEFAULT_LIMIT} exits within
 * {@link #DEFAULT_WINDOW_MILLIS} (ADR-001: 5 exits in 10 minutes). It counts host exits in this game session, not game
 * crashes. Once tripped the supervisor stays DISABLED until {@link #clear} (an explicit re-enable). Each exit keeps
 * its {@link HostFailure}, so a window made only of launch failures (antivirus, missing file) can be reported as such
 * instead of as a crash loop.
 */
public final class CrashLoopGuard {

    public static final int DEFAULT_LIMIT = 5;
    public static final long DEFAULT_WINDOW_MILLIS = 10 * 60_000L;

    private static final class Exit {

        final long atMillis;
        final HostFailure failure;

        Exit(long atMillis, HostFailure failure) {
            this.atMillis = atMillis;
            this.failure = failure;
        }
    }

    private final int limit;
    private final long windowMillis;
    private final ArrayDeque<Exit> exits = new ArrayDeque<>();

    public CrashLoopGuard() {
        this(DEFAULT_LIMIT, DEFAULT_WINDOW_MILLIS);
    }

    public CrashLoopGuard(int limit, long windowMillis) {
        if (limit < 1 || windowMillis <= 0) throw new IllegalArgumentException("bad crash-loop limits");
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    /** Records one exit of unknown kind at {@code nowMillis}; returns true when this exit trips the guard. */
    public synchronized boolean recordExit(long nowMillis) {
        return recordExit(nowMillis, null);
    }

    /** Records one exit of kind {@code failure} at {@code nowMillis}; returns true when this exit trips the guard. */
    public synchronized boolean recordExit(long nowMillis, HostFailure failure) {
        exits.addLast(new Exit(nowMillis, failure));
        prune(nowMillis);
        return exits.size() >= limit;
    }

    /** Exits still inside the window at {@code nowMillis}. */
    public synchronized int recentExits(long nowMillis) {
        prune(nowMillis);
        return exits.size();
    }

    /** True when the window holds at least one exit and every exit in it was {@code failure}. */
    public synchronized boolean allRecentWere(HostFailure failure, long nowMillis) {
        prune(nowMillis);
        if (exits.isEmpty()) return false;
        for (Exit e : exits) if (e.failure != failure) return false;
        return true;
    }

    public synchronized void clear() {
        exits.clear();
    }

    private void prune(long nowMillis) {
        while (!exits.isEmpty() && nowMillis - exits.peekFirst().atMillis >= windowMillis) exits.removeFirst();
    }
}
