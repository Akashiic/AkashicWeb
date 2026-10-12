package com.akashiic.web.client.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

import org.junit.jupiter.api.Test;

class HostProtocolTest {

    @Test
    void roundTrip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        HostProtocol.writeJson(out, HostProtocol.NAVIGATE, 7, 3, "{\"url\":\"https://example.com/ação\"}");
        HostProtocol.write(out, HostProtocol.QUIT, 0, 4, null);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        HostProtocol.Message m = HostProtocol.read(in);
        assertEquals(HostProtocol.NAVIGATE, m.type);
        assertEquals(7, m.screenId);
        assertEquals(3, m.seq);
        assertEquals("{\"url\":\"https://example.com/ação\"}", m.text());
        HostProtocol.Message q = HostProtocol.read(in);
        assertEquals(HostProtocol.QUIT, q.type);
        assertEquals(0, q.payload.length);
        assertNull(HostProtocol.read(in));
    }

    @Test
    void rejectsBadMagicAndLength() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x12345678);
        assertThrows(
            HostProtocol.ProtocolException.class,
            () -> HostProtocol.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        ByteArrayOutputStream b2 = new ByteArrayOutputStream();
        DataOutputStream o2 = new DataOutputStream(b2);
        o2.writeInt(HostProtocol.MAGIC);
        o2.writeShort(HostProtocol.FRAME);
        o2.writeShort(0);
        o2.writeInt(1);
        o2.writeInt(0);
        o2.writeInt(HostProtocol.MAX_PAYLOAD + 1);
        assertThrows(
            HostProtocol.ProtocolException.class,
            () -> HostProtocol.read(new DataInputStream(new ByteArrayInputStream(b2.toByteArray()))));
    }

    @Test
    void frameHeaderValidation() throws IOException {
        byte[] ok = frame(1280, 720, 10, 20, 2, 3);
        HostProtocol.FrameHeader f = HostProtocol.FrameHeader.parse(ok);
        assertEquals(1280, f.width);
        assertEquals(3, f.h);
        assertArrayEquals(new int[] { 10, 20, 2, 3 }, new int[] { f.x, f.y, f.w, f.h });
        assertThrows(
            HostProtocol.ProtocolException.class,
            () -> HostProtocol.FrameHeader.parse(frame(100, 100, 99, 0, 2, 1)));
        byte[] shortData = new byte[ok.length - 1];
        System.arraycopy(ok, 0, shortData, 0, shortData.length);
        assertThrows(HostProtocol.ProtocolException.class, () -> HostProtocol.FrameHeader.parse(shortData));
    }

    /** Bytes produced by host/src/protocol.js encode() for the same messages (node 22, 2026-10-12). */
    @Test
    void matchesTheJavaScriptEncoderByteForByte() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        HostProtocol.writeJson(out, HostProtocol.NAVIGATE, 7, 0xFFFFFFFE, "{\"url\":\"https://example.com/ação\"}");
        assertEquals(
            "414b57310023000000000007fffffffe000000247b2275726c223a2268747470733a2f2f6578616d706c652e636f6d2f61c3a7c3a36f227d",
            hex(bytes.toByteArray()));

        byte[] js = unhex("414b573100020000000000030000000100000014050002d0000a0014000200010102030405060708");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(js));
        HostProtocol.Header h = HostProtocol.readHeader(in);
        assertEquals(HostProtocol.FRAME, h.type);
        assertEquals(3, h.screenId);
        assertEquals(1, h.seq);
        assertEquals(20, h.length);
        byte[] head = new byte[HostProtocol.FrameHeader.BYTES];
        in.readFully(head);
        HostProtocol.FrameHeader f = HostProtocol.FrameHeader.parse(head, h.length);
        assertArrayEquals(new int[] { 1280, 720, 10, 20, 2, 1 }, new int[] { f.width, f.height, f.x, f.y, f.w, f.h });
        assertFalse(f.isFullFrame());
    }

    @Test
    void headerReaderSeparatesCleanEofFromTruncation() throws IOException {
        assertNull(HostProtocol.readHeader(new DataInputStream(new ByteArrayInputStream(new byte[0]))));
        byte[] partial = { 0x41, 0x4B, 0x57 };
        assertThrows(
            EOFException.class,
            () -> HostProtocol.readHeader(new DataInputStream(new ByteArrayInputStream(partial))));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HostProtocol.writeJson(new DataOutputStream(bytes), HostProtocol.STATE, 1, 0, "{\"a\":1}");
        byte[] cut = new byte[bytes.size() - 2];
        System.arraycopy(bytes.toByteArray(), 0, cut, 0, cut.length);
        assertThrows(EOFException.class, () -> HostProtocol.read(new DataInputStream(new ByteArrayInputStream(cut))));
    }

    @Test
    void unknownTypesAndExtremeHeaderValuesRoundTrip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        HostProtocol.write(out, 0xFFFF, -1, Integer.MIN_VALUE, new byte[] { 9 });
        HostProtocol.write(out, HostProtocol.DESTROY, Integer.MAX_VALUE, -1, new byte[0]);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        HostProtocol.Message a = HostProtocol.read(in);
        assertEquals(0xFFFF, a.type);
        assertEquals(-1, a.screenId);
        assertEquals(Integer.MIN_VALUE, a.seq);
        assertArrayEquals(new byte[] { 9 }, a.payload);
        HostProtocol.Message b = HostProtocol.read(in);
        assertEquals(Integer.MAX_VALUE, b.screenId);
        assertEquals(-1, b.seq);
        assertEquals(0, b.payload.length);
        assertThrows(
            HostProtocol.ProtocolException.class,
            () -> HostProtocol.write(out, HostProtocol.STATE, 0, 0, new byte[HostProtocol.MAX_PAYLOAD + 1]));
    }

    @Test
    void negativeLengthIsRejected() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bytes);
        o.writeInt(HostProtocol.MAGIC);
        o.writeShort(HostProtocol.STATE);
        o.writeShort(0);
        o.writeInt(1);
        o.writeInt(0);
        o.writeInt(-1);
        assertThrows(
            HostProtocol.ProtocolException.class,
            () -> HostProtocol.readHeader(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
    }

    @Test
    void frameHeaderRejectsZeroSizesAndRectsOutsideThePage() {
        assertThrows(
            HostProtocol.ProtocolException.class,
            () -> HostProtocol.FrameHeader.parse(frame(0, 10, 0, 0, 1, 1)));
        assertThrows(
            HostProtocol.ProtocolException.class,
            () -> HostProtocol.FrameHeader.parse(frame(10, 10, 0, 0, 0, 1)));
        assertThrows(
            HostProtocol.ProtocolException.class,
            () -> HostProtocol.FrameHeader.parse(frame(10, 10, 0, 9, 1, 2)));
        assertThrows(HostProtocol.ProtocolException.class, () -> HostProtocol.FrameHeader.parse(new byte[5]));
        assertThrows(
            HostProtocol.ProtocolException.class,
            () -> HostProtocol.FrameHeader.parse(frame(10, 10, 0, 0, 1, 1), 999));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static byte[] frame(int width, int height, int x, int y, int w, int h) {
        byte[] p = new byte[HostProtocol.FrameHeader.BYTES + w * h * 4];
        int[] v = { width, height, x, y, w, h };
        for (int i = 0; i < v.length; i++) {
            p[i * 2] = (byte) (v[i] >> 8);
            p[i * 2 + 1] = (byte) v[i];
        }
        return p;
    }
}
