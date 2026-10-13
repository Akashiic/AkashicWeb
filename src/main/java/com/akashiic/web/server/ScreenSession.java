package com.akashiic.web.server;

/**
 * Transient server state of one screen that is not part of its saved record: media clock, URL history and the
 * controller lock's activity (for {@link ControlLock}). Lives as long as the screen exists; a restart starts fresh.
 * Times are on the server clock ({@link ServerClock}).
 */
public final class ScreenSession {

    public final MediaClock clock;
    public final UrlHistory history = new UrlHistory();
    /** Last accepted C2SControl take or keepalive from the controller, or the last controller change. */
    public long lastKeepaliveMs;
    /** Last accepted controller sample while its media played or an ad showed; 0 if none. */
    public long lastPlayingMs;

    public ScreenSession(int epoch) {
        this.clock = new MediaClock(epoch);
    }
}
