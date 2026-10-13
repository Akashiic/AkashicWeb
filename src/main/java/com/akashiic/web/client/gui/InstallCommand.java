package com.akashiic.web.client.gui;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;

/**
 * {@code /awinstall}: opens the install prompt in any state (install, progress, cancel, repair, why the host does not
 * run). The way back after "Not now", which stops right-clicks from asking. A client command: the engine lives on the
 * player's PC.
 */
public final class InstallCommand extends CommandBase {

    public static final String NAME = "awinstall";

    private final Runnable open;

    /** @param open opens the prompt; called from the command, it must defer to the next tick (see ClientWiring). */
    public InstallCommand(Runnable open) {
        this.open = open;
    }

    @Override
    public String getCommandName() {
        return NAME;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/" + NAME;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true; // runs on the player's own client
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        open.run();
    }
}
