package com.akashiic.web.server;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.ModContent;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.ScreenFormation;
import com.akashiic.web.common.ScreenGeometry;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.WebConfig;
import com.akashiic.web.common.WebSettings;
import com.akashiic.web.common.block.BlockScreen;
import com.akashiic.web.common.block.TileEntityScreen;

/**
 * Screens coming and going: formation by right-click, dissolution when blocks are removed, and self-healing of the
 * index against the real tile entities (ADR-010). Every world change is deferred one tick through the action queue
 * and re-validated then, because Crucible can roll back a placement or block change after the event that told us
 * about it, and WorldEdit/Schematica can change blocks without any player.
 * <p>
 * Rule for existing screens: a screen stays as long as its own rectangle is made of screen blocks. Placing blocks
 * next to it never dissolves it (Web Displays destroyed neighbouring screens on placement).
 */
public final class ScreenLifecycle {

    /** Keys already waiting for a heal this tick (coalesces a burst of neighbour updates). */
    private static final Set<ScreenKey> PENDING_HEAL = new HashSet<>();

    private ScreenLifecycle() {}

    // ---- tile entity hooks (server side, from TileEntityScreen) ----

    public static void onTileLoaded(TileEntityScreen te) {
        ServerRuntime.LOADED.register(te);
        for (ScreenState s : te.screens()) {
            // A screen saved with a newer epoch than the index remembers (the index file was lost): keep it in step.
            if (ServerRuntime.index()
                .noteEpoch(s.epoch)) ServerRuntime.indexChanged();
            scheduleHeal(s.key);
        }
    }

    public static void onTileRemoved(TileEntityScreen te) {
        ServerRuntime.LOADED.unregister(te);
        for (ScreenState s : te.screens()) scheduleHeal(s.key);
    }

    public static void onTileUnloaded(TileEntityScreen te) {
        ServerRuntime.LOADED.unregister(te);
    }

    /** A screen block broke or a neighbour changed: re-check every loaded screen containing it, next tick. */
    public static void onScreenBlockChanged(World w, int x, int y, int z) {
        int dim = w.provider.dimensionId;
        for (ScreenState s : ServerRuntime.LOADED.containing(dim, x, y, z, -1)) scheduleHeal(s.key);
    }

    /** A chunk loaded: index entries whose origin is in it are compared with the tile entities next tick. */
    public static void onChunkLoaded(World w, int chunkX, int chunkZ) {
        for (ScreenIndex.Entry e : ServerRuntime.index()
            .inChunk(w.provider.dimensionId, chunkX, chunkZ)) scheduleHeal(e.key);
    }

    /** Right-click with an empty hand on a face that is not a formed screen yet: try to form one next tick. */
    public static void onActivated(EntityPlayerMP p, World w, int x, int y, int z, BlockSide face) {
        int dim = w.provider.dimensionId;
        if (!ServerRuntime.LOADED.containing(dim, x, y, z, face.side())
            .isEmpty()) return; // formed: the client opened the GUI, nothing to do here
        if (!ServerRuntime.LIMITS.tryAcquire(p.getUniqueID(), ActionLimits.Kind.FORM, WebConfig.get())) return;
        UUID id = p.getUniqueID();
        ServerRuntime.QUEUE.offer(id, () -> form(id, new ScreenKey(dim, x, y, z, face.side())));
    }

    // ---- deferred work ----

    /**
     * Queues one heal per key. If the queue refuses it, the key leaves the pending set again, so the next block
     * change, chunk load or tile event schedules it anew (review finding: a full queue left the key pending forever,
     * and that screen was never healed or cleaned up again until a restart).
     */
    static void scheduleHeal(ScreenKey key) {
        if (!PENDING_HEAL.add(key)) return;
        boolean queued = ServerRuntime.QUEUE.offer(() -> {
            PENDING_HEAL.remove(key);
            heal(key);
        });
        if (!queued) PENDING_HEAL.remove(key);
    }

    /** Keys waiting for a heal (tests, status). */
    static boolean healPending(ScreenKey key) {
        return PENDING_HEAL.contains(key);
    }

    /** Brings index, registry and world back in line for one screen. */
    static void heal(ScreenKey key) {
        WorldServer w = ScreenWorld.world(key.dim);
        if (w == null || !ScreenWorld.loaded(w, key.x, key.z)) return; // try again when it loads
        ScreenIndex index = ServerRuntime.index();
        TileEntityScreen te = ScreenWorld.tile(w, key.x, key.y, key.z);
        ScreenState s = te == null ? null : te.screen(key.side);
        if (s == null) {
            if (index.remove(key)) {
                ServerRuntime.indexChanged();
                AuditLog.log(null, null, "screen gone", key.toString());
            }
            ServerRuntime.endSession(key);
            return;
        }
        ServerRuntime.LOADED.register(te);
        BlockSide side = BlockSide.fromSide(key.side);
        ScreenFormation.Integrity integrity = ScreenFormation
            .intact(ScreenWorld.query(w), key.x, key.y, key.z, side, s.widthBlocks, s.heightBlocks);
        if (integrity == ScreenFormation.Integrity.BROKEN) {
            dissolve(w, te, key.side, ReasonCode.SCREEN_DISSOLVED, null);
            return;
        }
        // INTACT, or UNKNOWN (part of it in an unloaded chunk): keep it and refresh the index entry.
        if (index.put(ScreenIndex.Entry.of(s))) ServerRuntime.indexChanged();
    }

