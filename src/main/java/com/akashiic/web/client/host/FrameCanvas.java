package com.akashiic.web.client.host;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Private full-page BGRA canvas of one screen, owned by the reader thread (ADR-004). Every FRAME carries only its
 * dirty rectangle; applying it here keeps a complete, correct page that {@link FrameRing3} can snapshot. A page size
 * change reallocates the canvas (zeroed: transparent until the host's next full paint, which Chromium sends after a
 * resize). Not thread-safe by design: only the reader thread touches it.
 */
public final class FrameCanvas {

    /** Largest page: a full frame (header + w*h*4) must fit one 16 MiB FRAME payload. */
    public static final long MAX_PIXELS = (HostProtocol.MAX_PAYLOAD - HostProtocol.FrameHeader.BYTES) / 4;

    /** Largest width or height (GL texture limits, {@link FrameSlot#MAX_DIMENSION}). */
    public static final int MAX_DIMENSION = FrameSlot.MAX_DIMENSION;

    private ByteBuffer pixels;
    private int width;
    private int height;

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The canvas bytes (direct, native order, row stride width*4). Reader thread only; duplicate before reading. */
    ByteBuffer pixels() {
        return pixels;
    }

    /**
     * True when a {@code w x h} page can be held: 1..{@link #MAX_DIMENSION} on each side and at most
     * {@link #MAX_PIXELS} pixels. Larger pages (for example a page painted at a display scale above 100%) are skipped
     * by the reader, which fails only that screen.
     */
    public static boolean fits(int w, int h) {
        return w > 0 && h > 0 && w <= MAX_DIMENSION && h <= MAX_DIMENSION && (long) w * h <= MAX_PIXELS;
    }

    /**
     * Makes the canvas {@code w x h}; returns true when it was (re)allocated. Rejects pages that do not
     * {@link #fits fit}: the direct allocation would otherwise be sized by the other process.
     */
    public boolean ensureSize(int w, int h) throws HostProtocol.ProtocolException {
        if (!fits(w, h)) throw new HostProtocol.ProtocolException("FRAME page " + w + "x" + h + " too large");
        if (pixels != null && w == width && h == height) return false;
        pixels = ByteBuffer.allocateDirect(w * h * 4)
            .order(ByteOrder.nativeOrder());
        width = w;
        height = h;
        return true;
    }

    /** Copies the dirty rectangle of a FRAME whose pixel rows start at {@code src[offset]}. */
    public void apply(HostProtocol.FrameHeader f, byte[] src, int offset) throws HostProtocol.ProtocolException {
        ensureSize(f.width, f.height);
        int rowBytes = f.w * 4;
        ByteBuffer dst = pixels.duplicate();
        for (int r = 0; r < f.h; r++) {
            dst.position(((f.y + r) * width + f.x) * 4);
            dst.put(src, offset + r * rowBytes, rowBytes);
        }
    }

    /**
     * Streams the dirty rectangle's rows straight from the socket into the canvas through {@code scratch}, so a frame
     * costs no per-message allocation (a 720p full frame would otherwise be 3.7 MB of garbage 30 times a second on the
     * player's Java 8 heap).
     */
    public void read(HostProtocol.FrameHeader f, DataInputStream in, byte[] scratch) throws IOException {
        ensureSize(f.width, f.height);
        ByteBuffer dst = pixels.duplicate();
        if (f.x == 0 && f.w == width) {
            // Full-width rectangle: its rows are contiguous in the canvas.
            dst.position(f.y * width * 4);
            copy(in, dst, (long) f.w * f.h * 4, scratch);
        } else {
            long rowBytes = f.w * 4L;
            for (int r = 0; r < f.h; r++) {
                dst.position(((f.y + r) * width + f.x) * 4);
                copy(in, dst, rowBytes, scratch);
            }
        }
    }

    private static void copy(DataInputStream in, ByteBuffer dst, long bytes, byte[] scratch) throws IOException {
        while (bytes > 0) {
            int n = (int) Math.min(scratch.length, bytes);
            in.readFully(scratch, 0, n);
            dst.put(scratch, 0, n);
            bytes -= n;
        }
    }
}
