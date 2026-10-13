package com.akashiic.web.common.net;

import com.akashiic.web.common.ScreenKey;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * S2C 4: the server's extrapolated media clock for a screen, 2-4 times per second to every tracker: at server time
 * {@link #serverTimeMs} the media position is {@link #mediaTime} seconds, advancing at {@link #rate} unless paused.
 * {@link #serverTimeMs} is on the same server clock as S2CClockPong (server.ServerClock), so a client that synced its
 * clock with the ping exchange can compare it with its own estimate of server time directly.
 * <p>
 * While the controller's page shows an ad the server holds the clock: the message says paused at the position where
 * the content stopped, and it resumes with the controller's next sample of the content.
 */
public final class S2CMediaClock extends S2CMessage {

    /** Largest live lag sent (an hour): anything larger is a broken sample, not a stream. */
    public static final int MAX_LIVE_LAG_MS = 3_600_000;

    public ScreenKey key;
    public int epoch;
    public double mediaTime;
    public float rate;
    public boolean paused;
    public long serverTimeMs;
    /** The controller's page plays a live stream (D-016). */
    public boolean live;
    /** How far the controller is behind the live edge, in ms; 0 for VOD (D-016, for the followers' live-edge rule). */
    public int liveLagMs;

    public S2CMediaClock() {}

    public S2CMediaClock(ScreenKey key, int epoch, double mediaTime, float rate, boolean paused, long serverTimeMs,
        boolean live, int liveLagMs) {
        this.key = key;
        this.epoch = epoch;
        this.mediaTime = mediaTime;
        this.rate = rate;
        this.paused = paused;
        this.serverTimeMs = serverTimeMs;
        this.live = live;
        this.liveLagMs = liveLagMs;
    }

    @Override
    protected void read(ByteBuf b) {
        key = NetCodec.readKey(b);
        epoch = b.readInt();
        mediaTime = NetCodec.readFinite(b);
        rate = NetCodec.readFiniteFloat(b);
        paused = b.readBoolean();
        serverTimeMs = b.readLong();
        // Appended by D-016: a server of the same major built before it does not send them.
        if (b.isReadable()) {
            live = b.readBoolean();
            liveLagMs = b.readInt();
            if (liveLagMs < 0 || liveLagMs > MAX_LIVE_LAG_MS) throw new CodecException("liveLagMs " + liveLagMs);
        }
    }

    @Override
    protected void write(ByteBuf b) {
        NetCodec.writeKey(b, key);
        b.writeInt(epoch);
        b.writeDouble(mediaTime);
        b.writeFloat(rate);
        b.writeBoolean(paused);
        b.writeLong(serverTimeMs);
        b.writeBoolean(live);
        b.writeInt(Math.max(0, Math.min(MAX_LIVE_LAG_MS, liveLagMs)));
    }

    public static final class Handler implements IMessageHandler<S2CMediaClock, IMessage> {

        @Override
        public IMessage onMessage(S2CMediaClock m, MessageContext ctx) {
            if (ClientSide.accept(m)) ClientSide.hooks()
                .onMediaClock(m.key, m.epoch, m.mediaTime, m.rate, m.paused, m.serverTimeMs, m.live, m.liveLagMs);
            return null;
        }
    }
}
