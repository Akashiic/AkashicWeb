package com.akashiic.web.common.net;

import com.akashiic.web.common.ScreenKey;

import io.netty.buffer.ByteBuf;

/** C2S 16: navigate a screen (address bar, or the controller's own main-frame navigation reported back). */
public final class C2SSetUrl extends AkwMessage {

    public ScreenKey key;
    /** The epoch the client saw; a stale one is dropped (someone else changed the URL meanwhile). */
    public int epoch;
    public String url;

    public C2SSetUrl() {}

    public C2SSetUrl(ScreenKey key, int epoch, String url) {
        this.key = key;
        this.epoch = epoch;
        this.url = url;
    }

    @Override
    protected void read(ByteBuf b) {
        key = NetCodec.readKey(b);
        epoch = b.readInt();
        url = NetCodec.readUrl(b);
    }

    @Override
    protected void write(ByteBuf b) {
        NetCodec.writeKey(b, key);
        b.writeInt(epoch);
        NetCodec.writeString(b, url, NetCodec.MAX_URL);
    }
}
