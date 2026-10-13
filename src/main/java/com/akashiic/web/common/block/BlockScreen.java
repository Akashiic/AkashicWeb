package com.akashiic.web.common.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;

import com.akashiic.web.AkashicWeb;
import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.HitMath;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.net.ClientHooks;
import com.akashiic.web.server.ScreenLifecycle;
import com.akashiic.web.server.ScreenProtection;

/**
 * The screen block. Placed like any block (so region plugins and block limiters apply, ADR-010); a flat rectangle of
 * them becomes a screen when a player right-clicks one face with an empty hand. Metadata bit 0 marks the origin
 * block, the only one with a tile entity (Web Displays' layout: one tile per screen, not one per block).
 * <p>
 * Right-clicking a formed screen only opens M5's GUI on the client (Crucible fires PlayerInteractEvent before
 * onBlockActivated, so region plugins can veto this click); page input then stays on the client (D-016). Breaking,
 * neighbour changes and chunk loads re-validate the affected screens one tick later; explosions, the wither and the
 * dragon do not break a protected screen ({@link ScreenProtection}).
 * Derived from Web Displays by montoyo (public domain), BlockScreen.
 */
public final class BlockScreen extends Block {

    public static final int META_ORIGIN = 1;

    public BlockScreen() {
        super(Material.iron);
        setHardness(1.5F);
        setResistance(10.0F);
        setStepSound(soundTypeMetal);
        setBlockName(AkashicWeb.MODID + ".screen");
        setBlockTextureName(AkashicWeb.MODID + ":screen");
        setCreativeTab(CreativeTabs.tabDecorations);
    }

    public static boolean isOrigin(int meta) {
        return (meta & META_ORIGIN) != 0;
    }

    @Override
    public boolean hasTileEntity(int metadata) {
        return isOrigin(metadata);
    }

    @Override
    public TileEntity createTileEntity(World world, int metadata) {
        return isOrigin(metadata) ? new TileEntityScreen() : null;
    }

    /** The origin flag is world state, never an item property. */
    @Override
    public int damageDropped(int meta) {
        return 0;
    }

    /** Pistons cannot move screen blocks (2 = immovable); a pushed block would tear a screen apart silently. */
    @Override
    public int getMobilityFlag() {
        return 2;
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        // Holding something: let the item do its thing (placing blocks against the screen, eating...).
        if (player.getHeldItem() != null) return false;
        BlockSide face = BlockSide.fromSide(side);
        if (face == null) return false;
        if (world.isRemote) {
            ScreenState s = ScreenLocator.find(world, this, x, y, z, face);
            if (s != null) {
                double[] uv = HitMath.uvOnFace(s, x + hitX, y + hitY, z + hitZ);
                clientHooks().onScreenActivated(
                    s.key,
                    uv == null ? Double.NaN : uv[0],
                    uv == null ? Double.NaN : uv[1],
                    player.isSneaking());
            }
            return true;
        }
        if (!(player instanceof EntityPlayerMP) || player instanceof FakePlayer) return false;
        ScreenLifecycle.onActivated((EntityPlayerMP) player, world, x, y, z, face);
        return true;
    }

    /**
     * The wither and the ender dragon break blocks through this check, not through BreakEvent: a protected screen
     * stays ({@link ScreenProtection}; explosions are handled in {@code ServerEvents.onExplosion}).
     */
    @Override
    public boolean canEntityDestroy(IBlockAccess world, int x, int y, int z, Entity entity) {
        if (world instanceof World && ScreenProtection.shielded((World) world, x, y, z)) return false;
        return super.canEntityDestroy(world, x, y, z, entity);
    }

    @Override
    public void onNeighborBlockChange(World world, int x, int y, int z, Block neighbor) {
        if (!world.isRemote) ScreenLifecycle.onScreenBlockChanged(world, x, y, z);
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int meta) {
        if (!world.isRemote) ScreenLifecycle.onScreenBlockChanged(world, x, y, z);
        super.breakBlock(world, x, y, z, block, meta);
    }

    private static ClientHooks clientHooks() {
        return AkashicWeb.proxy == null ? ClientHooks.NOOP : AkashicWeb.proxy.clientHooks();
    }
}
