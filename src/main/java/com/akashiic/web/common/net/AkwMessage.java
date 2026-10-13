package com.akashiic.web.common.net;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * Base of every packet on the {@code akashicweb} channel. Decoding never throws into FML: a truncated or oversized
 * packet would otherwise surface as a pipeline exception (and on the server, possibly a kick for a harmless client
 * bug). Instead {@link #malformed} is set and the handler drops the message and records {@code BAD_PACKET}. This is
 * the documented boundary for decode errors; nothing else is swallowed.
 */
public abstract class AkwMessage implements IMessage {

    /** Why decoding failed, or null for a good message. */
    public String malformed;

    @Override
    public final void fromBytes(ByteBuf buf) {
        try {
            read(buf);
            if (buf.isReadable()) malformed = buf.readableBytes() + " trailing bytes";
        } catch (CodecException | IndexOutOfBoundsException | IllegalArgumentException e) {
            malformed = e.toString();
        }
    }

    @Override
    public final void toBytes(ByteBuf buf) {
        write(buf);
    }

    protected abstract void read(ByteBuf buf);

    protected abstract void write(ByteBuf buf);

    public boolean ok() {
        return malformed == null;
    }
}
