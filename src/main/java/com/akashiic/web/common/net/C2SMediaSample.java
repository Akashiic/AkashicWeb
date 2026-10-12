package com.akashiic.web.common.net;

import com.akashiic.web.common.ScreenKey;

import io.netty.buffer.ByteBuf;

/** C2S 20: the controller's media position at 4 Hz, stamped by the client in server time (ADR-012 item 3). */
public final class C2SMediaSample extends AkwMessage {

    public ScreenKey key;
    public MediaSample sample;

    public C2SMediaSample() {}

    public C2SMediaSample(ScreenKey key, MediaSample sample) {
        this.key = key;
        this.sample = sample;
    }

    @Override
    protected void read(ByteBuf b) {
        key = NetCodec.readKey(b);
        sample = NetCodec.readSample(b);
    }

    @Override
    protected void write(ByteBuf b) {
        NetCodec.writeKey(b, key);
        NetCodec.writeSample(b, sample);
    }
}
