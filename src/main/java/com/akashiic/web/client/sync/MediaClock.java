package com.akashiic.web.client.sync;

/**
 * The server's media clock for one screen (S2CMediaClock): the controller's position at a server instant, and whether
 * the controller plays a live stream and how far behind its live edge (D-016 fields). Immutable.
 */
public final class MediaClock {

    /** liveLagMs is clamped by M4 to 0..3,600,000; anything else from the wire counts as unknown. */
    static final int MAX_LIVE_LAG_MS = 3_600_000;

    public final int epoch;
    public final double mediaTime;
    public final float rate;
    public final boolean paused;
    public final long serverTimeMs;
    /** The controller's page plays a live stream. */
    public final boolean live;
    /** How far the controller is behind its own live edge (ms); 0 for VOD. */
    public final int liveLagMs;

    public MediaClock(int epoch, double mediaTime, float rate, boolean paused, long serverTimeMs) {
        this(epoch, mediaTime, rate, paused, serverTimeMs, false, 0);
    }

    public MediaClock(int epoch, double mediaTime, float rate, boolean paused, long serverTimeMs, boolean live,
        int liveLagMs) {
        this.epoch = epoch;
        this.mediaTime = mediaTime;
        this.rate = rate;
        this.paused = paused;
        this.serverTimeMs = serverTimeMs;
        this.live = live;
        this.liveLagMs = liveLagMs;
    }

    /** The controller's lag behind its live edge in seconds, or NaN when not live or out of range. */
    public double liveLagSeconds() {
        if (!live || liveLagMs < 0 || liveLagMs > MAX_LIVE_LAG_MS) return Double.NaN;
        return liveLagMs / 1000.0;
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
