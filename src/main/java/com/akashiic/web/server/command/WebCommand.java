package com.akashiic.web.server.command;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.util.FakePlayer;

import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.WebConfig;
import com.akashiic.web.common.net.C2SControl;
import com.akashiic.web.server.Admins;
import com.akashiic.web.server.ScreenActions;
import com.akashiic.web.server.ServerRuntime;

/**
 * {@code /web}: for players. {@code info} about the screen you look at, {@code take}/{@code release} its controller
 * lock (same rules as the GUI: CLICK_TYPE, owner/admin pre-empt), {@code doctor} for your own diagnostics. On
 * Crucible it is gated by the node {@code akashicweb.web} (Bukkit's default for an unregistered node is ops only, so
 * the owner grants it to the default group in LuckPerms).
 */
public final class WebCommand extends CommandSupport {

    static final String USAGE = "akashicweb.cmd.web.usage";
    private static final List<String> SUBCOMMANDS = Arrays.asList("info", "take", "release", "doctor");

    @Override
    public String getCommandName() {
        return "web";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return USAGE;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    protected void execute(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP) || sender instanceof FakePlayer) {
            error(sender, "akashicweb.cmd.players_only");
            return;
        }
        EntityPlayerMP p = (EntityPlayerMP) sender;
        if (args.length != 1) throw new WrongUsageException(USAGE);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("doctor")) {
            CommandText.doctor(p, p.getCommandSenderName(), ServerRuntime.DIAGNOSTICS.peek(p.getUniqueID()));
            return;
        }
        if (!SUBCOMMANDS.contains(sub)) throw new WrongUsageException(USAGE);
        ScreenState s = ScreenTargets.lookedAt(p);
        if (s == null) {
            error(p, "akashicweb.cmd.no_screen");
            return;
        }
        switch (sub) {
            case "info":
                CommandText.describe(p, s, p.getUniqueID(), Admins.isAdmin(p, WebConfig.get()));
                return;
            case "take":
            case "release":
                ScreenActions.control(p, new C2SControl(s.key, sub.equals("take")));
                reply(p, "akashicweb.cmd.control.now", CommandText.player(s.controller));
                return;
            default:
                throw new WrongUsageException(USAGE);
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        return args.length == 1 ? getListOfStringsFromIterableMatchingLastWord(args, SUBCOMMANDS) : null;
    }
}
