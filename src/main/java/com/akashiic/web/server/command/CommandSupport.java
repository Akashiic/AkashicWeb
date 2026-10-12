package com.akashiic.web.server.command;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import com.akashiic.web.server.AuditLog;
import com.akashiic.web.server.ServerThread;

/**
 * RCON-safe base for /web and /webadmin. Everything a command does touches tile entities, the index or the audit
 * log, which only the main thread may touch. The 1.7.10 RCON thread runs commands on its own thread, so such a call
 * is queued for the next tick and the RCON thread waits for the result (replies still reach RCON).
 * <p>
 * Copied from AkashicFM (same owner) {@code com.akashiic.fm.server.FmCommand} (processCommand threading, reply
 * helpers).
 */
public abstract class CommandSupport extends CommandBase {

    static final long OTHER_THREAD_WAIT_MS = 10_000;
    private static final Queue<Runnable> FROM_OTHER_THREADS = new ConcurrentLinkedQueue<>();

    /** Start of the server tick: runs commands that arrived on another thread. */
    public static void runQueued() {
        for (Runnable r; (r = FROM_OTHER_THREADS.poll()) != null;) r.run();
    }

    public static void clear() {
        FROM_OTHER_THREADS.clear();
    }

    @Override
    public final void processCommand(ICommandSender sender, String[] args) {
        if (ServerThread.unknown() || ServerThread.isMain()) {
            execute(sender, args);
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        FROM_OTHER_THREADS.add(() -> {
            try {
                execute(sender, args);
            } catch (RuntimeException e) {
                failure.set(e); // rethrown on the caller's thread below, where the command handler reports it
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(OTHER_THREAD_WAIT_MS, TimeUnit.MILLISECONDS)) {
                reply(sender, new ChatComponentTranslation("akashicweb.cmd.queued"));
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            return;
        }
        if (failure.get() != null) throw failure.get();
    }

    /** Main thread. Must re-check rights itself: Crucible bypasses canCommandSenderUseCommand for mod commands. */
    protected abstract void execute(ICommandSender sender, String[] args);

    protected static void reply(ICommandSender sender, IChatComponent msg) {
        sender.addChatMessage(msg);
    }

    protected static void reply(ICommandSender sender, String key, Object... args) {
        sender.addChatMessage(new ChatComponentTranslation(key, args));
    }

    protected static void error(ICommandSender sender, String key, Object... args) {
        ChatComponentTranslation c = new ChatComponentTranslation(key, args);
        c.getChatStyle()
            .setColor(EnumChatFormatting.RED);
        sender.addChatMessage(c);
    }

    protected static void audit(ICommandSender sender, String action, String details) {
        UUID id = sender instanceof EntityPlayer ? ((EntityPlayer) sender).getUniqueID() : null;
        AuditLog.log(sender.getCommandSenderName(), id, action, details);
    }

    protected static UUID senderId(ICommandSender sender) {
        return sender instanceof EntityPlayer ? ((EntityPlayer) sender).getUniqueID() : null;
    }
}
