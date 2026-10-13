package com.akashiic.web.client.host;

import com.akashiic.web.common.SitePolicy;

/**
 * The game's view of the AkashicWebHost process (docs/CONTRACTS.md, M2→M5). Every method except {@link #shutdown()}
 * is non-blocking and safe from the client thread; shutdown blocks for up to about 3.5 s and is meant for game exit.
 * Screens with their latest URL, size, audio and render settings survive while the host is not READY and are
 * re-created on every (re)connect; transient commands (input, back/forward/reload/stop, SYNC) are dropped while not
 * READY. URLs are checked against the site policy and the internal-address block before they leave the JVM; a refused
 * URL produces a local "blocked-url" EVENT instead. Contract type.
 */
public interface BrowserHost {

    HostStatus status();

    /** Returns a new screen id (> 0). */
    int createScreen(CreateSpec spec);

    void destroyScreen(int screenId);

    void navigate(int screenId, String url);

    /** back, forward, reload or stop. */
    void action(int screenId, String action);

    void input(int screenId, InputEvent event);

    void audio(int screenId, boolean muted, float volume);

    void render(int screenId, int fps, boolean painting);

    void resize(int screenId, int width, int height);

    void sync(int screenId, SyncCommand cmd);

    void policy(SitePolicy policy);

    /** Frame ring of a screen, or null if the id is unknown. */
    FrameRing frames(int screenId);

    void addListener(HostListener listener);

    void removeListener(HostListener listener);

    void shutdown();

    /**
     * Leaves DISABLED (crash loop, protocol mismatch, engine missing at launch) and launches now; also cuts a running
     * relaunch backoff short. For the "try again" button and after the engine install finishes. Added by M2 after v1.
     */
    default void reenable() {}

    /** Lines for /web doctor (status, versions, failures, per-screen frame counters). Added by M2 after v1. */
    default java.util.List<String> diagnostics() {
        return java.util.Collections.emptyList();
    }
}
