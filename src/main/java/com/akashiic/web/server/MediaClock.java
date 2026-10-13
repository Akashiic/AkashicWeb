package com.akashiic.web.server;

import com.akashiic.web.common.net.MediaSample;
import com.akashiic.web.common.net.S2CMediaClock;

/**
 * The server's media clock for one screen (ADR-012 item 4): anchored on the controller's last good sample and
 * extrapolated from there, so followers keep converging when the controller lags, leaves or disconnects.
 * <p>
 * The anchor time is the client's own estimate of server time when it sampled ({@link MediaSample#clientServerTimeMs}),
 * not the time this server tick handled the packet: re-stamping on the tick thread would add 0-50 ms of queue
 * jitter. Stamps that are implausible (from the future or very old, i.e. the client's clock sync is broken) are
 * replaced by the receive time and counted. Every time here is on the one server clock, {@link ServerClock}: the
 * same clock the client synced against with C2SClockPing/S2CClockPong and the one S2CMediaClock carries.
 * <p>
 * Ads (D-016): while the controller's samples report an ad, the clock is held where the content stopped (it reads as
 * paused there), and the next sample of the content re-anchors it. Followers therefore wait for the controller's ad
 * instead of running ahead of it. Pure logic, main thread only.
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
        /**
         * The controller is watching an ad: its position says nothing about the content (ADR-012 item 5), so the
         * clock holds until the next sample of the content.
         */
        HELD_FOR_AD,
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
    /** An ad is showing on the controller's page: the clock stands still at {@link #mediaTime}. */
    private boolean held;
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
        long stamp = s.clientServerTimeMs;
        if (stamp > nowMs + MAX_FUTURE_MS || stamp < nowMs - MAX_AGE_MS) {
            stamp = nowMs;
            restamped++;
        }
        if (valid && stamp < anchorMs) return Result.IGNORED_OLD;
        if (s.adShowing) {
            // Hold where the content stopped: the position extrapolated to the first ad sample (at most one sample
            // interval late). Before any content sample there is nothing to hold.
            if (valid && !held) {
                mediaTime = positionAt(stamp);
                anchorMs = stamp;
                held = true;
            }
            return Result.HELD_FOR_AD;
        }
        valid = true;
        held = false;
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
        if (paused || held) return mediaTime;
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
        held = false;
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

    /** Paused by the controller, or held for an ad: followers stand still either way. */
    public boolean paused() {
        return paused || held;
    }

    /** Held because the controller's page shows an ad. */
    public boolean heldForAd() {
        return held;
    }

    public boolean live() {
        return live;
    }

    public double behindLiveEdge() {
        return behindLiveEdge;
    }

    /** The controller's distance behind the live edge in ms for S2CMediaClock; 0 for VOD. */
    public int liveLagMs() {
        if (!live) return 0;
        return (int) Math.max(0, Math.min(S2CMediaClock.MAX_LIVE_LAG_MS, Math.round(behindLiveEdge * 1000.0)));
    }

    public long anchorMs() {
        return anchorMs;
    }

    /** Samples whose stamp had to be replaced (diagnostics: a client with broken clock sync). */
    public long restamped() {
        return restamped;
    }
}
