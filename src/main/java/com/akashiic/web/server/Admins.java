package com.akashiic.web.server;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.FakePlayer;

import com.akashiic.web.common.WebSettings;
import com.mojang.authlib.GameProfile;

/**
 * Who is a screen admin (ADR-010): a server operator (if {@code opsAreAdmins}), a player holding the Bukkit node
 * {@code akashicweb.admin} (Crucible + LuckPerms, by reflection on the main thread), or a UUID in the config list.
 * FakePlayers never are. Re-checked on every action and inside every command, because Crucible bypasses
 * canCommandSenderUseCommand for mod commands.
 */
public final class Admins {

    public static final String NODE_ADMIN = "akashicweb.admin";
    /** Crucible command group: it builds each command's node as group + "." + command name. */
    public static final String NODE_GROUP = "akashicweb";
    /** Crucible node of /webadmin; holding it also grants the command. */
    public static final String NODE_WEBADMIN = NODE_GROUP + ".webadmin";
    /** Crucible node of /web (Bukkit's default for it is ops only: grant it to the default group). */
    public static final String NODE_WEB = NODE_GROUP + ".web";

    private Admins() {}

    public static boolean isAdmin(EntityPlayer player, WebSettings cfg) {
        if (!(player instanceof EntityPlayerMP) || player instanceof FakePlayer) return false;
        if (player.getUniqueID() != null && cfg.adminUuids.contains(player.getUniqueID())) return true;
        if (cfg.opsAreAdmins && isOp(player)) return true;
        return Boolean.TRUE.equals(BukkitBridge.hasPermission((EntityPlayerMP) player, NODE_ADMIN));
    }

    /** /webadmin: console and RCON always; players if admin or holding the command node. */
    public static boolean mayUseAdminCommand(ICommandSender sender, WebSettings cfg) {
        if (!(sender instanceof EntityPlayer)) return true;
        EntityPlayer p = (EntityPlayer) sender;
        return isAdmin(p, cfg) || (p instanceof EntityPlayerMP && !(p instanceof FakePlayer)
            && Boolean.TRUE.equals(BukkitBridge.hasPermission((EntityPlayerMP) p, NODE_WEBADMIN)));
    }

    /**
     * Real operator. Never throws: FakePlayers and profiles without a UUID are simply not ops (copied from AkashicFM
     * Permissions.isOp).
     */
    public static boolean isOp(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP) || player instanceof FakePlayer) return false;
        GameProfile profile = player.getGameProfile();
        if (profile == null || profile.getId() == null || profile.getName() == null) return false;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return false;
        return server.getConfigurationManager()
            .func_152596_g(profile);
    }
}
