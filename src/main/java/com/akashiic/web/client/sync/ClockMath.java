package com.akashiic.web.client.sync;

import java.util.Arrays;

/**
 * NTP-style clock math. In one exchange the client sends t0 (its clock), the server stamps t1 on receipt and t2 on
 * reply, and the client receives at t3. All values in microseconds.
 * <p>
 * Copied from AkashicFM {@code com.akashiic.fm.audio.relay.ClockMath} (same owner), comments translated and the
 * estimator's lowest-RTT selection rewritten as an index sort (same result). Pure; tested in ClockMathTest.
 */
public final class ClockMath {

    private ClockMath() {}

    /** How far the server clock is ahead of the client clock. */
    public static long offset(long t0, long t1, long t2, long t3) {
        return ((t1 - t0) + (t2 - t3)) / 2;
    }

    /** Network round trip only (the time the server held the reply is removed). */
    public static long rtt(long t0, long t1, long t2, long t3) {
        return (t3 - t0) - (t2 - t1);
    }

    /**
     * Keeps the last {@code window} exchanges and returns the median offset of the {@code best} ones with the lowest
     * RTT: those have the smallest possible error (RTT/2). Thread-safe: the Netty thread adds, the client thread
     * reads.
     */
    public static final class Estimator {

        private final int window;
        private final int best;
        private final long[] offsets;
        private final long[] rtts;
        private int count;
        private int next;

        public Estimator(int window, int best) {
            if (window < 1 || best < 1) throw new IllegalArgumentException("window/best must be >= 1");
            this.window = window;
            this.best = best;
            this.offsets = new long[window];
            this.rtts = new long[window];
        }

        public synchronized void add(long offsetMicros, long rttMicros) {
            if (rttMicros < 0) return; // non-monotonic clock or a forged exchange
            offsets[next] = offsetMicros;
            rtts[next] = rttMicros;
            next = (next + 1) % window;
            if (count < window) count++;
        }

        public synchronized int samples() {
            return count;
        }

        public synchronized boolean ready(int minSamples) {
            return count >= minSamples;
        }

        /** Best offset estimate (µs); 0 without samples. */
        public synchronized long offsetMicros() {
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

        /** Lowest RTT in the window (µs), or -1 without samples. */
        public synchronized long bestRttMicros() {
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
