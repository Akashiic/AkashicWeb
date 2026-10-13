package com.akashiic.web.server;

/**
 * When the server lets go of a controller lock (D-016). The lock is taken with C2SControl after {@link ViewCheck}
 * and the rights check; it then lives while its holder keeps it alive, and goes as soon as one of these holds:
 * <ul>
 * <li>the holder is offline, in another dimension, or farther than the interaction range plus
 * {@link #REACH_MARGIN} (a margin so walking along the edge of the range does not flap the lock);</li>
 * <li>the holder no longer has the right to control (rights changed, screen locked);</li>
 * <li>idle: no keepalive (C2SControl take, repeated by the controller's client at most every 5 s while it interacts)
 * and no sample of playing media for {@code controllerIdleSeconds}. Watching is not idling: while the holder's page
 * plays (or shows an ad) the lock stays, so a friend cannot grab control in the middle of a video.</li>
 * </ul>
 * Pure; {@link ServerRuntime} checks every held lock once a second.
 */
public final class ControlLock {

    /** Blocks beyond the interaction range before a walking-away controller loses the lock. */
    public static final double REACH_MARGIN = 4;

    public enum Release {
        /** Keep the lock. */
        NONE,
        OFFLINE,
        DIMENSION,
        FAR,
        RIGHTS,
        IDLE
    }

    private ControlLock() {}

    /**
     * @param distance        from the holder's eye to the screen, in blocks (ignored when offline)
     * @param mayControl      the holder still passes the rights check for taking control
     * @param lastKeepaliveMs last accepted take or keepalive (server clock)
     * @param lastPlayingMs   last accepted media sample of playing content or an ad (server clock), 0 if none
     */
    public static Release check(boolean online, boolean sameDimension, double distance, double interactRange,
        boolean mayControl, long nowMs, long lastKeepaliveMs, long lastPlayingMs, int idleSeconds) {
        if (!online) return Release.OFFLINE;
        if (!sameDimension) return Release.DIMENSION;
        if (distance > interactRange + REACH_MARGIN) return Release.FAR;
        if (!mayControl) return Release.RIGHTS;
        long lastActivity = Math.max(lastKeepaliveMs, lastPlayingMs);
        if (nowMs - lastActivity > idleSeconds * 1000L) return Release.IDLE;
        return Release.NONE;
    }
}
