package com.akashiic.web.common.net;

import com.akashiic.web.common.ScreenKey;

import io.netty.buffer.ByteBuf;

/** C2S 19: keyboard input (key down/up with a DOM key name, or composed text). */
public final class C2SKey extends AkwMessage {

    public ScreenKey key;
    public ScreenInput input;

    public C2SKey() {}

    public C2SKey(ScreenKey key, ScreenInput input) {
        this.key = key;
        this.input = input;
    }

    @Override
    protected void read(ByteBuf b) {
        key = NetCodec.readKey(b);
        input = NetCodec.readInput(b);
        if (input.isPointer()) throw new CodecException("not a key event: " + input.kind);
        if (input.kind == ScreenInput.Kind.CHAR && input.text.isEmpty()) throw new CodecException("empty text");
    }

    @Override
    protected void write(ByteBuf b) {
        NetCodec.writeKey(b, key);
        NetCodec.writeInput(b, input);
    }
}
