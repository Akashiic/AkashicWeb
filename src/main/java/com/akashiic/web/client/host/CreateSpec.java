package com.akashiic.web.client.host;

/** CREATE parameters (docs/PROTOCOL.md). Contract type. */
public final class CreateSpec {

    public enum Session {
        SHARED,
        PRIVATE
    }

    public enum Audio {
        /** Chromium plays through the OS; Java steers volume and mute. Baseline. */
        OS,
        /** PCM is captured and played through AkashicFM spatial audio. Stretch goal. */
        SPATIAL
    }

    public final int width;
    public final int height;
    public final int fps;
    public final String url;
    public final Session session;
    public final Audio audio;
    public final float volume;

    public CreateSpec(int width, int height, int fps, String url, Session session, Audio audio, float volume) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.url = url == null ? "" : url;
        this.session = session;
        this.audio = audio;
        this.volume = volume;
    }
}
