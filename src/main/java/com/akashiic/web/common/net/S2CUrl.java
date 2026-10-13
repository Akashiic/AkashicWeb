package com.akashiic.web.common.net;

import com.akashiic.web.common.ScreenKey;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** S2C 2: the screen's URL changed (new epoch). Sent to every player tracking the screen's chunk. */
public final class S2CUrl extends S2CMessage {

    public ScreenKey key;
    public String url;
    public int epoch;

    public S2CUrl() {}

    public S2CUrl(ScreenKey key, String url, int epoch) {
        this.key = key;
        this.url = url;
        this.epoch = epoch;
    }

    @Override
    protected void read(ByteBuf b) {
        key = NetCodec.readKey(b);
        url = NetCodec.readUrl(b);
        epoch = b.readInt();
    }

    @Override
    protected void write(ByteBuf b) {
        NetCodec.writeKey(b, key);
        NetCodec.writeString(b, url, NetCodec.MAX_URL);
        b.writeInt(epoch);
    }

    public static final class Handler implements IMessageHandler<S2CUrl, IMessage> {

        @Override
        public IMessage onMessage(S2CUrl m, MessageContext ctx) {
            if (ClientSide.accept(m)) ClientSide.hooks()
                .onUrl(m.key, m.url, m.epoch);
            return null;
        }
    }
}
