package com.akashiic.web.server;

/**
 * The server time base of the protocol (serverTimeMs in S2CHello, S2CMediaClock, S2CClockPong): wall-clock
 * milliseconds at start-up plus monotonic elapsed time, so it never jumps when the host's clock is adjusted.
 */
public final class ServerClock {

    private static final long BASE_NANOS = System.nanoTime();
    private static final long BASE_MS = System.currentTimeMillis();

    private ServerClock() {}

    public static long nowMs() {
        return BASE_MS + (System.nanoTime() - BASE_NANOS) / 1_000_000L;
    }
}
