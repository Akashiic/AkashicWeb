package com.akashiic.web.common.net;

import com.akashiic.web.common.ScreenState;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * S2C 1: one screen's full record, sent to a single player to resynchronise their view (for example after one of
 * their actions was refused). Broadcasts to every tracker use the tile entity's description packet instead, so the
 * client tile entity (which the renderer reads) never goes stale; both paths end in {@link ClientHooks#onScreenState}.
 */
public final class S2CScreenState extends AkwMessage {

    public ScreenState state;

    public S2CScreenState() {}

    public S2CScreenState(ScreenState state) {
        this.state = state;
    }

    @Override
    protected void read(ByteBuf b) {
        state = NetCodec.readState(b);
    }

    @Override
    protected void write(ByteBuf b) {
        NetCodec.writeState(b, state);
    }

    public static final class Handler implements IMessageHandler<S2CScreenState, IMessage> {

        @Override
        public IMessage onMessage(S2CScreenState m, MessageContext ctx) {
            if (ClientSide.accept(m)) ClientSide.hooks()
                .onScreenState(m.state);
            return null;
        }
    }
}