    /** Forms a screen on the clicked face, re-validating everything against the world as it is now. */
    static void form(UUID playerId, ScreenKey clicked) {
        EntityPlayerMP p = ScreenWorld.online(playerId);
        if (p == null || p.dimension != clicked.dim) return;
        WorldServer w = ScreenWorld.world(clicked.dim);
        if (w == null || ScreenWorld.query(w)
            .at(clicked.x, clicked.y, clicked.z) != com.akashiic.web.common.BlockQuery.Cell.SCREEN) return;
        WebSettings cfg = WebConfig.get();
        boolean admin = Admins.isAdmin(p, cfg);
        if (cfg.killSwitch && !admin) {
            ScreenSync.tell(p, ReasonCode.DISABLED_BY_SERVER, "");
            return;
        }
        BlockSide side = BlockSide.fromSide(clicked.side);
        int maxW = admin ? cfg.adminMaxWidth : cfg.maxWidth, maxH = admin ? cfg.adminMaxHeight : cfg.maxHeight;
        ScreenFormation.Plan plan = ScreenFormation
            .plan(ScreenWorld.query(w), clicked.x, clicked.y, clicked.z, side, maxW, maxH);
        if (!plan.ok()) {
            refuseFormation(p, plan, maxW, maxH);
            return;
        }
        // Existing screens on this side inside the new rectangle (the edge check guarantees they are fully inside):
        // only their owner or an admin may merge them into the bigger one.
        List<ScreenState> inside = new ArrayList<>();
        for (ScreenState s : ServerRuntime.LOADED.faces()) {
            if (s.key.dim == clicked.dim && s.key.side == clicked.side
                && ScreenFormation.overlaps(
                    plan.originX,
                    plan.originY,
                    plan.originZ,
                    plan.width,
                    plan.height,
                    s.key.x,
                    s.key.y,
                    s.key.z,
                    s.widthBlocks,
                    s.heightBlocks,
                    side)) {
                if (!admin && !playerId.equals(s.owner)) {
                    ScreenSync.tell(p, ReasonCode.SCREEN_OVERLAP, s.ownerName);
                    return;
                }
                inside.add(s);
            }
        }
        int ownedAfter = ServerRuntime.index()
            .ownedBy(playerId) + 1;
        for (ScreenState s : inside) if (playerId.equals(s.owner)) ownedAfter--;
        if (!admin && cfg.maxPerPlayer > 0 && ownedAfter > cfg.maxPerPlayer) {
            ScreenSync.tell(p, ReasonCode.SCREEN_LIMIT, String.valueOf(cfg.maxPerPlayer));
            return;
        }
        for (ScreenState s : inside) {
            TileEntityScreen old = ServerRuntime.LOADED.tile(s.key);
            if (old != null) dissolve(w, old, s.key.side, null, p.getCommandSenderName());
        }
        TileEntityScreen te = originTile(w, plan.originX, plan.originY, plan.originZ);
        if (te == null) {
            ScreenSync.tell(p, ReasonCode.SCREEN_INVALID_SHAPE, plan.originX + " " + plan.originY + " " + plan.originZ);
            return;
        }
        ScreenState s = newScreen(
            te.keyFor(clicked.side),
            plan.width,
            plan.height,
            p.getUniqueID(),
            p.getCommandSenderName(),
            p.rotationYaw,
            cfg,
            inside,
            ServerRuntime.index());
        te.putFace(s);
        w.markBlockForUpdate(plan.originX, plan.originY, plan.originZ);
        ServerRuntime.LOADED.register(te);
        ServerRuntime.session(s);
        if (ServerRuntime.index()
            .put(ScreenIndex.Entry.of(s))) ServerRuntime.indexChanged();
        AuditLog.log(
            p.getCommandSenderName(),
            playerId,
            "screen formed",
            s.key + " " + s.widthBlocks + "x" + s.heightBlocks + (inside.isEmpty() ? "" : " merged=" + inside.size()));
        ScreenSync.chat(p, "akashicweb.msg.formed", s.widthBlocks, s.heightBlocks);
    }

