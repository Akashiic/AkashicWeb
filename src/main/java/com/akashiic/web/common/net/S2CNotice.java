package com.akashiic.web.common.net;

import com.akashiic.web.AkashicWeb;
import com.akashiic.web.common.ReasonCode;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * S2C 6: a reason code with arguments for the player (refusals, screen events). The code travels by name, so a code
 * added on the server later is simply unknown (null) on an older client, which logs and ignores it.
 */
public final class S2CNotice extends AkwMessage {

    /** Null on the client when the server sent a code this client does not know. */
    public ReasonCode code;
    public String codeName;
    public String[] args;

    public S2CNotice() {}

    public S2CNotice(ReasonCode code, String... args) {
        this.code = code;
        this.codeName = code.name();
        this.args = args == null ? new String[0] : args;
    }

    @Override
    protected void read(ByteBuf b) {
        codeName = NetCodec.readString(b, 48);
        code = ReasonCode.parse(codeName);
        int n = b.readUnsignedByte();
        if (n > NetCodec.MAX_NOTICE_ARGS) throw new CodecException("args " + n);
        args = new String[n];
        for (int i = 0; i < n; i++) args[i] = NetCodec.readText(b, NetCodec.MAX_NOTICE_ARG);
    }

    @Override
    protected void write(ByteBuf b) {
        NetCodec.writeNotice(b, code, args);
    }

    public static final class Handler implements IMessageHandler<S2CNotice, IMessage> {

        @Override
        public IMessage onMessage(S2CNotice m, MessageContext ctx) {
            if (!ClientSide.accept(m)) return null;
            if (m.code == null) {
                AkashicWeb.LOG.info("AkashicWeb: server notice with unknown code {}", m.codeName);
                return null;
            }
            ClientSide.hooks()
                .onNotice(m.code, m.args);
            return null;
        }
    }
}
