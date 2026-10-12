package com.akashiic.web.server.command;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandManager;

import com.akashiic.web.AkashicWeb;
import com.akashiic.web.server.Admins;

import cpw.mods.fml.common.event.FMLServerStartingEvent;

/**
 * Registers /web and /webadmin. On Crucible (Cauldron family) the command handler has
 * {@code registerCommand(String group, ICommand)}, which gates the command behind a Bukkit node named after the group,
 * so we pass the stable nodes {@code akashicweb.web} and {@code akashicweb.webadmin}. Without it (plain Forge) the
 * standard {@code registerServerCommand} is used. Found by reflection: no compile dependency on Crucible (ADR-010).
 * The exact Crucible signature is INFERRED from its patches and must be confirmed by the Crucible boot test.
 */
public final class CommandRegistrar {

    private CommandRegistrar() {}

    public static void register(FMLServerStartingEvent event) {
        String a = register(event, new WebCommand(), Admins.NODE_WEB);
        String b = register(event, new WebAdminCommand(), Admins.NODE_WEBADMIN);
        WebAdminCommand.registration = a.equals(b) ? a : a + "/" + b;
    }

    private static String register(FMLServerStartingEvent event, ICommand command, String node) {
        ICommandManager manager = event.getServer()
            .getCommandManager();
        Method grouped = groupedRegister(manager.getClass());
        if (grouped != null) {
            try {
                grouped.invoke(manager, node, command);
                AkashicWeb.LOG
                    .info("AkashicWeb: /{} registered with permission node {}", command.getCommandName(), node);
                return "group";
            } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException e) {
                AkashicWeb.LOG.warn(
                    "AkashicWeb: grouped registration of /{} failed ({}); using the standard one",
                    command.getCommandName(),
                    e.toString());
            }
        }
        event.registerServerCommand(command);
        return "forge";
    }

    /** The Crucible overload (public, searched through superclasses by getMethod), or null on plain Forge. */
    private static Method groupedRegister(Class<?> type) {
        try {
            return type.getMethod("registerCommand", String.class, ICommand.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
