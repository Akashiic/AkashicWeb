package com.akashiic.web.client.gui;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

/**
 * Client-side diagnostics, {@code /awdoctor}: host and engine state, clock sync, upload timings (p50/p95/p99, MB/s,
 * skipped and dropped frames), Angelica registration and every tracked screen. A client command because these numbers
 * live on the player's PC; the server's {@code /web doctor} (M4) cannot see them. English on purpose: it is pasted
 * into bug reports. Lines carry no player identifiers beyond screen coordinates.
 */
public final class DoctorCommand extends CommandBase {

    public static final String NAME = "awdoctor";

    private final Supplier<List<String>> lines;

    public DoctorCommand(Supplier<List<String>> lines) {
        this.lines = lines;
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
        for (String line : lines.get()) sender.addChatMessage(new ChatComponentText(line));
    }
}
