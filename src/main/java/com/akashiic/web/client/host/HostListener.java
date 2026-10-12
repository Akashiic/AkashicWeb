package com.akashiic.web.client.host;

/**
 * Host events. Callbacks arrive on the reader thread: implementations must hop to the client thread before touching
 * Minecraft. Contract type.
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
