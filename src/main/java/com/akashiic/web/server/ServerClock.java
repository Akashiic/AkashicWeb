package com.akashiic.web.server;

/**
 * The one server clock of the protocol: wall-clock milliseconds at start-up plus monotonic elapsed time, so it never
 * jumps when the host's clock is adjusted. It is the only time source for
 * <ul>
 * <li>S2CClockPong.serverTimeMs, the stamp clients sync their estimate of server time against;</li>
 * <li>S2CMediaClock.serverTimeMs, the instant the broadcast media position refers to;</li>
 * <li>S2CHello.serverTimeMs;</li>
 * <li>the server-side reading of C2SMediaSample.clientServerTimeMs, which is the client's estimate of this clock
 * (MediaClock compares it with {@link #nowMs()}, never with the tick or wall clock);</li>
 * <li>the controller lock's keepalive and idle times.</li>
 * </ul>
 * Mixing in another clock (System.currentTimeMillis, tick counts) would put a constant error into every follower's
 * position, so protocol code must stamp with {@link #nowMs()} only. Audit lines use wall time, which is not protocol.
 */
public final class ServerClock {

    private static final long BASE_NANOS = System.nanoTime();
    private static final long BASE_MS = System.currentTimeMillis();

    private ServerClock() {}

    public static long nowMs() {
        return BASE_MS + (System.nanoTime() - BASE_NANOS) / 1_000_000L;
    }
}
