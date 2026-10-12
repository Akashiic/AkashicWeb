package com.akashiic.web;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.akashiic.web.protocol.Protocol;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

/**
 * AkashicWeb: a real web browser on in-world screens. The browser engine never runs inside this JVM: on the client
 * a separate AkashicWebHost process renders pages and this mod only uploads frames, forwards input and keeps every
 * player in sync with the server (see docs/adr/README.md).
 */
@Mod(
    modid = AkashicWeb.MODID,
    name = AkashicWeb.NAME,
    version = Tags.VERSION,
    acceptableRemoteVersions = Protocol.ACCEPTABLE_REMOTE_VERSIONS)
public final class AkashicWeb {

    public static final String MODID = "akashicweb";
    public static final String NAME = "AkashicWeb";
    public static final Logger LOG = LogManager.getLogger(NAME);

    @Mod.Instance(MODID)
    public static AkashicWeb instance;

    @SidedProxy(clientSide = "com.akashiic.web.client.ClientProxy", serverSide = "com.akashiic.web.common.CommonProxy")
    public static com.akashiic.web.common.CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }
}
