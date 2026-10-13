package com.akashiic.web.client.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-render-frame upload budget (ADR-004 "BUDGET"): about 8.3 MB, one 1080p frame, so the worst-case render-thread
 * cost stays predictable however many screens are painting. Screens are served nearest/largest first; a screen that
 * does not fit keeps its damage and gains priority each frame it waits, so a far screen is delayed, never starved.
 * Render thread only. Pure; tested in UploadBudgetTest.
 */
public final class UploadBudget {

    /** One 1920x1080 BGRA frame. */
    public static final long DEFAULT_BYTES_PER_FRAME = 1920L * 1080L * 4L;

    private final long bytesPerFrame;
    private long used;
    private int uploads;

    public UploadBudget(long bytesPerFrame) {
        this.bytesPerFrame = bytesPerFrame;
    }

    public void beginFrame() {
        used = 0;
        uploads = 0;
    }

    /**
     * Reserves {@code bytes} if they fit. The first upload of a frame always fits, so a single page larger than the
     * budget (a 4K screen) still updates.
     */
    public boolean tryConsume(long bytes) {
        if (uploads > 0 && used + bytes > bytesPerFrame) return false;
        used += bytes;
        uploads++;
        return true;
    }

    public long used() {
        return used;
    }

    /** A screen waiting for upload: its base priority (size x proximity) and how many frames it has been skipped. */
    public static final class Entry<K> {

        final K key;
        final double effectivePriority;

        public Entry(K key, double priority, int skippedFrames) {
            this.key = key;
            this.effectivePriority = Math.max(0, priority) * (1 + Math.max(0, skippedFrames));
        }
    }

    /** Upload order: highest priority first, aged by skipped frames. Stable for equal priorities. */
    public static <K> List<K> order(List<Entry<K>> entries) {
        List<Entry<K>> sorted = new ArrayList<>(entries);
        Collections.sort(sorted, (a, b) -> Double.compare(b.effectivePriority, a.effectivePriority));
        List<K> keys = new ArrayList<>(sorted.size());
        for (Entry<K> e : sorted) keys.add(e.key);
        return keys;
    }
}
