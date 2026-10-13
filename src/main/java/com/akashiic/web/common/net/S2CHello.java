package com.akashiic.web.common.net;

import java.util.UUID;

import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.protocol.Protocol;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * S2C 0: sent on login, after a policy reload and when the kill switch changes. A client with another protocol major
 * shows a placeholder instead of disconnecting (ADR-010): the major and minor come first and never move, and a hello
 * of another major is read no further, so it still reaches the client as a version mismatch instead of being dropped
 * as malformed.
 */
public final class S2CHello extends S2CMessage {

    public int protoMajor;
    public int protoMinor;
    public boolean killSwitch;
    /** Null when {@link #foreignMajor}. */
    public SitePolicy policy;
    /** The server's clock (server.ServerClock) when the hello was written. */
    public long serverTimeMs;
    /**
     * The receiving player's UUID as the server knows it (D-016). On offline-mode or proxied servers it can differ from
     * the UUID the client's own session reports, and the client compares it with {@code ScreenState.controller} to
     * know whether it is the controller. Null when a server of the same major built before D-016 sent the hello, or
     * when {@link #foreignMajor}.
     */
    public UUID playerUuid;
    /** The server speaks another protocol major: nothing after the version numbers was read. */
    public boolean foreignMajor;

    public S2CHello() {}

    public S2CHello(int protoMajor, int protoMinor, boolean killSwitch, SitePolicy policy, long serverTimeMs,
        UUID playerUuid) {
        this.protoMajor = protoMajor;
        this.protoMinor = protoMinor;
        this.killSwitch = killSwitch;
        this.policy = policy;
        this.serverTimeMs = serverTimeMs;
        this.playerUuid = playerUuid;
    }

    @Override
    protected void read(ByteBuf b) {
        // Major and minor come first and never move, so a client of any version can read them.
        protoMajor = b.readUnsignedShort();
        protoMinor = b.readUnsignedShort();
        if (!Protocol.compatible(protoMajor)) {
            // Another major may have changed everything after the version numbers: do not guess, report the mismatch.
            foreignMajor = true;
            b.skipBytes(b.readableBytes());
            return;
        }
        killSwitch = b.readBoolean();
        policy = NetCodec.readPolicy(b);
        serverTimeMs = b.readLong();
        // Appended by D-016: a server of the same major built before it does not send the field.
        playerUuid = b.isReadable() ? NetCodec.readUuid(b) : null;
    }

    @Override
    protected void write(ByteBuf b) {
        b.writeShort(protoMajor);
        b.writeShort(protoMinor);
        b.writeBoolean(killSwitch);
        NetCodec.writePolicy(b, policy);
        b.writeLong(serverTimeMs);
        NetCodec.writeUuid(b, playerUuid);
    }

    public static final class Handler implements IMessageHandler<S2CHello, IMessage> {

        @Override
        public IMessage onMessage(S2CHello m, MessageContext ctx) {
            if (ClientSide.accept(m)) ClientSide.hooks()
                .onHello(m.protoMajor, m.protoMinor, m.killSwitch, m.policy, m.serverTimeMs, m.playerUuid);
            return null;
        }
    }
}
