package com.akashiic.web.client.screen;

/**
 * OS-mode audio for one screen (ADR-007): page volume = distance gain x Minecraft Master x Records, pushed to the host
 * at about 10 Hz, with an engine-level hard mute out of range, in menus and on teardown. Muting never waits for
 * smoothing or the rate limit; un-muting fades in from silence. One instance per screen, client thread only. Pure;
 * tested in ScreenAudioTest.
 */
public final class ScreenAudio {

    /** Gain smoothing time constant: fast enough to track walking, slow enough to avoid zipper steps. */
    public static final double SMOOTH_TAU_S = 0.15;
    /** Below this the page is hard-muted rather than set to an inaudible volume. */
    public static final float MIN_AUDIBLE = 0.002f;

    public static final float SEND_EPSILON = 0.01f;
    /** ADR-007: re-applied at about 10 Hz. */
    public static final long MIN_SEND_INTERVAL_MS = 100;

    /** What to send to {@code BrowserHost.audio}. */
    public static final class Command {

        public final boolean muted;
        public final float volume;

        Command(boolean muted, float volume) {
            this.muted = muted;
            this.volume = volume;
        }
    }

    private float smoothed;
    private boolean sentAny;
    private boolean lastMuted = true;
    private float lastVolume;
    private long lastSentMs;

    /** Audible range (blocks): grows with the screen, 16..64. INFERRED tuning, checked at G3. */
    public static double range(int maxDimBlocks) {
        return Math.max(16, Math.min(64, 16 + 2.0 * Math.max(1, maxDimBlocks)));
    }

    /**
     * @param hardMute menus, kill switch, teardown: mute now, whatever the distance
     * @return the command to send now, or null when nothing changed enough
     */
    public Command update(double distance, int maxDimBlocks, float master, float records, boolean hardMute,
        double dtSeconds, long nowMs) {
        float target = hardMute ? 0 : GainModel.screenGain(distance, range(maxDimBlocks), master, records);
        boolean mute = hardMute || target < MIN_AUDIBLE;
        if (mute) {
            smoothed = 0; // the next un-mute fades in from silence
            if (sentAny && lastMuted) return null;
            return remember(true, 0, nowMs);
        }
        smoothed = GainModel.smooth(smoothed, target, dtSeconds, SMOOTH_TAU_S);
        // Snap when close so the curve settles exactly on the target instead of creeping forever.
        if (Math.abs(smoothed - target) < SEND_EPSILON / 4) smoothed = target;
        float volume = Math.max(MIN_AUDIBLE, Math.min(1, smoothed));
        boolean unmuting = !sentAny || lastMuted;
        if (!unmuting) {
            if (volume == lastVolume) return null;
            // Small steps are skipped, except the final one that lands on the target.
            if (Math.abs(volume - lastVolume) < SEND_EPSILON && volume != target) return null;
            if (nowMs - lastSentMs < MIN_SEND_INTERVAL_MS) return null;
        }
        return remember(false, volume, nowMs);
    }

    /** Forces the next update to send (after the host restarted and lost our settings). */
    public void invalidate() {
        sentAny = false;
    }

    public float lastVolume() {
        return lastVolume;
    }

    public boolean lastMuted() {
        return lastMuted;
    }

    private Command remember(boolean muted, float volume, long nowMs) {
        sentAny = true;
        lastMuted = muted;
        lastVolume = volume;
        lastSentMs = nowMs;
        return new Command(muted, volume);
    }
}
