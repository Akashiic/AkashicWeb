package com.akashiic.web.client.screen;

import net.minecraftforge.event.world.WorldEvent;

import com.akashiic.web.client.render.ScreenRenderer;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Forge-bus world events. The client world is replaced on every dimension change and every proxy server switch (the
 * proxy's respawn dance), and fires Unload on the client thread: all screens are muted and destroyed with it.
 */
public final class WorldEvents {

    private final ClientScreens screens;
    private final ScreenRenderer renderer;

    public WorldEvents(ClientScreens screens, ScreenRenderer renderer) {
        this.screens = screens;
        this.renderer = renderer;
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload e) {
        if (e.world == null || !e.world.isRemote) return; // the integrated server's worlds are not ours
        screens.teardownAll();
        if (renderer != null) renderer.clearCache();
    }
}
