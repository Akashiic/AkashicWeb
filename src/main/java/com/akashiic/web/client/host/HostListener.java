package com.akashiic.web.client.host;

/**
 * Host events. Callbacks arrive on the host reader thread, except {@link #onStatus} (supervisor thread) and the
 * EVENTs generated locally: "blocked-url" for a URL refused before it left the JVM (the caller's thread, or the
 * supervisor thread during a replay). Implementations must hop to the client thread before touching Minecraft. A
 * listener that throws (even an Error) is logged and skipped; it never stops the others or the host. Contract type.
 */
public interface HostListener {

    default void onStatus(HostStatus status) {}

    /** STATE JSON (docs/PROTOCOL.md). */
    default void onState(int screenId, String json) {}

    /** MEDIA JSON sampled at 4 Hz on the controller's screen. */
    default void onMedia(int screenId, String json) {}

    /** EVENT JSON {kind, detail}. */
    default void onEvent(int screenId, String json) {}

    default void onPcm(int screenId, long ptsMicros, int sampleRate, int channels, short[] interleaved, int frames) {}
}
