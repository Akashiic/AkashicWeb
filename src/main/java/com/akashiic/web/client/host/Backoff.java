package com.akashiic.web.client.host;

/**
 * Relaunch delays after a host exit: 1 s, 2 s, 4 s ... capped at 60 s (docs/PROTOCOL.md, ADR-001). Reset after a run
 * that stayed up long enough to count as healthy, so one crash an hour does not wait a minute. Not thread-safe: used
 * only by the supervisor thread (and {@link #reset} under the supervisor's monitor).
 */
public final class Backoff {

    public static final long FIRST_MILLIS = 1000;
    public static final long MAX_MILLIS = 60_000;

    private long next = FIRST_MILLIS;

    /** The delay to wait now; doubles the following one up to {@link #MAX_MILLIS}. */
    public long next() {
        long d = next;
        next = Math.min(MAX_MILLIS, next * 2);
        return d;
    }

    public void reset() {
        next = FIRST_MILLIS;
    }
}
