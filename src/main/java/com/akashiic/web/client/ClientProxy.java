package com.akashiic.web.client;

import com.akashiic.web.common.CommonProxy;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/** Client side. The only side that may start the browser host process. */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }
}
