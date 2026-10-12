package com.akashiic.web.common;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;

import com.akashiic.web.common.net.ServerBound;

/**
 * Late-bound handles filled by M4 during preInit/init, read by M5. Keeps the client from importing server-side
 * concrete classes and keeps modules compiling independently. Contract type.
 */
public final class ModContent {

    public static Block screenBlock;
    /** Tile entity class of the screen origin; implements {@link ScreenTile}. */
    public static Class<? extends TileEntity> screenTileClass;
    /** Client→server sender; a no-op until the network channel is registered. */
    public static volatile ServerBound toServer = ServerBound.NOOP;

    private ModContent() {}
}
