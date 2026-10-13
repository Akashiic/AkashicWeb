package com.akashiic.web.server;

import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.akashiic.web.common.ModContent;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.WebConfig;
import com.akashiic.web.common.WebSettings;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Server-side event subscriptions: the tick, login/logout (S2CHello, cleanup), screen block protection, and chunk
 * loads for self-healing. Registered on both the FML bus (ticks, players) and the Forge bus (world events).
 */
public final class ServerEvents {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) ServerRuntime.tick();
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP && !(event.player instanceof FakePlayer)) {
            ServerRuntime.playerSeen((EntityPlayerMP) event.player);
            ScreenSync.hello((EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player == null) return;
        UUID id = event.player.getUniqueID();
        ScreenActions.releaseAll(id, "logout");
        ServerRuntime.LIMITS.forget(id);
        ServerRuntime.THROTTLE.forget(id);
        ServerRuntime.DIAGNOSTICS.forget(id);
    }

    /** A lock is only ever taken in the player's own dimension, so a dimension change ends every lock it holds. */
    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.player != null && !(event.player instanceof FakePlayer)) {
            ScreenActions.releaseAll(event.player.getUniqueID(), "dimension");
        }
    }

    /**
     * Blocks of a formed screen can only be broken by its owner or an admin (config protectScreens). Never throws:
     * machines and other FakePlayers are simply refused on someone else's screen.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onBreak(BlockEvent.BreakEvent event) {
        if (event.world == null || event.world.isRemote || event.block != ModContent.screenBlock) return;
        WebSettings cfg = WebConfig.get();
        if (!cfg.protectScreens) return;
        EntityPlayer player = event.getPlayer();
        boolean admin = player != null && Admins.isAdmin(player, cfg);
        if (admin) return;
        List<ScreenState> screens = ServerRuntime.LOADED
            .containing(event.world.provider.dimensionId, event.x, event.y, event.z, -1);
        for (ScreenState s : screens) {
            boolean owner = player != null && !(player instanceof FakePlayer)
                && s.owner != null
                && s.owner.equals(player.getUniqueID());
            if (owner || s.owner == null) continue;
            event.setCanceled(true);
            if (player instanceof EntityPlayerMP && !(player instanceof FakePlayer)) {
                ScreenSync.tell((EntityPlayerMP) player, ReasonCode.NO_PERMISSION, s.ownerName);
            }
            return;
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.world == null || event.world.isRemote) return;
        ScreenLifecycle.onChunkLoaded(event.world, event.getChunk().xPosition, event.getChunk().zPosition);
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world == null || event.world.isRemote) return;
        ServerRuntime.LOADED.removeDimension(event.world.provider.dimensionId);
    }
}
