package com.akashiic.web.common.net;

import com.akashiic.web.common.SitePolicy;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * S2C 0: sent on login, after a policy reload and when the kill switch changes. A client with another protocol major
 * shows a placeholder instead of disconnecting (ADR-010).
 */
public final class S2CHello extends AkwMessage {

    public int protoMajor;
    public int protoMinor;
    public boolean killSwitch;
    public SitePolicy policy;
    public long serverTimeMs;

    public S2CHello() {}

    public S2CHello(int protoMajor, int protoMinor, boolean killSwitch, SitePolicy policy, long serverTimeMs) {
        this.protoMajor = protoMajor;
        this.protoMinor = protoMinor;
        this.killSwitch = killSwitch;
        this.policy = policy;
        this.serverTimeMs = serverTimeMs;
    }

    @Override
    protected void read(ByteBuf b) {
        // Major and minor come first and never move, so a client of any version can read them.
        protoMajor = b.readUnsignedShort();
        protoMinor = b.readUnsignedShort();
        killSwitch = b.readBoolean();
        policy = NetCodec.readPolicy(b);
        serverTimeMs = b.readLong();
    }

    @Override
    protected void write(ByteBuf b) {
        b.writeShort(protoMajor);
        b.writeShort(protoMinor);
        b.writeBoolean(killSwitch);
        NetCodec.writePolicy(b, policy);
        b.writeLong(serverTimeMs);
    }

    public static final class Handler implements IMessageHandler<S2CHello, IMessage> {

        @Override
        public IMessage onMessage(S2CHello m, MessageContext ctx) {
            if (ClientSide.accept(m)) ClientSide.hooks()
                .onHello(m.protoMajor, m.protoMinor, m.killSwitch, m.policy, m.serverTimeMs);
            return null;
        }
    }
}
