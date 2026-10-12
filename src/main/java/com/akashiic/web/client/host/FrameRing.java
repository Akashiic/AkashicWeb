package com.akashiic.web.client.host;

/** 3-slot ring: written by the reader thread, read by the render thread (ADR-004). Contract type. */
public interface FrameRing {

    /**
     * Newest complete frame plus the damage accumulated since the last take, or null if nothing new. The caller owns
     * the slot until {@link #release}.
     */
    FrameSlot takeLatest();

    void release(FrameSlot slot);
}
