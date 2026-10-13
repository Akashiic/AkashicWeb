package com.akashiic.web.server.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.WebConfig;
import com.akashiic.web.common.WebSettings;
import com.akashiic.web.protocol.Protocol;
import com.akashiic.web.server.Admins;
import com.akashiic.web.server.BukkitBridge;
import com.akashiic.web.server.ScreenActions;
import com.akashiic.web.server.ScreenIndex;
import com.akashiic.web.server.ScreenSync;
import com.akashiic.web.server.ScreenWorld;
import com.akashiic.web.server.ServerRuntime;

/**
 * {@code /webadmin}: screen moderation for admins (op, Bukkit {@code akashicweb.admin} or {@code akashicweb.webadmin},
 * or the config list; console and RCON always). Every action that changes something goes to the audit log. Targets
 * are the screen the admin looks at or {@code x y z [side] [dim]}; only loaded screens can be changed (a command
 * never loads chunks).
 */
public final class WebAdminCommand extends CommandSupport {

    static final String USAGE = "akashicweb.cmd.admin.usage";
    static final int LIST_LIMIT = 20;
    private static final List<String> SUBCOMMANDS = Arrays
        .asList("list", "info", "clear", "lock", "unlock", "takeover", "kill", "policy", "doctor", "status");

    /** How the command was registered (status output). */
    static volatile String registration = "-";

    @Override
    public String getCommandName() {
        return "webadmin";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return USAGE;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    /** Plain Forge consults this; Crucible does not (hence the re-check in {@link #execute}). */
    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return Admins.mayUseAdminCommand(sender, WebConfig.get());
    }

