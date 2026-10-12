package com.akashiic.web.server.command;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;

import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.RightsPolicy;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.server.PlayerDiagnostics;
import com.akashiic.web.server.ScreenIndex;
import com.akashiic.web.server.ScreenSession;
import com.akashiic.web.server.ScreenWorld;
import com.akashiic.web.server.ServerClock;
import com.akashiic.web.server.ServerRuntime;

/** Shared output of /web and /webadmin (lang keys under {@code akashicweb.cmd.*}). */
final class CommandText {

    private CommandText() {}

    static String where(ScreenState s) {
        return s.key.x + " " + s.key.y + " " + s.key.z + " " + side(s.key.side) + " (dim " + s.key.dim + ")";
    }

    static String side(int side) {
        return com.akashiic.web.common.BlockSide.fromSide(side)
            .name()
            .toLowerCase(Locale.ROOT);
    }

    static String player(UUID id) {
        if (id == null) return "-";
        EntityPlayerMP p = ScreenWorld.online(id);
        return p != null ? p.getCommandSenderName() : id.toString();
    }

    static void describe(ICommandSender to, ScreenState s, UUID viewer, boolean admin) {
        header(to, "akashicweb.cmd.info.header", where(s));
        CommandSupport.reply(
            to,
            "akashicweb.cmd.info.size",
            s.widthBlocks,
            s.heightBlocks,
            s.resolutionW,
            s.resolutionH,
            s.rotation);
        CommandSupport.reply(to, "akashicweb.cmd.info.owner", s.ownerName.isEmpty() ? player(s.owner) : s.ownerName);
        CommandSupport.reply(to, "akashicweb.cmd.info.url", s.url.isEmpty() ? "-" : s.url, s.epoch);
        CommandSupport.reply(
            to,
            "akashicweb.cmd.info.state",
            s.mode.name(),
            player(s.controller),
            s.locked ? "yes" : "no",
            s.friends.size());
        if (viewer != null || admin) {
            CommandSupport.reply(to, "akashicweb.cmd.info.rights", RightsPolicy.rights(s, viewer, admin));
        }
        ScreenSession session = ServerRuntime.peekSession(s.key);
        if (session != null && session.clock.valid()) {
            CommandSupport.reply(
                to,
                "akashicweb.cmd.info.clock",
                String.format(Locale.ROOT, "%.1f", session.clock.positionAt(ServerClock.nowMs())),
                session.clock.rate(),
                session.clock.paused() ? "yes" : "no",
                session.clock.live() ? "yes" : "no");
        }
    }

    static void entry(ICommandSender to, ScreenIndex.Entry e) {
        String where = e.key.x + " " + e.key.y + " " + e.key.z + " " + side(e.key.side) + " dim " + e.key.dim;
        String url = e.url.isEmpty() ? "-" : e.url.length() > 60 ? e.url.substring(0, 60) + "..." : e.url;
        to.addChatMessage(
            new ChatComponentText(" " + where + "  " + e.width + "x" + e.height + "  " + e.ownerName + "  " + url));
    }

    static void doctor(ICommandSender to, String name, PlayerDiagnostics.Record r) {
        header(to, "akashicweb.cmd.doctor.header", name);
        if (r == null) {
            CommandSupport.reply(to, "akashicweb.cmd.doctor.nothing");
            return;
        }
        CommandSupport.reply(to, "akashicweb.cmd.doctor.hello", r.helloSentMs == 0 ? "-" : time(r.helloSentMs));
        if (r.preflight.isEmpty()) CommandSupport.reply(to, "akashicweb.cmd.doctor.no_preflight");
        else {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, String> e : r.preflight.entrySet()) {
                if (b.length() > 0) b.append(", ");
                b.append(e.getKey())
                    .append('=')
                    .append(e.getValue());
            }
            CommandSupport.reply(to, "akashicweb.cmd.doctor.preflight", b.toString());
        }
        for (PlayerDiagnostics.Event e : r.recent()) {
            to.addChatMessage(
                new ChatComponentText(" " + time(e.timeMs) + " ")
                    .appendSibling(new ChatComponentTranslation(e.code.langKey(), e.detail))
                    .appendText(" [" + e.code.name() + "]"));
        }
        if (!r.counts()
            .isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<ReasonCode, Integer> e : r.counts()
                .entrySet()) {
                if (b.length() > 0) b.append(", ");
                b.append(
                    e.getKey()
                        .name())
                    .append('=')
                    .append(e.getValue());
            }
            CommandSupport.reply(to, "akashicweb.cmd.doctor.counts", b.toString());
        }
    }

    static void header(ICommandSender to, String key, Object... args) {
        ChatComponentTranslation c = new ChatComponentTranslation(key, args);
        c.getChatStyle()
            .setColor(EnumChatFormatting.GOLD);
        to.addChatMessage(c);
    }

    static String time(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }
}
