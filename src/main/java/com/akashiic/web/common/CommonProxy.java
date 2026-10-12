package com.akashiic.web.common;

import java.io.File;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;

import com.akashiic.web.AkashicWeb;
import com.akashiic.web.Tags;
import com.akashiic.web.common.block.BlockScreen;
import com.akashiic.web.common.block.ItemBlockScreen;
import com.akashiic.web.common.block.TileEntityScreen;
import com.akashiic.web.common.net.ClientHooks;
import com.akashiic.web.common.net.WebNetwork;
import com.akashiic.web.protocol.Protocol;
import com.akashiic.web.server.ScreenLifecycle;
import com.akashiic.web.server.ServerEvents;
import com.akashiic.web.server.ServerRuntime;
import com.akashiic.web.server.ServerThread;
import com.akashiic.web.server.command.CommandRegistrar;
import com.akashiic.web.server.command.CommandSupport;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.registry.GameRegistry;

/**
 * Code shared by both sides. Must never touch client classes, LWJGL or the browser host. The client proxy (M5)
 * extends it and calls super for every phase; the dedicated server uses it as is.
 */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        AkashicWeb.LOG.info("AkashicWeb {} (protocol {}) starting", Tags.VERSION, Protocol.MAJOR);
        WebConfig.init(new File(event.getModConfigurationDirectory(), AkashicWeb.MODID + ".cfg"));
        BlockScreen screen = new BlockScreen();
        GameRegistry.registerBlock(screen, ItemBlockScreen.class, "screen");
        GameRegistry.registerTileEntity(TileEntityScreen.class, AkashicWeb.MODID + ":screen");
        ModContent.screenBlock = screen;
        ModContent.screenTileClass = TileEntityScreen.class;
    }

    public void init(FMLInitializationEvent event) {
        WebNetwork.init();
        ServerEvents events = new ServerEvents();
        FMLCommonHandler.instance()
            .bus()
            .register(events);
        MinecraftForge.EVENT_BUS.register(events);
        // Four screens: iron frame, glass panes for the face, redstone and glowstone for the electronics.
        GameRegistry.addRecipe(
            new ItemStack(ModContent.screenBlock, 4),
            "IGI",
            "RLR",
            "IGI",
            'I',
            Items.iron_ingot,
            'G',
            Blocks.glass_pane,
            'R',
            Items.redstone,
            'L',
            Items.glowstone_dust);
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {
        ServerThread.mark();
        CommandRegistrar.register(event);
    }

    public void serverStopped(FMLServerStoppedEvent event) {
        ServerRuntime.stop();
        ScreenLifecycle.clear();
        CommandSupport.clear();
    }

    /** Receiver for server→client packets. The dedicated server never receives them. */
    public ClientHooks clientHooks() {
        return ClientHooks.NOOP;
    }
}
