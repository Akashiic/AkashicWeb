package com.akashiic.web.client.render;

/**
 * What to upload from one frame slot, decided before any GL call (ADR-004). Angelica 2.2.20 runs a no-error GL
 * context ({@code use_no_error_g_l_context=true}), where a bad size or offset is undefined behaviour rather than a GL
 * error, so every number that reaches glTexImage2D / glTexSubImage2D is validated here first. Pure; tested in
 * UploadPlanTest.
 */
public final class UploadPlan {

    public enum Kind {
        /** No damage since the last upload. */
        NOTHING,
        /** The slot is from before a resize; the host is already painting at the new size. */
        DROP_SIZE,
        /** Impossible slot (zero/huge size, buffer too small or not direct): never handed to GL. */
        DROP_INVALID,
        /** The texture must be (re)allocated at the slot's size, then filled completely. */
        ALLOCATE,
        /** Damage covers at least half the page: one full upload is cheaper than a strided one. */
        FULL,
        /** Upload only the damage bounding box. */
        PARTIAL
    }

    /** CCBlueX's threshold, adopted by ADR-004. */
    public static final double FULL_UPLOAD_FRACTION = 0.5;

    public final Kind kind;
    /** Region to upload, within the slot (whole page for ALLOCATE/FULL). */
    public final Rect rect;

    private UploadPlan(Kind kind, Rect rect) {
        this.kind = kind;
        this.rect = rect;
    }

    public boolean uploads() {
        return kind == Kind.ALLOCATE || kind == Kind.FULL || kind == Kind.PARTIAL;
    }

    /** Bytes read from the slot by this plan (BGRA, 4 bytes per pixel). */
    public long bytes() {
        return uploads() ? rect.area() * 4L : 0;
    }

    /**
     * @param slotW          page width of the slot
     * @param slotH          page height of the slot
     * @param capacity       slot buffer capacity in bytes
     * @param direct         whether the slot buffer is direct (GL needs a native address)
     * @param damage         damage to upload (the slot's own plus any carried over from skipped frames)
     * @param expectedW      page width we last asked the host for
     * @param expectedH      page height we last asked the host for
     * @param textureW       current texture width (0 when not allocated)
     * @param textureH       current texture height
     * @param maxTextureSize GL_MAX_TEXTURE_SIZE
     */
    public static UploadPlan plan(int slotW, int slotH, long capacity, boolean direct, Rect damage, int expectedW,
        int expectedH, int textureW, int textureH, int maxTextureSize) {
        if (slotW <= 0 || slotH <= 0
            || slotW > maxTextureSize
            || slotH > maxTextureSize
            || !direct
            || capacity < (long) slotW * slotH * 4L) {
            return new UploadPlan(Kind.DROP_INVALID, Rect.EMPTY);
        }
        if (slotW != expectedW || slotH != expectedH) return new UploadPlan(Kind.DROP_SIZE, Rect.EMPTY);
        Rect full = new Rect(0, 0, slotW, slotH);
        if (textureW != slotW || textureH != slotH) return new UploadPlan(Kind.ALLOCATE, full);
        Rect d = damage == null ? Rect.EMPTY : damage.clip(slotW, slotH);
        if (d.isEmpty()) return new UploadPlan(Kind.NOTHING, Rect.EMPTY);
        if (d.area() >= FULL_UPLOAD_FRACTION * full.area()) return new UploadPlan(Kind.FULL, full);
        return new UploadPlan(Kind.PARTIAL, d);
    }

    @Override
    public String toString() {
        return kind + (uploads() ? " " + rect : "");
    }
}
