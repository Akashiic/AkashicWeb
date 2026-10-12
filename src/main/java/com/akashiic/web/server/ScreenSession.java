package com.akashiic.web.server;

/**
 * Transient server state of one screen that is not part of its saved record: media clock, URL history and when the
 * controller last sent input (for the idle release). Lives as long as the screen exists; a restart starts fresh.
 */
public final class ScreenSession {

    public final MediaClock clock;
    public final UrlHistory history = new UrlHistory();
    public long lastInputMs;

    public ScreenSession(int epoch) {
        this.clock = new MediaClock(epoch);
    }
}
