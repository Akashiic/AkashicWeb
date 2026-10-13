package com.akashiic.web.common.block;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.ScreenFormation;
import com.akashiic.web.common.ScreenState;

/**
 * Finds the formed screen a block belongs to by walking left and down over screen blocks to candidate origins
 * (only blocks flagged as origins are inspected). Used on the client, which has no screen registry; the server uses
 * its own index of loaded screens instead. Never loads chunks: an unloaded block ends the walk.
 */
public final class ScreenLocator {

    /** Longest walk in each direction (largest screen side the server can allow). */
    static final int MAX_SPAN = 64;

    private ScreenLocator() {}

    public static ScreenState find(World w, Block screenBlock, int x, int y, int z, BlockSide side) {
        for (int i = 0; i < MAX_SPAN; i++) {
            int cx = x - side.rightX * i, cy = y - side.rightY * i, cz = z - side.rightZ * i;
            if (!isScreen(w, screenBlock, cx, cy, cz)) break;
            for (int j = 0; j < MAX_SPAN; j++) {
                int px = cx - side.upX * j, py = cy - side.upY * j, pz = cz - side.upZ * j;
                if (!isScreen(w, screenBlock, px, py, pz)) break;
                if (!BlockScreen.isOrigin(w.getBlockMetadata(px, py, pz))) continue;
                TileEntity te = w.getTileEntity(px, py, pz);
                if (!(te instanceof TileEntityScreen)) continue;
                ScreenState s = ((TileEntityScreen) te).screen(side.side());
                if (s != null && ScreenFormation.contains(px, py, pz, side, s.widthBlocks, s.heightBlocks, x, y, z)) {
                    return s;
                }
            }
        }
        return null;
    }

    private static boolean isScreen(World w, Block screenBlock, int x, int y, int z) {
        return w.blockExists(x, y, z) && w.getBlock(x, y, z) == screenBlock;
    }
}
