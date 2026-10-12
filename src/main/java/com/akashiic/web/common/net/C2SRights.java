package com.akashiic.web.common.net;

import com.akashiic.web.common.ScreenKey;

import io.netty.buffer.ByteBuf;

/** C2S 22: friends, rights bits, resolution, rotation and (admins) lock of a screen. */
public final class C2SRights extends AkwMessage {

    public ScreenKey key;
    public RightsChange change;

    public C2SRights() {}

    public C2SRights(ScreenKey key, RightsChange change) {
        this.key = key;
        this.change = change;
    }

    @Override
    protected void read(ByteBuf b) {
        key = NetCodec.readKey(b);
        change = NetCodec.readRights(b);
    }

    @Override
    protected void write(ByteBuf b) {
        NetCodec.writeKey(b, key);
        NetCodec.writeRights(b, change);
    }
}
