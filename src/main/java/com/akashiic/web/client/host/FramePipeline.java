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

    public FrameRing3 ring() {
        return ring;
    }

    /** Reader thread: streams one FRAME's pixels into the canvas and publishes the result. */
    public void receive(HostProtocol.FrameHeader f, DataInputStream in, byte[] scratch) throws IOException {
        canvas.read(f, in, scratch);
        ring.publish(canvas, f.x, f.y, f.w, f.h);
        framesIn++;
        bytesIn += (long) f.w * f.h * 4;
    }

    /** Same as {@link #receive(HostProtocol.FrameHeader, DataInputStream, byte[])} from an in-memory payload. */
    public void receive(HostProtocol.FrameHeader f, byte[] payload) throws HostProtocol.ProtocolException {
        canvas.apply(f, payload, HostProtocol.FrameHeader.BYTES);
        ring.publish(canvas, f.x, f.y, f.w, f.h);
        framesIn++;
        bytesIn += (long) f.w * f.h * 4;
    }

    public long framesIn() {
        return framesIn;
    }

    public long bytesIn() {
        return bytesIn;
    }
}
