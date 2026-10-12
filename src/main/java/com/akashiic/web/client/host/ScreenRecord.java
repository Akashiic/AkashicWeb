package com.akashiic.web.client.host;

/**
 * What the game last asked of one screen, kept so the screen can be re-created exactly after a host restart and so
 * commands issued before READY are not lost. Holds state, not a command log: while the host is down for a minute the
 * record stays one object, and replay sends one CREATE plus the latest AUDIO/RENDER instead of every intermediate
 * step. Mutable fields are guarded by {@link BrowserHostImpl}'s lock; the frame pipeline is shared with the reader.
 */
final class ScreenRecord {

    final int id;
    final FramePipeline frames = new FramePipeline();

    final CreateSpec spec;
    int width;
    int height;
    /** Last URL asked for (CREATE's, then every NAVIGATE). */
    String url;

    boolean audioSet;
    boolean muted;
    float volume;

    boolean renderSet;
    int fps;
    boolean painting;

    ScreenRecord(int id, CreateSpec spec, String url) {
        this.id = id;
        this.spec = spec;
        int[] size = HostMessages.clampSize(spec.width, spec.height);
        this.width = size[0];
        this.height = size[1];
        this.url = url;
        this.volume = spec.volume;
        this.fps = spec.fps;
        this.painting = true;
    }

    /**
     * CREATE for (re)creating this screen now: current size and the latest URL, so a restart loads the page once
     * instead of loading CREATE's original URL and then navigating away from it.
     */
    CreateSpec currentSpec() {
        return new CreateSpec(
            width,
            height,
            renderSet ? fps : spec.fps,
            url,
            spec.session,
            spec.audio,
            audioSet ? volume : spec.volume);
    }
}
