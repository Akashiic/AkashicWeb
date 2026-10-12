package com.akashiic.web.client.screen;

/**
 * Click count for in-world clicks, so double-click selects a word and triple-click a line like on a desktop: a click on
 * the same screen within {@link #INTERVAL_MS} and {@link #MAX_DRIFT} (face units) of the previous one counts up to 3.
 * Client thread only. Pure; tested in ClickCounterTest.
 */
public final class ClickCounter {

    public static final long INTERVAL_MS = 500;
    /** About 1% of the screen: hand tremor while aiming with the crosshair. */
    public static final double MAX_DRIFT = 0.01;

    private Object lastScreen;
    private double lastU;
    private double lastV;
    private long lastMs;
    private int count;

    public int click(Object screen, double u, double v, long nowMs) {
        boolean continues = screen != null && screen.equals(lastScreen)
            && nowMs - lastMs <= INTERVAL_MS
            && Math.abs(u - lastU) <= MAX_DRIFT
            && Math.abs(v - lastV) <= MAX_DRIFT;
        count = continues ? Math.min(3, count + 1) : 1;
        lastScreen = screen;
        lastU = u;
        lastV = v;
        lastMs = nowMs;
        return count;
    }
}
