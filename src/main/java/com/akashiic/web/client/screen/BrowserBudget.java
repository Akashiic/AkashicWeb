package com.akashiic.web.client.screen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-client browser budget (ADR-012 item 7): at most 2 live browsers on machines with 8 GB of RAM or less, 4
 * otherwise, given to the screens with the best size x proximity. The rest keep their last frame and show
 * "aproxime-se" ({@code BROWSER_BUDGET}). Each Chromium renderer costs roughly 150-400 MB (INFERRED), which is what
 * the cap protects on the pack's low-end 8 GB machines. Pure; tested in BrowserBudgetTest.
 */
public final class BrowserBudget {

    public static final int LOW_RAM_BROWSERS = 2;
    public static final int DEFAULT_BROWSERS = 4;
    /**
     * "8 GB" machines report a little under 8 GiB of physical memory (firmware and iGPU reservations); the threshold
     * sits between that and the next common size (12 GB).
     */
    public static final long LOW_RAM_THRESHOLD_BYTES = 8L * 1024 * 1024 * 1024 + 512L * 1024 * 1024;
    /** A screen that already has a browser keeps it unless a rival scores clearly better (no flapping). */
    public static final double STICKINESS = 1.25;

    private BrowserBudget() {}

    /** Budget for a machine; unknown memory (<= 0) gets the default. */
    public static int maxBrowsers(long totalPhysicalBytes) {
        if (totalPhysicalBytes <= 0) return DEFAULT_BROWSERS;
        return totalPhysicalBytes <= LOW_RAM_THRESHOLD_BYTES ? LOW_RAM_BROWSERS : DEFAULT_BROWSERS;
    }

    /** Size x proximity: picture area in blocks over (1 + distance). */
    public static double score(int widthBlocks, int heightBlocks, double distance) {
        double area = Math.max(1, widthBlocks) * (double) Math.max(1, heightBlocks);
        return area / (1 + Math.max(0, distance));
    }

    /** One screen competing for a browser. */
    public static final class Candidate<K> {

        final K key;
        final double score;
        final double distance;
        final boolean eligible;
        final boolean hasBrowser;

        public Candidate(K key, double score, double distance, double createRange, double keepRange,
            boolean hasBrowser) {
            this.key = key;
            this.score = hasBrowser ? score * STICKINESS : score;
            this.distance = distance;
            this.hasBrowser = hasBrowser;
            this.eligible = distance <= createRange || (hasBrowser && distance <= keepRange);
        }
    }

    /** Keys granted a browser, best first. Ties go to the closer screen, then to the one that already has one. */
    public static <K> List<K> select(List<Candidate<K>> candidates, int max) {
        List<Candidate<K>> eligible = new ArrayList<>();
        for (Candidate<K> c : candidates) if (c.eligible) eligible.add(c);
        Collections.sort(eligible, (a, b) -> {
            int byScore = Double.compare(b.score, a.score);
            if (byScore != 0) return byScore;
            int byDistance = Double.compare(a.distance, b.distance);
            if (byDistance != 0) return byDistance;
            return Boolean.compare(b.hasBrowser, a.hasBrowser);
        });
        List<K> granted = new ArrayList<>();
        for (int i = 0; i < eligible.size() && granted.size() < Math.max(0, max); i++) granted.add(eligible.get(i).key);
        return granted;
    }
}
