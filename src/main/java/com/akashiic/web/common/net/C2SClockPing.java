package com.akashiic.web.common.net;

import io.netty.buffer.ByteBuf;

/** C2S 23: clock sync request; the server answers with S2CClockPong. */
public final class C2SClockPing extends AkwMessage {

    public long nonce;
    public long clientSendMs;

    public C2SClockPing() {}

    public C2SClockPing(long nonce, long clientSendMs) {
        this.nonce = nonce;
        this.clientSendMs = clientSendMs;
    }

    @Override
    protected void read(ByteBuf b) {
        nonce = b.readLong();
        clientSendMs = b.readLong();
    }

    @Override
    protected void write(ByteBuf b) {
        b.writeLong(nonce);
        b.writeLong(clientSendMs);
    }
}
