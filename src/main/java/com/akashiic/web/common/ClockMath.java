package com.akashiic.web.common;

import java.util.Arrays;

/**
 * NTP-style clock maths for the C2SClockPing / S2CClockPong exchange. The client sends t0 (its clock), the server
 * answers with its time ts, and the client receives at t3. With a single server stamp the server's hold time counts
 * as network delay; the server answers on the main thread straight from the handler to keep it small (ADR-012
 * amendment: budget about +-25 ms for handler-thread stamping, no Netty mixin).
 * <p>
 * Copied from AkashicFM (same owner) {@code com.akashiic.fm.audio.relay.ClockMath} (four-stamp offset/rtt and the
 * min-RTT median estimator), with the three-stamp form used by this protocol added. Times are in milliseconds.
 */
public final class ClockMath {

    private ClockMath() {}

    /** How far the server clock is ahead of the client's (four stamps: t1 receive, t2 send on the server). */
    public static long offset(long t0, long t1, long t2, long t3) {
        return ((t1 - t0) + (t2 - t3)) / 2;
    }

    /** Network round trip, minus the time the server held the request. */
    public static long rtt(long t0, long t1, long t2, long t3) {
        return (t3 - t0) - (t2 - t1);
    }

    /** Three-stamp form: the server's single stamp ts stands for both receive and send. */
    public static long offset(long t0, long ts, long t3) {
        return offset(t0, ts, ts, t3);
    }

    public static long rtt(long t0, long t3) {
        return t3 - t0;
    }

    /**
     * Keeps the last {@code window} exchanges and returns the median offset of the {@code best} with the lowest RTT
     * (the lowest-RTT exchanges have the smallest possible error, RTT/2). Thread-safe.
     */
    public static final class Estimator {

        private final int window;
        private final int best;
        private final long[] offsets;
        private final long[] rtts;
        private int count;
        private int next;

        public Estimator(int window, int best) {
            this.window = window;
            this.best = best;
            this.offsets = new long[window];
            this.rtts = new long[window];
        }

        public synchronized void add(long offset, long rtt) {
            if (rtt < 0) return; // non-monotonic clock or a bogus exchange
            offsets[next] = offset;
            rtts[next] = rtt;
            next = (next + 1) % window;
            if (count < window) count++;
        }

        public synchronized int samples() {
            return count;
        }

        public synchronized boolean ready(int minSamples) {
            return count >= minSamples;
        }

        /** Best offset estimate; 0 without samples. */
        public synchronized long offset() {
            if (count == 0) return 0;
            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> Long.compare(rtts[a], rtts[b]));
            int k = Math.min(best, count);
            long[] chosen = new long[k];
            for (int i = 0; i < k; i++) chosen[i] = offsets[order[i]];
            Arrays.sort(chosen);
            return k % 2 == 1 ? chosen[k / 2] : (chosen[k / 2 - 1] + chosen[k / 2]) / 2;
        }

        /** Lowest RTT in the window, or -1 without samples. */
        public synchronized long bestRtt() {
            if (count == 0) return -1;
            long m = Long.MAX_VALUE;
            for (int i = 0; i < count; i++) m = Math.min(m, rtts[i]);
            return m;
        }

        public synchronized void clear() {
            count = 0;
            next = 0;
        }
    }
}
