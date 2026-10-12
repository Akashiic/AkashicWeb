package com.akashiic.web.client.sync;

import com.akashiic.web.client.host.SyncCommand;

/**
 * Follower steering for one screen (ADR-012 item 5). From the server media clock and our own player's position it
 * decides a {@link SyncCommand} for the host, which applies it to HTMLMediaElement.playbackRate / currentTime.
 * <p>
 * Rules (binding, ADR-012 with amendments):
 * <ul>
 * <li>playbackRate steering within {@link #MIN_RATE}..{@link #MAX_RATE} with a ±40 ms deadband. Hysteresis: steering
 * starts outside ±40 ms and stops inside ±{@link #DEADBAND_RELEASE_S}, so we do not park 39 ms off forever nor chatter
 * on the boundary.</li>
 * <li>VOD: seek only above {@link #VOD_SEEK_S} (1.5 s), at most once per {@link #SEEK_COOLDOWN_MS}.</li>
 * <li>Live: target lag = max in-range lag + {@link #LIVE_MARGIN_S}; the target is therefore never closer than 0.5 s to
 * our own live edge, and the rate is never above 1 within that margin (never forward past the edge).</li>
 * <li>Suspended while our own player shows an ad (rate back to 1, no seek).</li>
 * </ul>
 * One instance per screen, client thread only. Pure: time comes from the caller. Tested in SyncControllerTest.
 */
public final class SyncController {

    public static final double DEADBAND_S = 0.040;
    public static final double DEADBAND_RELEASE_S = 0.010;
    public static final double VOD_SEEK_S = 1.5;
    /** Paused frames show any error, and a seek while paused is cheap. INFERRED tuning. */
    public static final double PAUSED_SEEK_S = 0.25;
    /** Live seeks re-buffer and risk the edge; only for gross errors. INFERRED tuning, measured at the sync spike. */
    public static final double LIVE_SEEK_S = 3.0;

    public static final double LIVE_MARGIN_S = 0.5;
    public static final float MIN_RATE = 0.95f;
    public static final float MAX_RATE = 1.05f;
    /** rate = 1 + error / horizon: a 100 ms error is closed in about 2 s at 1.05. */
    public static final double CORRECTION_HORIZON_S = 2.0;
    /** Rates are quantized so tiny error changes do not produce a stream of SYNC messages. */
    public static final float RATE_STEP = 0.005f;

    public static final long SEEK_COOLDOWN_MS = 5_000;
    public static final long LIVE_SEEK_COOLDOWN_MS = 60_000;
    /** A local sample older than this (host stalled, tab hidden) is not a basis for steering. */
    public static final long LOCAL_STALE_MS = 2_000;

    private boolean steering;
    private boolean hasSeeked;
    private long lastSeekMs;

    /**
     * @param server       latest server clock for the current epoch, or null
     * @param serverNowMs  server time now (NaN while the clock is not synced)
     * @param local        latest sample from our own browser, or null
     * @param nowMs        client monotonic time
     * @param knownMaxLagS largest live lag reported for clients in range (seconds), or NaN when unknown; only the
     *                     controller's lag is known locally today
     */
    public SyncDecision update(MediaClock server, double serverNowMs, LocalMedia local, long nowMs,
        double knownMaxLagS) {
        if (server == null || local == null || Double.isNaN(serverNowMs) || local.ageMs(nowMs) > LOCAL_STALE_MS) {
            steering = false;
            return SyncDecision.WAITING;
        }
        double localNow = local.mediaTimeAt(nowMs);
        if (local.adShowing) {
            // The element is playing the ad's timeline; steering it would distort the ad and our resume point.
            steering = false;
            return new SyncDecision(
                SyncDecision.Kind.AD_SUSPENDED,
                new SyncCommand(localNow, 1.0f, server.paused, false),
                Double.NaN,
                local.live);
        }
        double controllerNow = server.mediaTimeAt(serverNowMs);
        return local.live ? live(server, controllerNow, local, localNow, nowMs, knownMaxLagS)
            : vod(server, controllerNow, local, localNow, nowMs);
    }

