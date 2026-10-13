package com.akashiic.web.server.command;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;

import net.minecraft.command.ICommand;

import com.akashiic.web.AkashicWeb;
import com.akashiic.web.server.Admins;

import cpw.mods.fml.common.event.FMLServerStartingEvent;

/**
 * Registers /web and /webadmin. On Crucible (Cauldron family) the command handler has
 * {@code registerCommand(String group, ICommand)}, which gates the command behind the Bukkit node
 * {@code <group>.<command name>}, so we pass the group {@link Admins#NODE_GROUP} and get the stable nodes
 * {@code akashicweb.web} and {@code akashicweb.webadmin}. LIVE-OBSERVED on Crucible staging-67b53034 (lead's boot log,
 * 2026-10-13): passing the full node as the group gave {@code akashicweb.web.web}, which no grant matched. Without the
 * method (plain Forge) the standard {@code registerServerCommand} is used. Found by reflection: no compile dependency
 * on Crucible (ADR-010).
 */
public final class CommandRegistrar {

    private CommandRegistrar() {}

    public static void register(FMLServerStartingEvent event) {
        Object manager = event.getServer()
            .getCommandManager();
        String a = register(manager, new WebCommand(), event::registerServerCommand);
        String b = register(manager, new WebAdminCommand(), event::registerServerCommand);
        WebAdminCommand.registration = a.equals(b) ? a : a + "/" + b;
    }

    /**
     * Registers one command through the grouped Crucible method when the manager has it, else through
     * {@code fallback}. Returns how: "group" or "forge".
     */
    static String register(Object manager, ICommand command, Consumer<ICommand> fallback) {
        Method grouped = groupedRegister(manager.getClass());
        if (grouped != null) {
            try {
                grouped.invoke(manager, Admins.NODE_GROUP, command);
                AkashicWeb.LOG.info(
                    "AkashicWeb: /{} registered with Bukkit permission node {}",
                    command.getCommandName(),
                    node(command));
                return "group";
            } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException e) {
                AkashicWeb.LOG.warn(
                    "AkashicWeb: grouped registration of /{} failed ({}); using the standard one",
                    command.getCommandName(),
                    e.toString());
            }
        }
        fallback.accept(command);
        return "forge";
    }

    /** The node Crucible builds for a command registered in our group: group + "." + command name. */
    static String node(ICommand command) {
        return Admins.NODE_GROUP + "." + command.getCommandName();
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
