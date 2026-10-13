package com.akashiic.web.common.block;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Item form of the screen block: always places a plain (non-origin) block, whatever the stack's damage. */
public final class ItemBlockScreen extends ItemBlock {

    public ItemBlockScreen(Block block) {
        super(block);
    }

    @Override
    public int getMetadata(int damage) {
        return 0;
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        lines.add(StatCollector.translateToLocal("tile.akashicweb.screen.tooltip"));
    }
}
