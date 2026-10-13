package com.akashiic.web.common.net;

import java.util.concurrent.atomic.AtomicLong;

import com.akashiic.web.AkashicWeb;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Tombstones for the packet ids D-016 retired: S2C 3 (S2CInput), C2S 18 (C2SPointer) and C2S 19 (C2SKey). Input
 * stays on the client now, so nothing sends these. The ids stay registered because FML's indexed codec cannot decode
 * an unknown discriminator: the exception closes the connection, so a peer built before D-016 would be disconnected
 * on its first click. A tombstone skips the payload unread and drops it. Never reuse these ids.
 */
public abstract class RetiredMessage implements IMessage {

    private static final AtomicLong DROPPED = new AtomicLong();

    @Override
    public final void fromBytes(ByteBuf buf) {
        buf.skipBytes(buf.readableBytes());
    }

    /** Never sent; writes nothing if something ever tries. */
    @Override
    public final void toBytes(ByteBuf buf) {}

    /** Retired packets received since start (from peers built before D-016). */
    public static long dropped() {
        return DROPPED.get();
    }

    static IMessage drop(RetiredMessage m) {
        if (DROPPED.getAndIncrement() == 0) {
            AkashicWeb.LOG.info(
                "AkashicWeb: dropped a retired {} from a peer built before D-016 (input stays on the client)",
                m.getClass()
                    .getSimpleName());
        }
        return null;
    }

    /** S2C 3, formerly S2CInput. */
    public static final class Input3 extends RetiredMessage {

        public static final class Handler implements IMessageHandler<Input3, IMessage> {

            @Override
            public IMessage onMessage(Input3 m, MessageContext ctx) {
                return drop(m);
            }
        }
    }

    /** C2S 18, formerly C2SPointer. */
    public static final class Pointer18 extends RetiredMessage {

        public static final class Handler implements IMessageHandler<Pointer18, IMessage> {

            @Override
            public IMessage onMessage(Pointer18 m, MessageContext ctx) {
                return drop(m);
            }
        }
    }

    /** C2S 19, formerly C2SKey. */
    public static final class Key19 extends RetiredMessage {

        public static final class Handler implements IMessageHandler<Key19, IMessage> {

            @Override
            public IMessage onMessage(Key19 m, MessageContext ctx) {
                return drop(m);
            }
        }
    }
}
