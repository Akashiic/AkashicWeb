package com.akashiic.web.client.host;

/** SYNC steering computed by the client from the server media clock (ADR-012). Contract type. */
public final class SyncCommand {

    public final double targetMediaTime;
    /** playbackRate to apply, clamped to 0.95..1.05 by the caller. */
    public final float rate;

    public final boolean paused;
    /** True when the error is too large to steer and the follower must jump. */
    public final boolean seek;

    public SyncCommand(double targetMediaTime, float rate, boolean paused, boolean seek) {
        this.targetMediaTime = targetMediaTime;
        this.rate = rate;
        this.paused = paused;
        this.seek = seek;
    }
}
