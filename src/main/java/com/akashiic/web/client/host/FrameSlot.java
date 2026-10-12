package com.akashiic.web.client.host;

import java.nio.ByteBuffer;

/** One full page canvas in BGRA plus the region that changed. Contract type. */
public final class FrameSlot {

    /** Direct, native order, width*height*4 bytes, row stride width*4. */
    public final ByteBuffer bgra;

    public final int width;
    public final int height;
    public int damageX;
    public int damageY;
    public int damageW;
    public int damageH;

    public FrameSlot(ByteBuffer bgra, int width, int height) {
        if (bgra.capacity() < width * height * 4) throw new IllegalArgumentException("buffer too small");
        this.bgra = bgra;
        this.width = width;
        this.height = height;
    }

    public boolean hasDamage() {
        return damageW > 0 && damageH > 0;
    }
}
