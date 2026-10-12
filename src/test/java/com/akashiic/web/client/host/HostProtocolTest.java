package com.akashiic.web.client.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