    @Override
    protected void execute(ICommandSender sender, String[] args) {
        WebSettings cfg = WebConfig.get();
        if (!Admins.mayUseAdminCommand(sender, cfg)) {
            error(sender, "akashicweb.reason.no_permission", "/webadmin");
            return;
        }
        if (args.length == 0) throw new WrongUsageException(USAGE);
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list":
                list(sender, args);
                return;
            case "info":
            case "clear":
            case "lock":
            case "unlock":
            case "takeover":
                onScreen(sender, sub, args, cfg);
                return;
            case "kill":
                kill(sender, args);
                return;
            case "policy":
                policy(sender, args);
                return;
            case "doctor":
                doctor(sender, args);
                return;
            case "status":
                status(sender, cfg);
                return;
            default:
                throw new WrongUsageException(USAGE);
        }
    }

    private void list(ICommandSender sender, String[] args) {
        ScreenIndex index = ServerRuntime.index();
        Collection<ScreenIndex.Entry> entries;
        if (args.length >= 2) {
            if (!(sender instanceof EntityPlayerMP)) throw new WrongUsageException(USAGE);
            double radius = parseDoubleBounded(sender, args[1], 1, 100_000);
            EntityPlayerMP p = (EntityPlayerMP) sender;
            entries = index.within(p.dimension, p.posX, p.posY, p.posZ, radius);
        } else {
            entries = index.all();
        }
        reply(sender, "akashicweb.cmd.list.header", entries.size(), index.size());
        int shown = 0;
        for (ScreenIndex.Entry e : entries) {
            if (shown++ >= LIST_LIMIT) {
                reply(sender, "akashicweb.cmd.list.more", entries.size() - LIST_LIMIT);
                break;
            }
            CommandText.entry(sender, e);
        }
    }

    private void onScreen(ICommandSender sender, String sub, String[] args, WebSettings cfg) {
        ScreenState s = ScreenTargets.resolve(sender, args, 1, USAGE);
        if (s == null) {
            error(sender, "akashicweb.cmd.no_screen");
            return;
        }
        switch (sub) {
            case "info":
                CommandText.describe(sender, s, senderId(sender), true);
                return;
            case "clear":
                ScreenActions.clear(s, sender.getCommandSenderName(), senderId(sender));
                reply(sender, "akashicweb.cmd.cleared", CommandText.where(s));
                return;
            case "lock":
            case "unlock":
                ScreenActions.setLocked(s, sub.equals("lock"), sender.getCommandSenderName(), senderId(sender));
                reply(
                    sender,
                    sub.equals("lock") ? "akashicweb.cmd.locked" : "akashicweb.cmd.unlocked",
                    CommandText.where(s));
                return;
            case "takeover":
                if (!(sender instanceof EntityPlayerMP) || ((EntityPlayerMP) sender).dimension != s.key.dim) {
                    error(sender, "akashicweb.cmd.players_only");
                    return;
                }
                ScreenActions.takeover((EntityPlayerMP) sender, s);
                reply(sender, "akashicweb.cmd.takeover", CommandText.where(s));
                return;
            default:
                throw new WrongUsageException(USAGE);
        }
    }

    private void kill(ICommandSender sender, String[] args) {
        if (args.length != 2) throw new WrongUsageException(USAGE);
        boolean on;
        if (args[1].equalsIgnoreCase("on")) on = true;
        else if (args[1].equalsIgnoreCase("off")) on = false;
        else throw new WrongUsageException(USAGE);
        WebConfig.setKillSwitch(on);
        audit(sender, "admin kill switch", on ? "on" : "off");
        ScreenSync.helloAll();
        reply(sender, on ? "akashicweb.cmd.kill.on" : "akashicweb.cmd.kill.off");
    }

    private void policy(ICommandSender sender, String[] args) {
        if (args.length != 2 || !args[1].equalsIgnoreCase("reload")) throw new WrongUsageException(USAGE);
        if (!WebConfig.reload()) {
            error(sender, "akashicweb.cmd.policy.failed");
            return;
        }
        WebSettings cfg = WebConfig.get();
        audit(sender, "admin policy reload", cfg.sitePolicy.toString());
        ScreenSync.helloAll();
        reply(
            sender,
            "akashicweb.cmd.policy.reloaded",
            cfg.sitePolicy.mode.name(),
            cfg.sitePolicy.allow.size(),
            cfg.sitePolicy.block.size());
    }

    private void doctor(ICommandSender sender, String[] args) {
        if (args.length != 2) throw new WrongUsageException(USAGE);
        MinecraftServer server = MinecraftServer.getServer();
        EntityPlayerMP target = server == null ? null
            : server.getConfigurationManager()
                .func_152612_a(args[1]);
        if (target == null) {
            error(sender, "akashicweb.reason.unknown_player", args[1]);
            return;
        }
        CommandText.doctor(sender, target.getCommandSenderName(), ServerRuntime.DIAGNOSTICS.peek(target.getUniqueID()));
    }

    private void status(ICommandSender sender, WebSettings cfg) {
        CommandText.header(sender, "akashicweb.cmd.status.header", Protocol.MAJOR + "." + Protocol.MINOR);
        reply(sender, "akashicweb.cmd.status.kill", cfg.killSwitch ? "ON" : "off");
        reply(
            sender,
            "akashicweb.cmd.status.policy",
            cfg.sitePolicy.mode.name(),
            cfg.sitePolicy.allow.size(),
            cfg.sitePolicy.block.size());
        reply(
            sender,
            "akashicweb.cmd.status.screens",
            ServerRuntime.LOADED.faces()
                .size(),
            ServerRuntime.index()
                .size(),
            ServerRuntime.sessions());
        reply(sender, "akashicweb.cmd.status.queue", ServerRuntime.QUEUE.stats());
        reply(
            sender,
            "akashicweb.cmd.status.perms",
            BukkitBridge.state()
                .name(),
            registration);
        reply(
            sender,
            "akashicweb.cmd.status.players",
            ScreenWorld.players()
                .size());
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) return getListOfStringsFromIterableMatchingLastWord(args, SUBCOMMANDS);
        if (args.length == 2 && "kill".equalsIgnoreCase(args[0]))
            return getListOfStringsMatchingLastWord(args, "on", "off");
        if (args.length == 2 && "policy".equalsIgnoreCase(args[0]))
            return getListOfStringsMatchingLastWord(args, "reload");
        if (args.length == 2 && "doctor".equalsIgnoreCase(args[0])) {
            List<String> names = new ArrayList<>();
            for (EntityPlayerMP p : ScreenWorld.players()) names.add(p.getCommandSenderName());
            return getListOfStringsFromIterableMatchingLastWord(args, names);
        }
        return null;
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return index == 1 && args.length > 0 && "doctor".equalsIgnoreCase(args[0]);
    }
}
