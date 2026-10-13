package com.akashiic.web.server;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.akashiic.web.common.BlockQuery;
import com.akashiic.web.common.HitMath;
import com.akashiic.web.common.ModContent;
import com.akashiic.web.common.block.TileEntityScreen;

/**
 * The few places the server logic touches Minecraft's world: chunk-loaded checks (nothing here ever loads or
 * generates a chunk), block queries, tile lookups, trackers and player eye positions. Main thread only.
 */
public final class ScreenWorld {

    private ScreenWorld() {}

    /** The loaded world of a dimension, or null. */
    public static WorldServer world(int dim) {
        return DimensionManager.getWorld(dim);
    }

    public static boolean loaded(World w, int x, int z) {
        return w.getChunkProvider()
            .chunkExists(x >> 4, z >> 4);
    }

    /** Screen-block query over a world; unloaded chunks answer UNLOADED instead of being loaded. */
    public static BlockQuery query(World w) {
        return (x, y, z) -> {
            if (y < 0 || y > 255) return BlockQuery.Cell.OTHER;
            if (!loaded(w, x, z)) return BlockQuery.Cell.UNLOADED;
            return w.getBlock(x, y, z) == ModContent.screenBlock ? BlockQuery.Cell.SCREEN : BlockQuery.Cell.OTHER;
        };
    }

    /** The screen tile at a position, or null (unloaded chunk, other block). */
    public static TileEntityScreen tile(World w, int x, int y, int z) {
        if (w == null || y < 0 || y > 255 || !loaded(w, x, z)) return null;
        TileEntity te = w.getTileEntity(x, y, z);
        return te instanceof TileEntityScreen && !te.isInvalid() ? (TileEntityScreen) te : null;
    }

    /** Players whose client has the chunk at block (x, z) loaded (PlayerManager.isPlayerWatchingChunk). */
    public static List<EntityPlayerMP> trackers(WorldServer w, int x, int z) {
        List<EntityPlayerMP> out = new ArrayList<>();
        int cx = x >> 4, cz = z >> 4;
        for (Object o : w.playerEntities) {
            if (!(o instanceof EntityPlayerMP)) continue;
            EntityPlayerMP p = (EntityPlayerMP) o;
            if (p.playerNetServerHandler != null && w.getPlayerManager()
                .isPlayerWatchingChunk(p, cx, cz)) out.add(p);
        }
        return out;
    }

    /**
     * Eye position from the bounding box (always the feet on the server, whatever yOffset/eyeHeight conventions the
     * player class uses): 1.62 standing, 1.54 sneaking.
     */
    public static double[] eye(EntityPlayerMP p) {
        return new double[] { p.posX, p.boundingBox.minY + (p.isSneaking() ? 1.54 : 1.62), p.posZ };
    }

    public static double[] look(EntityPlayerMP p) {
        return HitMath.lookVector(p.rotationYaw, p.rotationPitch);
    }

    /**
     * Line of sight with vanilla's block ray trace (World.func_147447_a: liquids do not stop it, blocks without a
     * collision box are ignored, so a torch on the wall does not hide a screen but glass or a slab does). A ray across
     * a chunk that is not loaded counts as blocked: a sight check never loads or generates a chunk.
     */
    public static ViewCheck.Sight sight(World w) {
        return (ax, ay, az, bx, by, bz) -> {
            if (!loadedAlong(w, ax, az, bx, bz)) return false;
            MovingObjectPosition hit = w.func_147447_a(
                Vec3.createVectorHelper(ax, ay, az),
                Vec3.createVectorHelper(bx, by, bz),
                false,
                true,
                false);
            return hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK;
        };
    }

    /** Every chunk in the horizontal box spanned by the segment is loaded (at most a few for interaction ranges). */
    static boolean loadedAlong(World w, double ax, double az, double bx, double bz) {
        int x0 = (int) Math.floor(Math.min(ax, bx)) >> 4, x1 = (int) Math.floor(Math.max(ax, bx)) >> 4;
        int z0 = (int) Math.floor(Math.min(az, bz)) >> 4, z1 = (int) Math.floor(Math.max(az, bz)) >> 4;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                if (!w.getChunkProvider()
                    .chunkExists(cx, cz)) return false;
            }
        }
        return true;
    }

    /** Online player by UUID, or null. */
    public static EntityPlayerMP online(UUID id) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || id == null || server.getConfigurationManager() == null) return null;
        for (Object o : server.getConfigurationManager().playerEntityList) {
            if (o instanceof EntityPlayerMP && id.equals(((EntityPlayerMP) o).getUniqueID())) return (EntityPlayerMP) o;
        }
        return null;
    }

    /**
     * This player object is still the logged-in, living player: not logged out, dead, or replaced by a respawn (which
     * creates a new object). Queued work for a player checks it first, so a task from the tick of a logout cannot
     * re-create the records logout dropped.
     */
    public static boolean isOnline(EntityPlayerMP p) {
        if (p == null || p.isDead || p.playerNetServerHandler == null) return false;
        MinecraftServer server = MinecraftServer.getServer();
        return server != null && server.getConfigurationManager() != null
            && server.getConfigurationManager().playerEntityList.contains(p);
    }

    @SuppressWarnings("unchecked")
    public static List<EntityPlayerMP> players() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return new ArrayList<>();
        return new ArrayList<EntityPlayerMP>(server.getConfigurationManager().playerEntityList);
    }
}
