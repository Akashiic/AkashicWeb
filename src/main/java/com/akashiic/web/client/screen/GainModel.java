package com.akashiic.web.client.screen;

/**
 * Distance gain curve for screen audio (ADR-007: "follows the AkashicFM GainModel.sourceGain curve, copied with
 * attribution, not linked").
 * <p>
 * Copied from AkashicFM {@code com.akashiic.fm.audio.dsp.GainModel} (same owner), comments translated.
 * {@link #distanceAttenuation} and {@link #smooth} are verbatim. {@link #screenGain} replaces AkashicFM's
 * {@code sourceGain}: here Chromium plays straight to the OS, so Minecraft's Master level is NOT applied by paulscode
 * and must be multiplied in by us (AkashicFM leaves it out to avoid Master squared). Pure; tested in GainModelTest.
 */
public final class GainModel {

    /** Full volume up to this distance (blocks). */
    public static final double NEAR_DISTANCE = 2.0;

    private GainModel() {}

    /**
     * 1 up to {@link #NEAR_DISTANCE}, then falls as (1-x)^2 and reaches exactly 0 at the range. No step: the
     * derivative is also 0 at the range, so walking out of range is not an audible cut.
     */
    public static double distanceAttenuation(double distance, double range) {
        if (!(distance >= 0) || !(range > 0)) return 0;
        if (range <= NEAR_DISTANCE) return distance <= range ? 1 : 0;
        if (distance <= NEAR_DISTANCE) return 1;
        if (distance >= range) return 0;
        double x = (distance - NEAR_DISTANCE) / (range - NEAR_DISTANCE);
        double a = 1 - x;
        return a * a;
    }

    /** Final page volume: distance curve x Minecraft Master x Records, each clamped to 0..1. */
    public static float screenGain(double distance, double range, float masterLevel, float recordsLevel) {
        double g = distanceAttenuation(distance, range) * clamp01(masterLevel) * clamp01(recordsLevel);
        return (float) clamp01(g);
    }

    /**
     * Moves {@code current} toward {@code target} with time constant {@code tauSeconds} (smooths gain changes between
     * ticks, no zipper noise).
     */
    public static float smooth(float current, float target, double dtSeconds, double tauSeconds) {
        if (tauSeconds <= 0 || dtSeconds <= 0) return target;
        double k = 1 - Math.exp(-dtSeconds / tauSeconds);
        return (float) (current + (target - current) * k);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
