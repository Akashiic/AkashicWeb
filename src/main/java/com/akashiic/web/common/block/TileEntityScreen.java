package com.akashiic.web.common.block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;

import com.akashiic.web.AkashicWeb;
import com.akashiic.web.common.ScreenGeometry;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenNbt;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.ScreenTile;
import com.akashiic.web.common.net.ClientHooks;
import com.akashiic.web.server.ScreenLifecycle;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Tile entity on the origin block of a screen (bottom-left as seen from the face); one record per face. Non-ticking
 * (ADR-010). The server owns the records and pushes them through the description packet; the client copy exists for
 * the renderer (via {@link ScreenTile}) and forwards every update to M5 through {@link ClientHooks}.
 * <p>
 * Keys are (dim, x, y, z, side) values derived from the tile's own position: NBT never stores coordinates, so a
 * moved or copied tile entity cannot claim another screen's identity.
 */
public final class TileEntityScreen extends TileEntity implements ScreenTile {

    /**
     * Render distance for big screens beyond their farthest corner (vanilla's TESR default is 64 blocks from the tile).
     */
    static final double MAX_RENDER_DISTANCE = 128;

    private final ScreenState[] faces = new ScreenState[6];
    private AxisAlignedBB renderBox;
    /** Squared render distance from the origin block; 0 until computed. Reset with {@link #renderBox}. */
    private double renderDistanceSq;
    /** Client: removal already reported to M5 (invalidate and onChunkUnload may both run). */
    private boolean removalReported;

    @Override
    public boolean canUpdate() {
        return false;
    }

    // ---- ScreenTile (contract) ----

    @Override
    public ScreenState screen(int side) {
        return side < 0 || side >= faces.length ? null : faces[side];
    }

    @Override
    public Collection<ScreenState> screens() {
        List<ScreenState> out = new ArrayList<>(2);
        for (ScreenState s : faces) if (s != null) out.add(s);
        return Collections.unmodifiableList(out);
    }

    // ---- server-side mutation (ScreenLifecycle / ScreenActions) ----

    /** Stores a face (key rebuilt from this tile's position) and marks the chunk for saving. */
    public void putFace(ScreenState s) {
        s.key = keyFor(s.key.side);
        faces[s.key.side] = s;
        boundsChanged();
        markDirty();
    }

    public ScreenState removeFace(int side) {
        ScreenState old = screen(side);
        if (old != null) {
            faces[side] = null;
            boundsChanged();
            markDirty();
        }
        return old;
    }

    public boolean hasFaces() {
        for (ScreenState s : faces) if (s != null) return true;
        return false;
    }

    /** Identity of the face on {@code side} of this tile, in its current world. */
    public ScreenKey keyFor(int side) {
        int dim = worldObj == null || worldObj.provider == null ? 0 : worldObj.provider.dimensionId;
        return new ScreenKey(dim, xCoord, yCoord, zCoord, side);
    }

    private void rebindKeys() {
        for (int side = 0; side < faces.length; side++) {
            ScreenState s = faces[side];
            if (s != null && !s.key.equals(keyFor(side))) s.key = keyFor(side);
        }
    }

    // ---- persistence ----

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        ScreenNbt.write(tag, screens(), false);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        // The world may not be set yet (chunk loading): keys get the right dimension in validate().
        load(ScreenNbt.read(tag, keyFor(0).dim, xCoord, yCoord, zCoord));
    }

    private void load(List<ScreenState> incoming) {
        for (int i = 0; i < faces.length; i++) faces[i] = null;
        for (ScreenState s : incoming) faces[s.key.side] = s;
        boundsChanged();
    }

    // ---- sync ----

    @Override
    public Packet getDescriptionPacket() {
        if (!hasFaces()) return null;
        NBTTagCompound tag = new NBTTagCompound();
        ScreenNbt.write(tag, screens(), true);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 1, tag);
    }

    /** Client: replace the faces and tell M5 what appeared, changed or went away. */
    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        if (worldObj == null || !worldObj.isRemote) return;
        List<ScreenState> incoming = ScreenNbt.read(pkt.func_148857_g(), keyFor(0).dim, xCoord, yCoord, zCoord);
        boolean[] present = new boolean[faces.length];
        for (ScreenState s : incoming) present[s.key.side] = true;
        ClientHooks hooks = hooks();
        for (int side = 0; side < faces.length; side++) {
            if (faces[side] != null && !present[side]) hooks.onScreenRemoved(faces[side].key);
        }
        load(incoming);
        removalReported = false;
        for (ScreenState s : incoming) hooks.onScreenState(s.copy());
    }

    // ---- lifecycle ----

    @Override
    public void validate() {
        super.validate();
        rebindKeys();
        if (worldObj != null && !worldObj.isRemote) ScreenLifecycle.onTileLoaded(this);
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (worldObj == null) return;
        if (worldObj.isRemote) reportRemoval();
        else ScreenLifecycle.onTileRemoved(this);
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (worldObj == null) return;
        if (worldObj.isRemote) reportRemoval();
        else ScreenLifecycle.onTileUnloaded(this);
    }

    private void reportRemoval() {
        if (removalReported) return;
        removalReported = true;
        ClientHooks hooks = hooks();
        for (ScreenState s : faces) if (s != null) hooks.onScreenRemoved(s.key);
    }

    private static ClientHooks hooks() {
        return AkashicWeb.proxy == null ? ClientHooks.NOOP : AkashicWeb.proxy.clientHooks();
    }

    // ---- rendering bounds (ADR-005: the true multiblock box, Angelica tests it live) ----

    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getRenderBoundingBox() {
        if (renderBox == null) {
            double[] b = ScreenGeometry.renderBox(xCoord, yCoord, zCoord, screens());
            renderBox = AxisAlignedBB.getBoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]);
        }
        return renderBox;
    }

    /**
     * Vanilla measures this from the origin block, so a big screen adds its own size ({@link
     * ScreenGeometry#renderDistance}); cached because the renderer asks every frame.
     */
    @Override
    @SideOnly(Side.CLIENT)
    public double getMaxRenderDistanceSquared() {
        if (renderDistanceSq == 0) {
            double d = ScreenGeometry.renderDistance(MAX_RENDER_DISTANCE, screens());
            renderDistanceSq = d * d;
        }
        return renderDistanceSq;
    }

    /** The faces changed: render box and distance are computed again on next use. */
    private void boundsChanged() {
        renderBox = null;
        renderDistanceSq = 0;
    }
}
