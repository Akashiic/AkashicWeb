package com.akashiic.web.server;

import java.util.Iterator;
import java.util.List;

import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;

import com.akashiic.web.common.ModContent;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.WebConfig;

/**
 * Keeps formed screens standing against everything that is not a player breaking a block (config
 * {@code protectScreens}): explosions (TNT, creepers, ghasts, wither skulls, modded blasts built on vanilla's
 * Explosion) and the wither's and the ender dragon's block breaking. Players go through
 * {@link ServerEvents#onBreak}, which lets the owner and admins break. Review finding: these broke any screen, so a
 * griefer outside protected regions could destroy screens with a creeper or TNT.
 * <p>
 * Only screens with an owner are protected, as for players. Server side only; the client never decides.
 */
public final class ScreenProtection {

    /** Whether a block position is protected; a seam so the list filter is tested without a world. */
    interface Shield {

        boolean covers(int x, int y, int z);
    }

    private ScreenProtection() {}

    /** A screen block of a formed screen with an owner, while protectScreens is on (server worlds only). */
    public static boolean shielded(World w, int x, int y, int z) {
        if (w == null || w.isRemote || !WebConfig.get().protectScreens) return false;
        if (w.getBlock(x, y, z) != ModContent.screenBlock) return false;
        return owned(ServerRuntime.LOADED.containing(w.provider.dimensionId, x, y, z, -1));
    }

    /** At least one of the screens containing a block has an owner. */
    static boolean owned(List<ScreenState> containing) {
        for (ScreenState s : containing) if (s.owner != null) return true;
        return false;
    }

    /**
     * Removes the shielded positions from an explosion's block list (ExplosionEvent.Detonate), so the blast goes on
     * around the screen without breaking it. Returns how many were spared.
     */
    static int spare(List<ChunkPosition> affected, Shield shield) {
        int spared = 0;
        for (Iterator<ChunkPosition> it = affected.iterator(); it.hasNext();) {
            ChunkPosition p = it.next();
            if (shield.covers(p.chunkPosX, p.chunkPosY, p.chunkPosZ)) {
                it.remove();
                spared++;
            }
        }
        return spared;
    }

    /** The Forge event's list, for a server world. */
    static int spare(World w, List<ChunkPosition> affected) {
        if (w == null || w.isRemote || affected == null || !WebConfig.get().protectScreens) return 0;
        return spare(affected, (x, y, z) -> shielded(w, x, y, z));
    }
}
