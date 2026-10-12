package com.akashiic.web.client.render;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * One GL texture per screen (ADR-004 "TEXTURE" and "UPLOAD HYGIENE"). Every call goes through our own GL11/GL12
 * references so Angelica's transformer redirects them into its state manager; nothing here assumes Angelica tracks
 * unpack state, so it is reset after every upload. Angelica 2.2.20 targets a GL 3.3+ core profile and suspends a bound
 * PIXEL_UNPACK_BUFFER itself, so the GL 2.1 branch of ADR-004 is not needed (ADR-005 reviewer amendment).
 * <p>
 * Client (GL) thread only. Sizes are validated by {@link UploadPlan} and re-checked here before any GL call, because
 * the pack runs a no-error context where a bad call is undefined behaviour.
 */
public final class ScreenTexture implements TextureTarget {

    private static int maxTextureSize;

    private int id;
    private int width;
    private int height;

    public int id() {
        return id;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public boolean isAllocated() {
        return id != 0 && width > 0 && height > 0;
    }

    /** GL_MAX_TEXTURE_SIZE, queried once (GL thread). */
    public static int maxTextureSize() {
        if (maxTextureSize <= 0) {
            int v = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            // Every GL 3.3 driver guarantees 1024; treat a nonsense answer as that floor rather than trusting it.
            maxTextureSize = v >= 1024 ? v : 1024;
        }
        return maxTextureSize;
    }

    /**
     * (Re)allocates the storage at {@code w x h} as RGBA8 filled from BGRA 8_8_8_8_REV (no swizzle under any Angelica
     * backend, ADR-004). Linear filtering, clamp-to-edge (GL_CLAMP is gone in core profile), a single mip level.
     */
    @Override
    public void allocate(int w, int h) {
        if (w <= 0 || h <= 0 || w > maxTextureSize() || h > maxTextureSize())
            throw new IllegalArgumentException("texture size " + w + "x" + h);
        if (id == 0) id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA8,
            w,
            h,
            0,
            GL12.GL_BGRA,
            GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
            (ByteBuffer) null);
        width = w;
        height = h;
    }

    /**
     * Uploads {@code r} of a full-page canvas whose rows are {@code canvasW * 4} bytes apart. ROW_LENGTH and the SKIP
     * values select the rectangle inside the canvas; they are reset to 0 afterwards even if GL throws.
     */
    @Override
    public void upload(ByteBuffer canvas, int canvasW, int canvasH, Rect r) {
        if (!isAllocated() || canvasW != width
            || canvasH != height
            || !r.within(width, height)
            || !canvas.isDirect()
            || canvas.capacity() < (long) canvasW * canvasH * 4L) {
            throw new IllegalStateException(
                "upload " + r + " of " + canvasW + "x" + canvasH + " into " + width + "x" + height);
        }
        // Own view at position 0: LWJGL checks remaining() against w*h*4 and GL applies the SKIP offsets from here.
        ByteBuffer pixels = canvas.duplicate();
        pixels.clear();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, canvasW);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, r.x);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, r.y);
        try {
            GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                r.x,
                r.y,
                r.w,
                r.h,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                pixels);
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        }
    }

    @Override
    public void bind() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
    }

    @Override
    public void delete() {
        if (id != 0) GL11.glDeleteTextures(id);
        id = 0;
        width = 0;
        height = 0;
    }
}
