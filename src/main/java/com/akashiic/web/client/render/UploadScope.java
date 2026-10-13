package com.akashiic.web.client.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GLContext;

/**
 * GL state around one frame's texture uploads ({@link FrameUploader}), entered before the first upload and always
 * left afterwards. Client (GL) thread only.
 */
interface UploadScope {

    void begin();

    void end();

    UploadScope NONE = new UploadScope() {

        @Override
        public void begin() {}

        @Override
        public void end() {}
    };

    /**
     * ADR-004 upload hygiene: we upload from client memory, so no pixel-unpack buffer may be bound. Another mod (or a
     * renderer) can leave one bound; LWJGL 2 then refuses the upload ("Cannot use Buffers when Pixel Unpack Buffer
     * Object is enabled", a permanent UPLOAD_FAILED), or, if the binding bypassed LWJGL's tracker, GL reads our
     * pointer as a buffer offset, which is undefined in Angelica's no-error context. The binding is saved, cleared and
     * restored once per frame. Angelica caches the query, so it costs nothing there; GL 2.0 contexts have no such
     * binding and are left alone.
     */
    final class UnpackBuffer implements UploadScope {

        private int saved;

        @Override
        public void begin() {
            saved = 0;
            if (!GLContext.getCapabilities().OpenGL21) return;
            saved = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            if (saved != 0) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        }

        @Override
        public void end() {
            if (saved != 0) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, saved);
            saved = 0;
        }
    }
}
