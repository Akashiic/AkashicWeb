package com.akashiic.web.client.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

class PcmChunkTest {

    @Test
    void decodesBigEndianHeaderAndLittleEndianSamples() throws Exception {
        short[] samples = { 0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 12345 };
        byte[] p = encode(-5L, 48000, 2, 3, samples);
        PcmChunk c = PcmChunk.decode(p, 0, p.length);
        assertEquals(-5L, c.ptsMicros);
        assertEquals(48000, c.sampleRate);
        assertEquals(2, c.channels);
        assertEquals(3, c.frames);
        assertArrayEquals(samples, c.interleaved);
        byte[] shifted = new byte[p.length + 7];
        System.arraycopy(p, 0, shifted, 7, p.length);
        assertArrayEquals(samples, PcmChunk.decode(shifted, 7, p.length).interleaved);
        PcmChunk empty = PcmChunk.decode(encode(1, 44100, 1, 0, new short[0]), 0, 16);
        assertEquals(0, empty.interleaved.length);
    }

    @Test
    void rejectsInconsistentPayloads() {
        assertThrows(HostProtocol.ProtocolException.class, () -> PcmChunk.decode(new byte[15], 0, 15));
        byte[] p = encode(0, 48000, 2, 2, new short[4]);
        assertThrows(HostProtocol.ProtocolException.class, () -> PcmChunk.decode(p, 0, p.length - 2));
        byte[] rate = encode(0, 1000, 2, 0, new short[0]);
        assertThrows(HostProtocol.ProtocolException.class, () -> PcmChunk.decode(rate, 0, rate.length));
        byte[] channels = encode(0, 48000, 0, 0, new short[0]);
        assertThrows(HostProtocol.ProtocolException.class, () -> PcmChunk.decode(channels, 0, channels.length));
        byte[] many = encode(0, 48000, 9, 0, new short[0]);
        assertThrows(HostProtocol.ProtocolException.class, () -> PcmChunk.decode(many, 0, many.length));
        byte[] hugeRate = encode(0, 0, 2, 0, new short[0]);
        hugeRate[8] = (byte) 0xFF; // u32 above Integer.MAX_VALUE
        assertThrows(HostProtocol.ProtocolException.class, () -> PcmChunk.decode(hugeRate, 0, hugeRate.length));
    }

    static byte[] encode(long pts, int rate, int channels, int frames, short[] samples) {
        ByteBuffer b = ByteBuffer.allocate(16 + samples.length * 2);
        b.putLong(pts)
            .putInt(rate)
            .putShort((short) channels)
            .putShort((short) frames);
        b.order(ByteOrder.LITTLE_ENDIAN);
        for (short s : samples) b.putShort(s);
        return b.array();
    }
}
