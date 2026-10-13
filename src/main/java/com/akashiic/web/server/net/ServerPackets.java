package com.akashiic.web.server.net;

import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.util.FakePlayer;

import com.akashiic.web.AkashicWeb;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.WebConfig;
import com.akashiic.web.common.net.AkwMessage;
import com.akashiic.web.common.net.C2SClockPing;
import com.akashiic.web.common.net.C2SControl;
import com.akashiic.web.common.net.C2SMediaSample;
import com.akashiic.web.common.net.C2SNav;
import com.akashiic.web.common.net.C2SPreflight;
import com.akashiic.web.common.net.C2SRights;
import com.akashiic.web.common.net.C2SSetUrl;
import com.akashiic.web.common.net.S2CClockPong;
import com.akashiic.web.server.ActionLimits;
import com.akashiic.web.server.ScreenActions;
import com.akashiic.web.server.ScreenSync;
import com.akashiic.web.server.ScreenWorld;
import com.akashiic.web.server.ServerActionQueue;
import com.akashiic.web.server.ServerClock;
import com.akashiic.web.server.ServerRuntime;
import com.akashiic.web.server.ServerThread;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Server handlers of the client-to-server packets. In 1.7.10 and on Crucible FML calls them on the server main thread
 * (FMLProxyPacket has no priority, so NetworkManager.processReceivedPackets runs them), at up to 1001 packets per tick
 * per connection; nothing here relies on the thread. They drop FakePlayers, refuse malformed packets, apply the
 * per-player rate limit, then queue the real work, charged to the player, for the start of the next tick
 * ({@link ServerActionQueue}). Refusals are only counted ({@link RejectionCounter}), so a flood costs no queue space.
 * Only the clock ping is answered at once when already on the main thread, because queueing would add up to a tick
 * of hold time to the clock exchange.
 */
public final class ServerPackets {

    static final RejectionCounter REJECTIONS = new RejectionCounter(ServerRuntime.QUEUE);

    private ServerPackets() {}

    static void accept(MessageContext ctx, AkwMessage m, ActionLimits.Kind kind, Consumer<EntityPlayerMP> work) {
        EntityPlayerMP p = ctx.getServerHandler().playerEntity;
        if (p == null) return;
        String action = kind.name()
            .toLowerCase(java.util.Locale.ROOT);
        if (p instanceof FakePlayer) {
            REJECTIONS.add(p, ReasonCode.FAKE_PLAYER, action, "");
            return;
        }
        if (!m.ok()) {
            REJECTIONS.add(p, ReasonCode.BAD_PACKET, action, m.malformed);
            return;
        }
        if (!ServerRuntime.LIMITS.tryAcquire(p.getUniqueID(), kind, WebConfig.get())) {
            REJECTIONS.add(p, ReasonCode.RATE_LIMITED, action, "");
            return;
        }
        // The queue logs drops itself (rate limited). A player who logged out before the next tick is skipped, so
        // nothing re-creates records that logout already dropped.
        ServerRuntime.QUEUE.offer(p.getUniqueID(), () -> { if (ScreenWorld.isOnline(p)) work.accept(p); });
    }

    /** Logout: forget the player's pending refusal counts. */
    public static void forget(UUID player) {
        REJECTIONS.forget(player);
    }

    /** Server stopped. */
    public static void clear() {
        REJECTIONS.clear();
    }

    public static final class SetUrl implements IMessageHandler<C2SSetUrl, IMessage> {

        @Override
        public IMessage onMessage(C2SSetUrl m, MessageContext ctx) {
            accept(ctx, m, ActionLimits.Kind.URL, p -> ScreenActions.setUrl(p, m));
            return null;
        }
    }

    public static final class Nav implements IMessageHandler<C2SNav, IMessage> {

        @Override
        public IMessage onMessage(C2SNav m, MessageContext ctx) {
            accept(ctx, m, ActionLimits.Kind.NAV, p -> ScreenActions.nav(p, m));
            return null;
        }
    }

    public static final class Media implements IMessageHandler<C2SMediaSample, IMessage> {

        @Override
        public IMessage onMessage(C2SMediaSample m, MessageContext ctx) {
            accept(ctx, m, ActionLimits.Kind.MEDIA, p -> ScreenActions.media(p, m));
            return null;
        }
    }

    public static final class Control implements IMessageHandler<C2SControl, IMessage> {

        @Override
        public IMessage onMessage(C2SControl m, MessageContext ctx) {
            accept(ctx, m, ActionLimits.Kind.CONTROL, p -> ScreenActions.control(p, m));
            return null;
        }
    }

    public static final class RightsChanged implements IMessageHandler<C2SRights, IMessage> {

        @Override
        public IMessage onMessage(C2SRights m, MessageContext ctx) {
            accept(ctx, m, ActionLimits.Kind.RIGHTS, p -> ScreenActions.rights(p, m));
            return null;
        }
    }

    /**
     * The pong carries one server stamp (PROTOCOL: nonce, clientSendMs, serverTimeMs). On the main thread it is sent
     * straight away, so receive and send times coincide; otherwise it waits for the next tick and is stamped when
     * sent (the wait then counts as network delay, which the client's min-RTT filter discards).
     */
    public static final class ClockPing implements IMessageHandler<C2SClockPing, IMessage> {

        @Override
        public IMessage onMessage(C2SClockPing m, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().playerEntity;
            if (p == null || p instanceof FakePlayer || !m.ok()) return null;
            if (!ServerRuntime.LIMITS.tryAcquire(p.getUniqueID(), ActionLimits.Kind.PING, WebConfig.get())) return null;
            // ServerClock is the one protocol clock: the same one S2CMediaClock carries and sample stamps estimate.
            Runnable reply = () -> ScreenSync.sendTo(p, new S2CClockPong(m.nonce, m.clientSendMs, ServerClock.nowMs()));
            if (ServerThread.isMain()) reply.run();
            else ServerRuntime.QUEUE.offer(p.getUniqueID(), reply);
            return null;
        }
    }

    public static final class Preflight implements IMessageHandler<C2SPreflight, IMessage> {

        @Override
        public IMessage onMessage(C2SPreflight m, MessageContext ctx) {
            accept(ctx, m, ActionLimits.Kind.PREFLIGHT, p -> {
                ServerRuntime.DIAGNOSTICS.preflight(p.getUniqueID(), m.info, ServerClock.nowMs());
                AkashicWeb.LOG.debug("AkashicWeb: preflight from {}: {}", p.getCommandSenderName(), m.info);
            });
            return null;
        }
    }
}