    private SyncDecision vod(MediaClock server, double controllerNow, LocalMedia local, double localNow, long nowMs) {
        double target = controllerNow;
        if (local.duration > 0 && !Double.isInfinite(local.duration)) target = Math.min(target, local.duration);
        target = Math.max(0, target);
        double error = target - localNow;
        if (server.paused) return paused(target, error, false);
        if (Math.abs(error) > VOD_SEEK_S && seekAllowed(nowMs, SEEK_COOLDOWN_MS))
            return seek(target, error, nowMs, false);
        return steer(target, error, false, Double.POSITIVE_INFINITY);
    }

    private SyncDecision live(MediaClock server, double controllerNow, LocalMedia local, double localNow, long nowMs,
        double knownMaxLagS) {
        double edge = local.liveEdgeAt(nowMs);
        double targetLag = liveTargetLag(edge - controllerNow, knownMaxLagS);
        double target = edge - targetLag;
        double error = target - localNow;
        if (server.paused) return paused(localNow, 0, true); // follow the pause; never seek on a live
        if (Math.abs(error) > LIVE_SEEK_S && seekAllowed(nowMs, LIVE_SEEK_COOLDOWN_MS)) {
            return seek(target, error, nowMs, true);
        }
        return steer(target, error, true, edge - localNow);
    }

    /**
     * Live rule: every client aims at the same lag behind its own edge, the largest lag in range plus a margin, so no
     * client has to run ahead of what it can buffer. Today the only in-range lag known locally is the controller's,
     * measured against our own edge (assumes comparable currentTime across clients; checked at the sync spike).
     */
    static double liveTargetLag(double controllerLagS, double knownMaxLagS) {
        double maxLag = Double.isNaN(controllerLagS) ? 0 : Math.max(0, controllerLagS);
        if (!Double.isNaN(knownMaxLagS)) maxLag = Math.max(maxLag, knownMaxLagS);
        return maxLag + LIVE_MARGIN_S;
    }

    private SyncDecision paused(double target, double error, boolean live) {
        steering = false;
        boolean seek = !live && Math.abs(error) > PAUSED_SEEK_S;
        return new SyncDecision(SyncDecision.Kind.PAUSED, new SyncCommand(target, 1.0f, true, seek), error, live);
    }

    private SyncDecision seek(double target, double error, long nowMs, boolean live) {
        steering = false;
        hasSeeked = true;
        lastSeekMs = nowMs;
        return new SyncDecision(SyncDecision.Kind.SEEK, new SyncCommand(target, 1.0f, false, true), error, live);
    }

    private SyncDecision steer(double target, double error, boolean live, double behindEdgeS) {
        double abs = Math.abs(error);
        if (steering ? abs <= DEADBAND_RELEASE_S : abs <= DEADBAND_S) {
            steering = false;
            return new SyncDecision(
                SyncDecision.Kind.IN_SYNC,
                new SyncCommand(target, 1.0f, false, false),
                error,
                live);
        }
        steering = true;
        float rate = steeringRate(error);
        // Never run faster than real time inside the live margin: we would hit the edge and stall.
        if (live && rate > 1.0f && behindEdgeS <= LIVE_MARGIN_S) rate = 1.0f;
        return new SyncDecision(SyncDecision.Kind.STEERING, new SyncCommand(target, rate, false, false), error, live);
    }

    /** Proportional rate, clamped to 0.95..1.05 and quantized; never exactly 1 while an error is being steered. */
    static float steeringRate(double errorSeconds) {
        double raw = 1.0 + errorSeconds / CORRECTION_HORIZON_S;
        double clamped = Math.max(MIN_RATE, Math.min(MAX_RATE, raw));
        float q = Math.round(clamped / RATE_STEP) * RATE_STEP;
        if (Math.abs(q - 1.0f) < RATE_STEP / 2) q = errorSeconds > 0 ? 1.0f + RATE_STEP : 1.0f - RATE_STEP;
        return Math.max(MIN_RATE, Math.min(MAX_RATE, q));
    }

    private boolean seekAllowed(long nowMs, long cooldownMs) {
        return !hasSeeked || nowMs - lastSeekMs >= cooldownMs;
    }

    /** New epoch (URL change): forget steering and seek history. */
    public void reset() {
        steering = false;
        hasSeeked = false;
    }

    public boolean isSteering() {
        return steering;
    }
}
