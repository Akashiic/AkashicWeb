package com.akashiic.web.client.sync;

import java.util.Arrays;
import java.util.Locale;

/**
 * A follower's recent sync error (|target - local position|, seconds) for one screen, as p50/p95 for /awdoctor. The
 * G4 gate (live p95 ≤ 1 s, VOD p95 ≤ 0.3 s) is measured per client until the server can aggregate it: ADR-012 item 6
 * asks for 1 Hz follower telemetry to the server, which needs a new C2S message (M4, protocol). Client thread only.
 * Pure; tested in SyncControllerTest.
 */
public final class SyncStats {

    private final double[] errors;
    private int count;
    private int next;

    /** @param window samples kept: 240 is one minute at the followers' 4 Hz evaluation */
    public SyncStats(int window) {
        this.errors = new double[Math.max(1, window)];
    }

    /** Records one evaluation's error; NaN and infinities (nothing to compare) are skipped. */
    public void add(double errorSeconds) {
        if (Double.isNaN(errorSeconds) || Double.isInfinite(errorSeconds)) return;
        errors[next] = Math.abs(errorSeconds);
        next = (next + 1) % errors.length;
        if (count < errors.length) count++;
    }

    public int count() {
        return count;
    }

    /** The q-quantile (0..1) of the recorded absolute errors in seconds, NaN without samples. */
    public double quantile(double q) {
        if (count == 0) return Double.NaN;
        double[] sorted = Arrays.copyOf(errors, count);
        Arrays.sort(sorted);
        int i = (int) Math.ceil(Math.max(0, Math.min(1, q)) * count) - 1;
        return sorted[Math.max(0, Math.min(count - 1, i))];
    }

    public void reset() {
        count = 0;
        next = 0;
    }

    @Override
    public String toString() {
        if (count == 0) return "err n=0";
        return String
            .format(Locale.ROOT, "err p50=%.0fms p95=%.0fms n=%d", quantile(0.5) * 1000, quantile(0.95) * 1000, count);
    }
}
