package com.akashiic.web.common.net;

import com.akashiic.web.common.ScreenKey;

import io.netty.buffer.ByteBuf;

/**
 * C2S 18: pointer input (move, down, up, wheel) at page position (u, v). The server checks reach and that the point
 * is in front of and visible to the player before echoing it to the sender (ADR-010, ADR-012).
 */
public final class C2SPointer extends AkwMessage {

    public ScreenKey key;
    public ScreenInput input;

    public C2SPointer() {}

    public C2SPointer(ScreenKey key, ScreenInput input) {
        this.key = key;
        this.input = input;
    }

    @Override
    protected void read(ByteBuf b) {
        key = NetCodec.readKey(b);
        input = NetCodec.readInput(b);
        if (!input.isPointer()) throw new CodecException("not a pointer event: " + input.kind);
    }

    @Override
    protected void write(ByteBuf b) {
        NetCodec.writeKey(b, key);
        NetCodec.writeInput(b, input);
    }
}
