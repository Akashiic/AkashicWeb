package com.akashiic.web.common.net;

import com.akashiic.web.common.ScreenKey;

import io.netty.buffer.ByteBuf;

/** C2S 21: take or release the controller lock of a SHARED/CAST screen. */
public final class C2SControl extends AkwMessage {

    public ScreenKey key;
    public boolean take;

    public C2SControl() {}

    public C2SControl(ScreenKey key, boolean take) {
        this.key = key;
        this.take = take;
    }

    @Override
    protected void read(ByteBuf b) {
        key = NetCodec.readKey(b);
        take = b.readBoolean();
    }

    @Override
    protected void write(ByteBuf b) {
        NetCodec.writeKey(b, key);
        b.writeBoolean(take);
    }
}
