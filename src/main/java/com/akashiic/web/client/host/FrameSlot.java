package com.akashiic.web.client.host;

import java.nio.ByteBuffer;

/** One full page canvas in BGRA plus the region that changed. Contract type. */
public final class FrameSlot {

    /**
     * Largest width or height of a slot (and of a page, {@link HostMessages#MAX_SIZE}). Pages above it never reach a
     * ring (FrameCanvas skips them), so the render side can size textures from a slot without further checks against
     * this bound; it must still compare with the GPU's own GL_MAX_TEXTURE_SIZE. Added by M2 after v1.
     */
    public static final int MAX_DIMENSION = 4096;

    /** Direct, native order, width*height*4 bytes, row stride width*4. */
    public final ByteBuffer bgra;

    public final int width;
    public final int height;
    public int damageX;
    public int damageY;
    public int damageW;
    public int damageH;

    public FrameSlot(ByteBuffer bgra, int width, int height) {
        if (width < 0 || height < 0 || width > MAX_DIMENSION || height > MAX_DIMENSION)
            throw new IllegalArgumentException("slot size " + width + "x" + height + " outside 0.." + MAX_DIMENSION);
        if (bgra.capacity() < width * height * 4) throw new IllegalArgumentException("buffer too small");
        this.bgra = bgra;
        this.width = width;
        this.height = height;
    }

    public boolean hasDamage() {
        return damageW > 0 && damageH > 0;
    }
}