    private static void refuseFormation(EntityPlayerMP p, ScreenFormation.Plan plan, int maxW, int maxH) {
        switch (plan.reason) {
            case SCREEN_TOO_BIG:
                ScreenSync.tell(p, plan.reason, maxW + "x" + maxH);
                break;
            case SCREEN_INVALID_SHAPE:
            case CHUNK_NOT_LOADED:
                ScreenSync.tell(p, plan.reason, plan.badX + " " + plan.badY + " " + plan.badZ);
                break;
            default:
                ScreenSync.tell(p, plan.reason, ScreenFormation.MIN_SIZE + "x" + ScreenFormation.MIN_SIZE);
                break;
        }
    }

    /** The origin block's tile, setting the origin flag (and so creating the tile) if needed. */
    private static TileEntityScreen originTile(WorldServer w, int x, int y, int z) {
        int meta = w.getBlockMetadata(x, y, z);
        if (!BlockScreen.isOrigin(meta)) w.setBlockMetadataWithNotify(x, y, z, meta | BlockScreen.META_ORIGIN, 2);
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof TileEntityScreen) return (TileEntityScreen) te;
        if (w.getBlock(x, y, z) != ModContent.screenBlock) return null;
        TileEntityScreen fresh = new TileEntityScreen();
        w.setTileEntity(x, y, z, fresh);
        return fresh;
    }

    /**
     * A new face record. A merged screen keeps the URL, friends, rights and mode of the largest screen it absorbed.
     * Its epoch comes from {@link ScreenIndex#nextEpoch}: above every epoch issued so far, so a client that still
     * holds an older screen at the same key takes the new one's URLs (epochs only grow per key).
     */
    static ScreenState newScreen(ScreenKey key, int w, int h, UUID ownerId, String ownerName, float yaw,
        WebSettings cfg, List<ScreenState> absorbed, ScreenIndex index) {
        ScreenState base = largest(absorbed);
        ScreenState s = new ScreenState();
        s.key = key;
        s.widthBlocks = w;
        s.heightBlocks = h;
        s.rotation = ScreenGeometry.initialRotation(BlockSide.fromSide(key.side), yaw);
        int[] res = ScreenGeometry.defaultResolution(w, h, s.rotation, cfg.defaultResolutionW, cfg.defaultResolutionH);
        s.resolutionW = res[0];
        s.resolutionH = res[1];
        s.owner = ownerId;
        s.ownerName = ownerName;
        s.epoch = index.nextEpoch(absorbed);
        if (base != null) {
            s.url = base.url;
            s.friends.addAll(base.friends);
            s.friendNames.putAll(base.friendNames);
            s.friendRights = base.friendRights;
            s.otherRights = base.otherRights;
            s.locked = base.locked;
            s.mode = base.mode;
            if (base.owner != null) { // an admin merging someone's screen keeps that owner
                s.owner = base.owner;
                s.ownerName = base.ownerName;
            }
        }
        return s;
    }

    private static ScreenState largest(List<ScreenState> screens) {
        ScreenState best = null;
        for (ScreenState s : screens) {
            if (best == null || s.widthBlocks * s.heightBlocks > best.widthBlocks * best.heightBlocks) best = s;
        }
        return best;
    }

    /**
     * Removes one face. {@code reason} null means a merge (no owner notice). The origin flag and tile go when the last
     * face goes; the block change carries the removal to clients.
     */
    static void dissolve(WorldServer w, TileEntityScreen te, int side, ReasonCode reason, String actor) {
        ScreenState s = te.removeFace(side);
        if (s == null) return;
        ScreenActions.forgetScreen(s.key);
        if (ServerRuntime.index()
            .remove(s.key)) ServerRuntime.indexChanged();
        AuditLog.log(
            actor,
            null,
            reason == null ? "screen merged" : "screen dissolved",
            s.key + " owner=" + s.ownerName + (reason == null ? "" : " " + reason.name()));
        if (reason != null && s.owner != null) {
            EntityPlayerMP owner = ScreenWorld.online(s.owner);
            if (owner != null) ScreenSync.tell(owner, reason, s.key.x + " " + s.key.y + " " + s.key.z);
        }
        if (te.hasFaces()) {
            w.markBlockForUpdate(te.xCoord, te.yCoord, te.zCoord);
            return;
        }
        int meta = w.getBlockMetadata(te.xCoord, te.yCoord, te.zCoord);
        if (w.getBlock(te.xCoord, te.yCoord, te.zCoord) == ModContent.screenBlock && BlockScreen.isOrigin(meta)) {
            w.setBlockMetadataWithNotify(te.xCoord, te.yCoord, te.zCoord, meta & ~BlockScreen.META_ORIGIN, 2);
        }
        w.removeTileEntity(te.xCoord, te.yCoord, te.zCoord);
    }

    /** Server stopped. */
    public static void clear() {
        PENDING_HEAL.clear();
    }
}
