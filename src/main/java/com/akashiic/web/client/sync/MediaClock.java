package com.akashiic.web.client.sync;

/**
 * The server's media clock for one screen (S2CMediaClock): the controller's position at a server instant. Immutable.
 */
public final class MediaClock {

    public final int epoch;
    public final double mediaTime;
    public final float rate;
    public final boolean paused;
    public final long serverTimeMs;

    public MediaClock(int epoch, double mediaTime, float rate, boolean paused, long serverTimeMs) {
        this.epoch = epoch;
        this.mediaTime = mediaTime;
        this.rate = rate;
        this.paused = paused;
        this.serverTimeMs = serverTimeMs;
    }

    /**
     * Position extrapolated to {@code serverNowMs}. A server instant older than the stamp (clock estimate moved
     * backwards) never moves the position backwards.
     */
    public double mediaTimeAt(double serverNowMs) {
        if (paused || Double.isNaN(serverNowMs)) return mediaTime;
        double elapsed = Math.max(0, serverNowMs - serverTimeMs) / 1000.0;
        return mediaTime + safeRate(rate) * elapsed;
    }

    static double safeRate(float rate) {
        // A garbage rate from the wire must not fling the target; real players stay within 0.0625..16.
        return rate > 0 && rate <= 16 ? rate : 1.0;
    }
}
