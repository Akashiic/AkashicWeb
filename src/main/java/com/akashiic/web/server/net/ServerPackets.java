package com.akashiic.web.server.net;

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
import com.akashiic.web.server.ServerActionQueue;
import com.akashiic.web.server.ServerClock;
import com.akashiic.web.server.ServerRuntime;
import com.akashiic.web.server.ServerThread;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Server handlers of the client-to-server packets. They run wherever FML calls them (the main thread in 1.7.10, but
 * nothing relies on it): drop FakePlayers, refuse malformed packets, apply the per-player rate limit, then queue the
 * real work for the start of the next tick ({@link ServerActionQueue}). Only the clock ping is answered at once when
 * already on the main thread, because queueing would add up to a tick of hold time to the clock exchange.
 */
public final class ServerPackets {

    private ServerPackets() {}

    static void accept(MessageContext ctx, AkwMessage m, ActionLimits.Kind kind, Consumer<EntityPlayerMP> work) {
        EntityPlayerMP p = ctx.getServerHandler().playerEntity;
        if (p == null) return;
        if (p instanceof FakePlayer) {
            // Diagnostics are main-thread state: record the refusal there.
            ServerRuntime.QUEUE.offer(
                () -> ServerRuntime.DIAGNOSTICS
                    .record(p.getUniqueID(), ReasonCode.FAKE_PLAYER, kind.name(), ServerClock.nowMs()));
            return;
        }
        String action = kind.name()
            .toLowerCase(java.util.Locale.ROOT);
        if (!m.ok()) {
            String why = m.malformed;
            ServerRuntime.QUEUE.offer(() -> rejected(p, ReasonCode.BAD_PACKET, action, why));
            return;
        }
        if (!ServerRuntime.LIMITS.tryAcquire(p.getUniqueID(), kind, WebConfig.get())) {
            ServerRuntime.QUEUE.offer(() -> rejected(p, ReasonCode.RATE_LIMITED, action, ""));
            return;
        }
        if (!ServerRuntime.QUEUE.offer(() -> work.accept(p))) {
            AkashicWeb.LOG.warn("AkashicWeb: action queue full, dropped {} from {}", action, p.getCommandSenderName());
        }
    }

    /** Main thread. A sample flood must not flood notices: RATE_LIMITED on media/ping is recorded quietly. */
    private static void rejected(EntityPlayerMP p, ReasonCode code, String action, String detail) {
        boolean quiet = "media".equals(action) || "ping".equals(action);
        ScreenActions.reject(p, code, action, detail, quiet);
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
            Runnable reply = () -> ScreenSync.sendTo(p, new S2CClockPong(m.nonce, m.clientSendMs, ServerClock.nowMs()));
            if (ServerThread.isMain()) reply.run();
            else ServerRuntime.QUEUE.offer(reply);
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
