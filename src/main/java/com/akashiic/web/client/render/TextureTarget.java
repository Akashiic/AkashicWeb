package com.akashiic.web.client.render;

import java.nio.ByteBuffer;

/**
 * What {@link FrameUploader} and {@link ScreenRenderer} need from a screen texture. {@link ScreenTexture} is the GL
 * implementation; tests substitute a recording one so the upload scheduling (held slots, carried damage, budget) is
 * verified without a GL context.
 */
public interface TextureTarget {

    int width();

    int height();

    boolean isAllocated();

    /** (Re)allocates storage at {@code w x h}; the caller fills it completely next. */
    void allocate(int w, int h);

    /** Uploads rectangle {@code r} of a full-page BGRA canvas with row stride {@code canvasW * 4}. */
    void upload(ByteBuffer canvas, int canvasW, int canvasH, Rect r);

    void bind();

    void delete();
}
