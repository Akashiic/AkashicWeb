package com.akashiic.web.common.net;

import com.akashiic.web.common.ScreenKey;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * S2C 4: the server's extrapolated media clock for a screen, 2-4 times per second to every tracker: at server time
 * {@link #serverTimeMs} the media position is {@link #mediaTime} seconds, advancing at {@link #rate} unless paused.
 */
public final class S2CMediaClock extends AkwMessage {

    public ScreenKey key;
    public int epoch;
    public double mediaTime;
    public float rate;
    public boolean paused;
    public long serverTimeMs;

    public S2CMediaClock() {}

    public S2CMediaClock(ScreenKey key, int epoch, double mediaTime, float rate, boolean paused, long serverTimeMs) {
        this.key = key;
        this.epoch = epoch;
        this.mediaTime = mediaTime;
        this.rate = rate;
        this.paused = paused;
        this.serverTimeMs = serverTimeMs;
    }

    @Override
    protected void read(ByteBuf b) {
        key = NetCodec.readKey(b);
        epoch = b.readInt();
        mediaTime = NetCodec.readFinite(b);
        rate = NetCodec.readFiniteFloat(b);
        paused = b.readBoolean();
        serverTimeMs = b.readLong();
    }

    @Override
    protected void write(ByteBuf b) {
        NetCodec.writeKey(b, key);
        b.writeInt(epoch);
        b.writeDouble(mediaTime);
        b.writeFloat(rate);
        b.writeBoolean(paused);
        b.writeLong(serverTimeMs);
    }

    public static final class Handler implements IMessageHandler<S2CMediaClock, IMessage> {

        @Override
        public IMessage onMessage(S2CMediaClock m, MessageContext ctx) {
            if (ClientSide.accept(m)) ClientSide.hooks()
                .onMediaClock(m.key, m.epoch, m.mediaTime, m.rate, m.paused, m.serverTimeMs);
            return null;
        }
    }
}
