package com.akashiic.web.client.sync;

/**
 * One MEDIA sample from this client's own browser (docs/PROTOCOL.md: {t, mediaTime, paused, rate, duration, live,
 * behindLiveEdge, adShowing, fullscreen}). The host's {@code t} is on the host's clock and is not used; instead the
 * reader thread stamps arrival on our monotonic clock ({@link #receivedMs}) and, when the clock is synced, on the
 * server clock ({@link #serverStampMs}, NaN otherwise). IPC latency is loopback (well under 1 ms). Immutable.
 */
public final class LocalMedia {

    public final double mediaTime;
    public final boolean paused;
    public final float rate;
    /** Seconds; NaN or infinite for lives. */
    public final double duration;

    public final boolean live;
    /** Seconds behind the live edge (0 for VOD). */
    public final double behindLiveEdge;

    public final boolean adShowing;
    public final boolean fullscreen;
    public final long receivedMs;
    public final double serverStampMs;

    public LocalMedia(double mediaTime, boolean paused, float rate, double duration, boolean live,
        double behindLiveEdge, boolean adShowing, boolean fullscreen, long receivedMs, double serverStampMs) {
        this.mediaTime = mediaTime;
        this.paused = paused;
        this.rate = rate;
        this.duration = duration;
        this.live = live;
        this.behindLiveEdge = Math.max(0, behindLiveEdge);
        this.adShowing = adShowing;
        this.fullscreen = fullscreen;
        this.receivedMs = receivedMs;
        this.serverStampMs = serverStampMs;
    }

    /** Position extrapolated to client time {@code nowMs}. */
    public double mediaTimeAt(long nowMs) {
        if (paused) return mediaTime;
        return mediaTime + MediaClock.safeRate(rate) * Math.max(0, nowMs - receivedMs) / 1000.0;
    }

    /** The live edge at {@code nowMs}: it advances in real time whatever our playback rate is. */
    public double liveEdgeAt(long nowMs) {
        return mediaTime + behindLiveEdge + Math.max(0, nowMs - receivedMs) / 1000.0;
    }

    public long ageMs(long nowMs) {
        return nowMs - receivedMs;
    }
}
