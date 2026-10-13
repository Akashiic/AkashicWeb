package com.akashiic.web.client.host;

/**
 * Decoded PCM payload (docs/PROTOCOL.md section 1, type 3): big-endian header {@code i64 ptsMicros, u32 sampleRate,
 * u16 channels, u16 frames}, then {@code frames * channels} signed 16-bit little-endian samples, interleaved. The
 * header is big-endian like every other protocol field; the samples are little-endian because that is the native
 * layout of the host's Int16Array and avoids a swap on the host's main thread.
 */
public final class PcmChunk {

    public static final int HEADER_BYTES = 16;
    public static final int MIN_RATE = 8000;
    public static final int MAX_RATE = 192000;
    public static final int MAX_CHANNELS = 8;

    public final long ptsMicros;
    public final int sampleRate;
    public final int channels;
    public final int frames;
    /** {@code frames * channels} samples; a fresh array per chunk so listeners may keep it. */
    public final short[] interleaved;

    private PcmChunk(long ptsMicros, int sampleRate, int channels, int frames, short[] interleaved) {
        this.ptsMicros = ptsMicros;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.frames = frames;
        this.interleaved = interleaved;
    }

    /** Decodes {@code length} payload bytes starting at {@code offset}. */
    public static PcmChunk decode(byte[] b, int offset, int length) throws HostProtocol.ProtocolException {
        if (length < HEADER_BYTES) throw new HostProtocol.ProtocolException("short PCM");
        long pts = 0;
        for (int i = 0; i < 8; i++) pts = (pts << 8) | (b[offset + i] & 0xFF);
        long rate = ((long) (b[offset + 8] & 0xFF) << 24) | ((b[offset + 9] & 0xFF) << 16)
            | ((b[offset + 10] & 0xFF) << 8)
            | (b[offset + 11] & 0xFF);
        int channels = ((b[offset + 12] & 0xFF) << 8) | (b[offset + 13] & 0xFF);
        int frames = ((b[offset + 14] & 0xFF) << 8) | (b[offset + 15] & 0xFF);
        if (rate < MIN_RATE || rate > MAX_RATE) throw new HostProtocol.ProtocolException("PCM rate " + rate);
        if (channels < 1 || channels > MAX_CHANNELS)
            throw new HostProtocol.ProtocolException("PCM channels " + channels);
        int samples = frames * channels;
        if (length != HEADER_BYTES + samples * 2)
            throw new HostProtocol.ProtocolException("PCM size " + length + " != " + (HEADER_BYTES + samples * 2));
        short[] out = new short[samples];
        int p = offset + HEADER_BYTES;
        for (int i = 0; i < samples; i++, p += 2) out[i] = (short) ((b[p] & 0xFF) | (b[p + 1] << 8));
        return new PcmChunk(pts, (int) rate, channels, frames, out);
    }
}
