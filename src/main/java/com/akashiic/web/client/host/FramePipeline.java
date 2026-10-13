package com.akashiic.web.client.host;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Per-screen frame path on the reader thread: FRAME crop, canvas, ring (ADR-004). The ring outlives host restarts so
 * the render side keeps one stable reference per screen.
 */
public final class FramePipeline {

    private final FrameCanvas canvas = new FrameCanvas();
    private final FrameRing3 ring = new FrameRing3();

    /** Written only by the reader thread; volatile so /web doctor reads a recent value. */
    private volatile long framesIn;
    private volatile long bytesIn;
    private volatile long framesRejected;
    /** Page size of the last rejected (too large) frame, 0 when the last frame was accepted. */
    private volatile int rejectedWidth;
    private volatile int rejectedHeight;

    public FrameRing3 ring() {
        return ring;
    }

    /** Reader thread: streams one FRAME's pixels into the canvas and publishes the result. */
    public void receive(HostProtocol.FrameHeader f, DataInputStream in, byte[] scratch) throws IOException {
        canvas.read(f, in, scratch);
        accepted(f);
    }

    /** Same as {@link #receive(HostProtocol.FrameHeader, DataInputStream, byte[])} from an in-memory payload. */
    public void receive(HostProtocol.FrameHeader f, byte[] payload) throws HostProtocol.ProtocolException {
        canvas.apply(f, payload, HostProtocol.FrameHeader.BYTES);
        accepted(f);
    }

    /**
     * Reader thread: a FRAME of a page that does not {@link FrameCanvas#fits fit} was skipped. Returns true for the
     * first rejected frame of a page size, so the caller reports the failure once per size instead of 30 times a
     * second.
     */
    public boolean reject(int pageWidth, int pageHeight) {
        framesRejected++;
        if (pageWidth == rejectedWidth && pageHeight == rejectedHeight) return false;
        rejectedWidth = pageWidth;
        rejectedHeight = pageHeight;
        return true;
    }

    private void accepted(HostProtocol.FrameHeader f) {
        ring.publish(canvas, f.x, f.y, f.w, f.h);
        framesIn++;
        bytesIn += (long) f.w * f.h * 4;
        rejectedWidth = 0;
        rejectedHeight = 0;
    }

    public long framesIn() {
        return framesIn;
    }

    public long bytesIn() {
        return bytesIn;
    }

    /** Frames skipped because their page was too large. */
    public long framesRejected() {
        return framesRejected;
    }

    /** "WxH" of the page currently being rejected, or null while frames are accepted. */
    public String rejectedSize() {
        int w = rejectedWidth;
        int h = rejectedHeight;
        return w == 0 && h == 0 ? null : w + "x" + h;
    }
}
