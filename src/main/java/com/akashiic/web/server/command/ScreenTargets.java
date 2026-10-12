package com.akashiic.web.server.command;

import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.HitMath;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.server.ScreenWorld;
import com.akashiic.web.server.ServerRuntime;

/**
 * Which screen a command means: the one the player is looking at, or explicit coordinates (any block of the screen)
 * with an optional side and dimension, which is how console and RCON address screens. Loaded screens only: a
 * command never loads a chunk.
 */
final class ScreenTargets {

    /** How far a player may be looking at a screen for a command. */
    static final double LOOK_RANGE = 64;

    private ScreenTargets() {}

    /** The loaded screen face hit first by the player's eye ray, or null. */
    static ScreenState lookedAt(EntityPlayerMP p) {
        double[] eye = ScreenWorld.eye(p), look = ScreenWorld.look(p);
        ScreenState best = null;
        double bestDist = Double.MAX_VALUE;
        for (ScreenState s : ServerRuntime.LOADED.faces()) {
            if (s.key.dim != p.dimension) continue;
            double[] hit = HitMath.rayHit(s, eye[0], eye[1], eye[2], look[0], look[1], look[2], LOOK_RANGE);
            if (hit != null && hit[2] < bestDist) {
                bestDist = hit[2];
                best = s;
            }
        }
        return best;
    }

    /**
     * {@code [x y z [side] [dim]]} starting at {@code from}; without coordinates, the screen a player looks at.
     * Returns null when nothing matches (the caller says so); throws WrongUsageException for bad syntax.
     */
    static ScreenState resolve(ICommandSender sender, String[] args, int from, String usage) {
        if (args.length - from >= 3) {
            int x = CommandBase.parseInt(sender, args[from]);
            int y = CommandBase.parseInt(sender, args[from + 1]);
            int z = CommandBase.parseInt(sender, args[from + 2]);
            int side = -1;
            if (args.length - from >= 4) {
                BlockSide bs = BlockSide.parse(args[from + 3]);
                if (bs == null) throw new WrongUsageException(usage);
                side = bs.side();
            }
            int dim;
            if (args.length - from >= 5) dim = CommandBase.parseInt(sender, args[from + 4]);
            else dim = sender instanceof EntityPlayerMP ? ((EntityPlayerMP) sender).dimension : 0;
            List<ScreenState> found = ServerRuntime.LOADED.containing(dim, x, y, z, side);
            return found.isEmpty() ? null : found.get(0);
        }
        if (args.length > from) throw new WrongUsageException(usage);
        return sender instanceof EntityPlayerMP ? lookedAt((EntityPlayerMP) sender) : null;
    }
}
