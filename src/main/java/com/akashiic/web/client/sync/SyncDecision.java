package com.akashiic.web.client.sync;

import java.util.Locale;

import com.akashiic.web.client.host.SyncCommand;

/** Outcome of one follower evaluation: what the controller decided and the command for the host (or none). */
public final class SyncDecision {

    public enum Kind {
        /** Missing server clock, server time or a fresh local sample: nothing is sent. */
        WAITING,
        /** Our own player shows an ad: steering suspended, rate back to 1. */
        AD_SUSPENDED,
        /** The server clock is paused: follow the pause, align the frame. */
        PAUSED,
        /** Inside the deadband: rate 1. */
        IN_SYNC,
        /** Error corrected with playbackRate within 0.95..1.05. */
        STEERING,
        /** Error too large to steer: jump to the target. */
        SEEK,
        /** The controller plays a live and our player knows no live edge yet: rate 1, never a seek. */
        LIVE_EDGE_UNKNOWN
    }

    public static final SyncDecision WAITING = new SyncDecision(Kind.WAITING, null, Double.NaN, false);

    public final Kind kind;
    /** Null for {@link Kind#WAITING}. */
    public final SyncCommand command;
    /** Target minus local position (s); positive means we are behind. NaN when unknown. */
    public final double errorSeconds;

    public final boolean live;

    public SyncDecision(Kind kind, SyncCommand command, double errorSeconds, boolean live) {
        this.kind = kind;
        this.command = command;
        this.errorSeconds = errorSeconds;
        this.live = live;
    }

    @Override
    public String toString() {
        return kind + (Double.isNaN(errorSeconds) ? "" : String.format(Locale.ROOT, " err=%+.3fs", errorSeconds))
            + (command == null ? "" : String.format(Locale.ROOT, " rate=%.3f", command.rate))
            + (live ? " live" : "");
    }
}
