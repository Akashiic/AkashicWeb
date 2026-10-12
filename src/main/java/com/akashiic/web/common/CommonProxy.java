package com.akashiic.web.common;

import com.akashiic.web.AkashicWeb;
import com.akashiic.web.Tags;
import com.akashiic.web.protocol.Protocol;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

/** Code shared by both sides. Must never touch client classes, LWJGL or the browser host. */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        AkashicWeb.LOG.info("AkashicWeb {} (protocol {}) starting", Tags.VERSION, Protocol.MAJOR);
    }

    public void init(FMLInitializationEvent event) {}

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {}
}
