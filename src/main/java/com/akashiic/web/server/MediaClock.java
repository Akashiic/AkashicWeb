package com.akashiic.web.server;

import com.akashiic.web.common.net.MediaSample;

/**
 * The server's media clock for one screen (ADR-012 item 4): anchored on the controller's last good sample and
 * extrapolated from there, so followers keep converging when the controller lags, leaves or disconnects.
 * <p>
 * The anchor time is the client's own estimate of server time when it sampled ({@link MediaSample#clientServerTimeMs}),
 * not the time this server tick handled the packet: re-stamping on the tick thread would add 0-50 ms of queue
 * jitter. Stamps that are implausible (from the future or very old, i.e. the client's clock sync is broken) are
 * replaced by the receive time and counted. Pure logic, main thread only.
 */
public final class MediaClock {

    /** A stamp may be this far in the future (clock-sync error) before it is distrusted. */
    static final long MAX_FUTURE_MS = 250;
    /** A sample older than this on arrival is distrusted (and the clock re-anchored at receive time). */
    static final long MAX_AGE_MS = 5_000;
    static final double MAX_MEDIA_TIME = 10_000_000; // ~115 days
    static final float MAX_RATE = 16f;

    public enum Result {
        APPLIED,
        /** The controller is watching an ad: its position says nothing about the content (ADR-012 item 5). */
        IGNORED_AD,
        /** Older than the current anchor (reordered or duplicated). */
        IGNORED_OLD,
        STALE_EPOCH,
        INVALID
    }

    private int epoch;
    private boolean valid;
    private double mediaTime;
    private float rate = 1f;
    private boolean paused;
    private long anchorMs;
    private boolean live;
    private double behindLiveEdge;
    private long restamped;

    public MediaClock(int epoch) {
        this.epoch = epoch;
    }

    public Result apply(MediaSample s, int currentEpoch, long nowMs) {
        if (s.epoch != currentEpoch) return Result.STALE_EPOCH;
        if (s.mediaTime < 0 || s.mediaTime > MAX_MEDIA_TIME
            || s.rate < 0
            || s.rate > MAX_RATE
            || s.behindLiveEdge < 0
            || s.behindLiveEdge > MAX_MEDIA_TIME) return Result.INVALID;
        if (currentEpoch != epoch) reset(currentEpoch);
        if (s.adShowing) return Result.IGNORED_AD;
        long stamp = s.clientServerTimeMs;
        if (stamp > nowMs + MAX_FUTURE_MS || stamp < nowMs - MAX_AGE_MS) {
            stamp = nowMs;
            restamped++;
        }
        if (valid && stamp < anchorMs) return Result.IGNORED_OLD;
        valid = true;
        mediaTime = s.mediaTime;
        rate = s.rate;
        paused = s.paused;
        anchorMs = stamp;
        live = s.live;
        behindLiveEdge = s.behindLiveEdge;
        return Result.APPLIED;
    }

    /** Media position (seconds) at a server time; NaN before the first good sample of this epoch. */
    public double positionAt(long serverMs) {
        if (!valid) return Double.NaN;
        if (paused) return mediaTime;
        return Math.max(0, mediaTime + rate * (serverMs - anchorMs) / 1000.0);
    }

    /** New URL: forget everything until the controller samples the new page. */
    public void reset(int newEpoch) {
        epoch = newEpoch;
        valid = false;
        mediaTime = 0;
        rate = 1f;
        paused = false;
        anchorMs = 0;
        live = false;
        behindLiveEdge = 0;
    }

    public boolean valid() {
        return valid;
    }

    public int epoch() {
        return epoch;
    }

    public float rate() {
        return rate;
    }

    public boolean paused() {
        return paused;
    }

    public boolean live() {
        return live;
    }

    public double behindLiveEdge() {
        return behindLiveEdge;
    }

    public long anchorMs() {
        return anchorMs;
    }

    /** Samples whose stamp had to be replaced (diagnostics: a client with broken clock sync). */
    public long restamped() {
        return restamped;
    }
}
