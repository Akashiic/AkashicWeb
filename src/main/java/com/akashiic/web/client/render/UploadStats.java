package com.akashiic.web.client.render;

import java.util.Arrays;

/**
 * Upload instrumentation from day 1 (ADR-004 "INSTRUMENTATION", shown in /web doctor): render-thread time per upload
 * (p50/p95/p99 over the last {@link #WINDOW} uploads), bytes per second over the last second, and counters for frames
 * taken, uploaded, skipped by the budget and dropped. The G3 pass criteria (p95 <= 1.0 ms, p99 <= 2.0 ms at 720p30)
 * are read from here. Render thread writes; the doctor reads a {@link #snapshot}. Pure; tested in UploadStatsTest.
 */
public final class UploadStats {

    public static final int WINDOW = 1024;
    private static final int BUCKETS = 10;
    private static final long BUCKET_MS = 100;

    private final long[] durationsNs = new long[WINDOW];
    private int count;
    private int next;
    private final long[] bucketStart = new long[BUCKETS];
    private final long[] bucketBytes = new long[BUCKETS];

    private long taken;
    private long uploaded;
    private long skippedBudget;
    private long droppedSize;
    private long droppedInvalid;
    private long failures;
    private long bytesTotal;

    public UploadStats() {
        Arrays.fill(bucketStart, Long.MIN_VALUE);
    }

    public synchronized void recordUpload(long durationNs, long bytes, long nowMs) {
        durationsNs[next] = Math.max(0, durationNs);
        next = (next + 1) % WINDOW;
        if (count < WINDOW) count++;
        uploaded++;
        bytesTotal += bytes;
        long start = nowMs - Math.floorMod(nowMs, BUCKET_MS);
        int i = (int) Math.floorMod(start / BUCKET_MS, (long) BUCKETS);
        if (bucketStart[i] != start) {
            bucketStart[i] = start;
            bucketBytes[i] = 0;
        }
        bucketBytes[i] += bytes;
    }

    public synchronized void recordTaken() {
        taken++;
    }

    public synchronized void recordSkippedBudget() {
        skippedBudget++;
    }

    public synchronized void recordDroppedSize() {
        droppedSize++;
    }

    public synchronized void recordDroppedInvalid() {
        droppedInvalid++;
    }

    public synchronized void recordFailure() {
        failures++;
    }

    /** Nearest-rank percentile of the upload durations (ns); 0 without samples. */
    public synchronized long percentileNs(double p) {
        if (count == 0) return 0;
        long[] s = Arrays.copyOf(durationsNs, count);
        Arrays.sort(s);
        int rank = (int) Math.ceil(Math.max(0, Math.min(100, p)) / 100.0 * count);
        return s[Math.max(0, Math.min(count - 1, rank - 1))];
    }

    /** Bytes uploaded during the second ending at {@code nowMs}. */
    public synchronized long bytesPerSecond(long nowMs) {
        long sum = 0;
        for (int i = 0; i < BUCKETS; i++) {
            if (bucketStart[i] != Long.MIN_VALUE && bucketStart[i] > nowMs - BUCKETS * BUCKET_MS
                && bucketStart[i] <= nowMs) sum += bucketBytes[i];
        }
        return sum;
    }

    /** One line for /web doctor. */
    public synchronized String snapshot(long nowMs) {
        return String.format(
            java.util.Locale.ROOT,
            "upload p50=%.2fms p95=%.2fms p99=%.2fms (n=%d) %.1f MB/s | taken=%d uploaded=%d skipped(budget)=%d"
                + " dropped(size)=%d dropped(invalid)=%d failures=%d total=%.1f MB",
            percentileNs(50) / 1e6,
            percentileNs(95) / 1e6,
            percentileNs(99) / 1e6,
            count,
            bytesPerSecond(nowMs) / 1e6,
            taken,
            uploaded,
            skippedBudget,
            droppedSize,
            droppedInvalid,
            failures,
            bytesTotal / 1e6);
    }

    public synchronized long uploaded() {
        return uploaded;
    }

    public synchronized long skippedBudget() {
        return skippedBudget;
    }

    public synchronized long dropped() {
        return droppedSize + droppedInvalid;
    }

    public synchronized long failures() {
        return failures;
    }
}
