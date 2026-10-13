package com.akashiic.web.client.host;

/** 3-slot ring: written by the reader thread, read by the render thread (ADR-004). Contract type. */
public interface FrameRing {

    /**
     * Newest complete frame plus the damage accumulated since the last take, or null if nothing new. The caller owns
     * the slot until {@link #release}.
     */
    FrameSlot takeLatest();

    void release(FrameSlot slot);

    /**
     * Gives back a slot that was taken but not uploaded (upload budget spent, texture not ready), so its damage is not
     * lost: the next {@link #takeLatest} covers it again. Added by M2 after v1; the default only releases, which is
     * enough for test fakes but loses damage, so real rings override it.
     */
    default void requeue(FrameSlot slot) {
        release(slot);
    }
}
