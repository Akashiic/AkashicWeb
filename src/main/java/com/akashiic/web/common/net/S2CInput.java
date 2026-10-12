package com.akashiic.web.common.net;

import com.akashiic.web.common.ScreenKey;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * S2C 3: input the server accepted, echoed ONLY to the client that sent it (the controller on SHARED/CAST screens,
 * the viewer on PRIVATE ones). Followers never receive raw input (ADR-012 amendment): they converge on URL and media
 * clock, so a relayed click can never land on an ad in N independent sessions.
 */
public final class S2CInput extends AkwMessage {

    public ScreenKey key;
    public int epoch;
    public ScreenInput input;

    public S2CInput() {}

    public S2CInput(ScreenKey key, int epoch, ScreenInput input) {
        this.key = key;
        this.epoch = epoch;
        this.input = input;
    }

    @Override
    protected void read(ByteBuf b) {
        key = NetCodec.readKey(b);
        epoch = b.readInt();
        input = NetCodec.readInput(b);
    }

    @Override
    protected void write(ByteBuf b) {
        NetCodec.writeKey(b, key);
        b.writeInt(epoch);
        NetCodec.writeInput(b, input);
    }

    public static final class Handler implements IMessageHandler<S2CInput, IMessage> {

        @Override
        public IMessage onMessage(S2CInput m, MessageContext ctx) {
            if (ClientSide.accept(m)) ClientSide.hooks()
                .onInput(m.key, m.epoch, m.input);
            return null;
        }
    }
}
