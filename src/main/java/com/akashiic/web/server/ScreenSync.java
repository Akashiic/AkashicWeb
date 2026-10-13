package com.akashiic.web.server;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.Packet;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.WorldServer;

import com.akashiic.web.AkashicWeb;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.WebConfig;
import com.akashiic.web.common.WebSettings;
import com.akashiic.web.common.block.TileEntityScreen;
import com.akashiic.web.common.net.S2CHello;
import com.akashiic.web.common.net.S2CNotice;
import com.akashiic.web.common.net.S2CScreenState;
import com.akashiic.web.common.net.S2CUrl;
import com.akashiic.web.common.net.WebNetwork;
import com.akashiic.web.protocol.Protocol;

import cpw.mods.fml.common.network.simpleimpl.IMessage;

/**
 * Everything the server sends. Sends happen on the main thread only (the 1.7.10 channel is not safe from two
 * threads); a call from elsewhere is queued for the next tick instead. Full-state broadcasts go out as the tile
 * entity's description packet so the client tile (read by the renderer) and M5's hooks stay in step.
 */
public final class ScreenSync {

    private ScreenSync() {}

    /** Runs now on the main thread, otherwise at the start of the next tick. */
    static void onMain(Runnable r) {
        if (ServerThread.isMain()) r.run();
        else ServerRuntime.QUEUE.offer(r);
    }

    public static void sendTo(EntityPlayerMP p, IMessage m) {
        if (p == null || p.playerNetServerHandler == null || WebNetwork.channel() == null) return;
        if (!ServerThread.isMain()) {
            ServerRuntime.QUEUE.offer(() -> sendTo(p, m));
            return;
        }
        WebNetwork.channel()
            .sendTo(m, p);
    }

    /** To every player whose client has the screen's chunk. */
    public static void sendToTrackers(ScreenKey key, IMessage m) {
        WorldServer w = ScreenWorld.world(key.dim);
        if (w == null) return;
        for (EntityPlayerMP p : ScreenWorld.trackers(w, key.x, key.z)) sendTo(p, m);
    }

    /** Full state of every face of a tile to its trackers (description packet: updates the client tile too). */
    public static void sendDescription(TileEntityScreen te) {
        onMain(() -> {
            WorldServer w = ScreenWorld.world(te.keyFor(0).dim);
            Packet packet = te.getDescriptionPacket();
            if (w == null || packet == null) return;
            for (EntityPlayerMP p : ScreenWorld.trackers(w, te.xCoord, te.zCoord)) {
                p.playerNetServerHandler.sendPacket(packet);
            }
        });
    }

    public static void sendUrl(ScreenState s) {
        sendToTrackers(s.key, new S2CUrl(s.key, s.url, s.epoch));
    }

    /** One player's view of a screen, e.g. after their change was refused so their GUI reverts. */
    public static void resync(EntityPlayerMP p, ScreenState s) {
        sendTo(p, new S2CScreenState(s.copy()));
    }

    /**
     * Refusal or event for a GUI-driven action: S2CNotice (M5 shows it) and the player's diagnostics. Reason texts take
     * exactly one argument, the detail (possibly empty), on every path: notice, chat and /web doctor.
     */
    public static void notice(EntityPlayerMP p, ReasonCode code, String detail) {
        String d = detail == null ? "" : detail;
        ServerRuntime.DIAGNOSTICS.record(p.getUniqueID(), code, d, ServerClock.nowMs());
        sendTo(p, new S2CNotice(code, d));
    }

    /** Result of a world interaction (forming a screen, breaking a protected block): a chat line, no GUI is open. */
    public static void tell(EntityPlayerMP p, ReasonCode code, String detail) {
        String d = detail == null ? "" : detail;
        if (code != ReasonCode.OK) ServerRuntime.DIAGNOSTICS.record(p.getUniqueID(), code, d, ServerClock.nowMs());
        ChatComponentTranslation msg = new ChatComponentTranslation(code.langKey(), d);
        msg.getChatStyle()
            .setColor(EnumChatFormatting.RED);
        p.addChatMessage(msg);
    }

    public static void chat(EntityPlayerMP p, String langKey, Object... args) {
        ChatComponentTranslation msg = new ChatComponentTranslation(langKey, args);
        msg.getChatStyle()
            .setColor(EnumChatFormatting.AQUA);
        p.addChatMessage(msg);
    }

    /**
     * S2CHello for one player (login, policy reload, kill switch). It carries the player's UUID as this server knows
     * it, which is what ScreenState.controller holds (D-016: the client recognises itself as the controller with it).
     */
    public static void hello(EntityPlayerMP p) {
        WebSettings cfg = WebConfig.get();
        long now = ServerClock.nowMs();
        ServerRuntime.DIAGNOSTICS.of(p.getUniqueID()).helloSentMs = now;
        sendTo(p, new S2CHello(Protocol.MAJOR, Protocol.MINOR, cfg.killSwitch, cfg.sitePolicy, now, p.getUniqueID()));
    }

    /** S2CHello for everyone (policy reload, kill switch). */
    public static void helloAll() {
        List<EntityPlayerMP> players = ScreenWorld.players();
        for (EntityPlayerMP p : players) hello(p);
        AkashicWeb.LOG.info("AkashicWeb: hello re-sent to {} players", players.size());
    }
}
