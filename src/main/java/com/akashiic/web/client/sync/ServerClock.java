package com.akashiic.web.client.sync;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The server clock as seen by this client (ADR-012 item 3). Same schedule as AkashicFM's {@code ClockSync}: ping every
 * 250 ms until 4 exchanges are in, then every 5 s; the estimate is the median offset of the 8 lowest-RTT exchanges out
 * of the last 16 (error bounded by RTT/2 of the best one).
 * <p>
 * The pong carries one server stamp (S2CClockPong has {@code serverTimeMs} only), so t1 = t2. Arrival is stamped by
 * {@code ClientNetHooks} on the Netty thread, before hopping to the client thread, so the tick-queue delay (0-50 ms)
 * does not bias the offset; that replaces AkashicFM's Netty mixin, which ADR-009 forbids. The server's own stamp is
 * taken on its handler thread, so a bias of about ±25 ms remains (ADR-012 reviewer amendment).
 * <p>
 * Times are milliseconds of a monotonic client clock supplied by the caller, which keeps this class pure and
 * testable. Thread-safe.
 */
public final class ServerClock {

    static final long FAST_INTERVAL_MS = 250;
    static final long SLOW_INTERVAL_MS = 5_000;
    /** Fast pings until this many exchanges are in. */
    static final int FAST_UNTIL_SAMPLES = 4;
    static final int MIN_SAMPLES = 3;
    /** Pings older than this are forgotten (lost packet); their pong would be ignored anyway. */
    static final long OUTSTANDING_TTL_MS = 10_000;

    static final int MAX_OUTSTANDING = 8;

    private final ClockMath.Estimator estimator = new ClockMath.Estimator(16, 8);
    /** nonce -> client send time. Only pongs answering one of our own pings are accepted. */
    private final Map<Long, Long> outstanding = new LinkedHashMap<>();
    private long nextNonce;
    private long lastPingMs;
    private boolean pinged;

    public ServerClock(long nonceSeed) {
        // Non-zero, positive base so nonces never collide with 0 ("no ping") within a session.
        this.nextNonce = (nonceSeed & 0x3FFF_FFFF_FFFF_FFFFL) | 1L;
    }

    /**
     * Client thread, every tick. Returns the nonce to send in C2SClockPing (with {@code nowMs} as clientSendMs), or 0
     * when no ping is due.
     */
    public synchronized long pollPing(long nowMs) {
        long interval = estimator.samples() < FAST_UNTIL_SAMPLES ? FAST_INTERVAL_MS : SLOW_INTERVAL_MS;
        if (pinged && nowMs - lastPingMs < interval) return 0;
        pinged = true;
        lastPingMs = nowMs;
        expireOutstanding(nowMs);
        if (outstanding.size() >= MAX_OUTSTANDING) {
            Iterator<Long> it = outstanding.keySet()
                .iterator();
            it.next();
            it.remove();
        }
        long nonce = nextNonce++;
        outstanding.put(nonce, nowMs);
        return nonce;
    }

    /**
     * Netty thread. {@code arrivalMs} must be stamped on arrival. Returns false (and ignores the pong) when it does not
     * answer an outstanding ping of ours or echoes a different send time.
     */
    public synchronized boolean onPong(long nonce, long clientSendMs, long serverTimeMs, long arrivalMs) {
        Long sent = outstanding.remove(nonce);
        if (sent == null || sent != clientSendMs) return false;
        long t0 = clientSendMs * 1000L;
        long t1 = serverTimeMs * 1000L;
        long t3 = arrivalMs * 1000L;
        long rtt = ClockMath.rtt(t0, t1, t1, t3);
        if (rtt < 0) return false;
        estimator.add(ClockMath.offset(t0, t1, t1, t3), rtt);
        return true;
    }

    public boolean ready() {
        return estimator.ready(MIN_SAMPLES);
    }

    /** Server time (ms, with fraction) at client time {@code nowMs}; NaN until {@link #ready()}. */
    public double serverNowMs(long nowMs) {
        if (!ready()) return Double.NaN;
        return nowMs + estimator.offsetMicros() / 1000.0;
    }

    /** Estimated server-minus-client offset (ms), for /web doctor. */
    public double offsetMs() {
        return estimator.offsetMicros() / 1000.0;
    }

    /** Lowest RTT seen (ms), or -1 without samples. */
    public double bestRttMs() {
        long r = estimator.bestRttMicros();
        return r < 0 ? -1 : r / 1000.0;
    }

    public int samples() {
        return estimator.samples();
    }

    /** On disconnect: the next server has a different clock. */
    public synchronized void reset() {
        estimator.clear();
        outstanding.clear();
        pinged = false;
    }

    private void expireOutstanding(long nowMs) {
        Iterator<Map.Entry<Long, Long>> it = outstanding.entrySet()
            .iterator();
        while (it.hasNext()) {
            if (nowMs - it.next()
                .getValue() > OUTSTANDING_TTL_MS) it.remove();
        }
    }
}
