package com.akashiic.web.client.host;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Wire format between the game and the AkashicWebHost process (docs/PROTOCOL.md, section 1). Pure Java, no
 * Minecraft types, so it is unit-tested on its own and mirrored by host/src/protocol.js.
 */
public final class HostProtocol {

    public static final int MAGIC = 0x414B5731; // "AKW1"
    public static final int HEADER_BYTES = 20;
    public static final int MAX_PAYLOAD = 16 * 1024 * 1024;

    // Host -> game
    public static final int HELLO = 1;
    public static final int FRAME = 2;
    public static final int PCM = 3;
    public static final int STATE = 4;
    public static final int MEDIA = 5;
    public static final int EVENT = 6;
    public static final int PONG = 7;
    public static final int BYE = 8;

    // Game -> host
    public static final int WELCOME = 32;
    public static final int CREATE = 33;
    public static final int DESTROY = 34;
    public static final int NAVIGATE = 35;
    public static final int INPUT = 36;
    public static final int POLICY = 37;
    public static final int AUDIO = 38;
    public static final int RENDER = 39;
    public static final int RESIZE = 40;
    public static final int SYNC = 41;
    public static final int PING = 42;
    public static final int QUIT = 43;

    private HostProtocol() {}

    /** One decoded message. {@code payload} is owned by the caller. */
    public static final class Message {

        public final int type;
        public final int flags;
        public final int screenId;
        public final int seq;
        public final byte[] payload;

        public Message(int type, int flags, int screenId, int seq, byte[] payload) {
            this.type = type;
            this.flags = flags;
            this.screenId = screenId;
            this.seq = seq;
            this.payload = payload;
        }

        public String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    /** Thrown when the stream does not follow the protocol; the connection must be closed. */
    public static final class ProtocolException extends IOException {

        private static final long serialVersionUID = 1L;

        public ProtocolException(String message) {
            super(message);
        }
    }

    public static void write(DataOutputStream out, int type, int screenId, int seq, byte[] payload) throws IOException {
        int length = payload == null ? 0 : payload.length;
        if (length > MAX_PAYLOAD) throw new ProtocolException("payload too large: " + length);
        out.writeInt(MAGIC);
        out.writeShort(type);
        out.writeShort(0);
        out.writeInt(screenId);
        out.writeInt(seq);
        out.writeInt(length);
        if (length > 0) out.write(payload);
    }

    public static void writeJson(DataOutputStream out, int type, int screenId, int seq, String json)
        throws IOException {
        write(out, type, screenId, seq, json.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads one message; returns null on a clean EOF before a header starts. */
    public static Message read(DataInputStream in) throws IOException {
        Header h = readHeader(in);
        if (h == null) return null;
        byte[] payload = new byte[h.length];
        in.readFully(payload);
        return new Message(h.type, h.flags, h.screenId, h.seq, payload);
    }

    /** The 20-byte header of one message. Lets the reader stream large payloads (FRAME) without a copy. */
    public static final class Header {

        public final int type;
        public final int flags;
        public final int screenId;
        public final int seq;
        /** Payload bytes that follow, 0..{@link #MAX_PAYLOAD}. */
        public final int length;

        public Header(int type, int flags, int screenId, int seq, int length) {
            this.type = type;
            this.flags = flags;
            this.screenId = screenId;
            this.seq = seq;
            this.length = length;
        }
    }

    /**
     * Reads and validates one header; the caller must then consume exactly {@code length} payload bytes. Returns null
     * on a clean EOF before the header starts; an EOF inside the header is an {@link EOFException}.
     */
    public static Header readHeader(DataInputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int magic = (b0 << 24) | (in.readUnsignedByte() << 16) | (in.readUnsignedShort());
        if (magic != MAGIC) throw new ProtocolException("bad magic 0x" + Integer.toHexString(magic));
        int type = in.readUnsignedShort();
        int flags = in.readUnsignedShort();
        int screenId = in.readInt();
        int seq = in.readInt();
        int length = in.readInt();
        if (length < 0 || length > MAX_PAYLOAD) throw new ProtocolException("bad length " + length);
        return new Header(type, flags, screenId, seq, length);
    }

    /** Header of a FRAME payload: page size and the dirty rectangle carried in the rest of the payload. */
    public static final class FrameHeader {

        public static final int BYTES = 12;
        public final int width;
        public final int height;
        public final int x;
        public final int y;
        public final int w;
        public final int h;

        public FrameHeader(int width, int height, int x, int y, int w, int h) {
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        /** Parses and validates the header; the pixel data starts at offset {@link #BYTES}. */
        public static FrameHeader parse(byte[] payload) throws ProtocolException {
            return parse(payload, payload.length);
        }

        /**
         * Parses the first {@link #BYTES} bytes of a FRAME payload whose total length is {@code payloadLength}, so the
         * pixel rows can be streamed afterwards. Same validation as {@link #parse(byte[])}.
         */
        public static FrameHeader parse(byte[] head, long payloadLength) throws ProtocolException {
            if (payloadLength < BYTES || head.length < BYTES) throw new ProtocolException("short FRAME");
            FrameHeader f = new FrameHeader(
                u16(head, 0),
                u16(head, 2),
                u16(head, 4),
                u16(head, 6),
                u16(head, 8),
                u16(head, 10));
            if (f.width == 0 || f.height == 0 || f.w == 0 || f.h == 0 || f.x + f.w > f.width || f.y + f.h > f.height)
                throw new ProtocolException("bad FRAME rect");
            long expected = (long) BYTES + (long) f.w * f.h * 4L;
            if (payloadLength != expected)
                throw new ProtocolException("FRAME size " + payloadLength + " != " + expected);
            return f;
        }

        public boolean isFullFrame() {
            return x == 0 && y == 0 && w == width && h == height;
        }

        private static int u16(byte[] b, int off) {
            return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
        }
    }
}
