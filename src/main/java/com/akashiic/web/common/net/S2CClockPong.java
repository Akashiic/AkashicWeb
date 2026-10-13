package com.akashiic.web.common.net;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** S2C 5: answer to C2SClockPing with the server's clock (see {@link com.akashiic.web.common.ClockMath}). */
public final class S2CClockPong extends AkwMessage {

    public long nonce;
    public long clientSendMs;
    public long serverTimeMs;

    public S2CClockPong() {}

    public S2CClockPong(long nonce, long clientSendMs, long serverTimeMs) {
        this.nonce = nonce;
        this.clientSendMs = clientSendMs;
        this.serverTimeMs = serverTimeMs;
    }

    @Override
    protected void read(ByteBuf b) {
        nonce = b.readLong();
        clientSendMs = b.readLong();
        serverTimeMs = b.readLong();
    }

    @Override
    protected void write(ByteBuf b) {
        b.writeLong(nonce);
        b.writeLong(clientSendMs);
        b.writeLong(serverTimeMs);
    }

    public static final class Handler implements IMessageHandler<S2CClockPong, IMessage> {

        @Override
        public IMessage onMessage(S2CClockPong m, MessageContext ctx) {
            if (ClientSide.accept(m)) ClientSide.hooks()
                .onClockPong(m.nonce, m.clientSendMs, m.serverTimeMs);
            return null;
        }
    }
}
