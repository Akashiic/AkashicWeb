package com.akashiic.web.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiLanguage;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenOptionsSounds;
import net.minecraft.client.gui.GuiScreenResourcePacks;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import com.akashiic.web.client.gui.AddressBarGui;
import com.akashiic.web.client.gui.ClientNotices;
import com.akashiic.web.client.gui.KeyboardGui;
import com.akashiic.web.client.gui.LangKeys;
import com.akashiic.web.client.net.ClientTaskQueue;
import com.akashiic.web.client.render.FrameUploader;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenTile;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/**
 * FML-bus events: the client tick drives {@link ClientScreens} (20 Hz), the render tick START drives
 * {@link FrameUploader} once per frame (ADR-004), the disconnect event tears everything down, and the two key bindings
 * open the keyboard or the address bar for the screen under the crosshair. Both tick phases START drain
 * {@link ClientTaskQueue} so packet and host work lands within a frame.
 */
public final class ClientEvents {

    private static final long PRUNE_INTERVAL_MS = 1_000;

    private final ClientScreens screens;
    private final FrameUploader uploader;
    private final KeyBinding keyboardKey;
    private final KeyBinding addressKey;
    private long lastPruneMs;

    public ClientEvents(ClientScreens screens, FrameUploader uploader, KeyBinding keyboardKey, KeyBinding addressKey) {
        this.screens = screens;
        this.uploader = uploader;
        this.keyboardKey = keyboardKey;
        this.addressKey = addressKey;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase == TickEvent.Phase.START) {
            ClientTaskQueue.drain();
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        screens.tick(view(mc));
        long now = System.nanoTime() / 1_000_000L;
        if (mc.theWorld != null && now - lastPruneMs >= PRUNE_INTERVAL_MS) {
            lastPruneMs = now;
            World world = mc.theWorld;
            int dim = world.provider.dimensionId;
            screens.prune(key -> tilePresent(world, dim, key));
        }
        if (mc.theWorld != null && mc.currentScreen == null) {
            while (keyboardKey.isPressed()) openFor(mc, true);
            while (addressKey.isPressed()) openFor(mc, false);
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        ClientTaskQueue.drain();
        if (Minecraft.getMinecraft().theWorld != null) uploader.uploadFrame();
    }

    /** Netty thread: hop before touching screens. */
    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
        ClientTaskQueue.add(screens::onDisconnect);
    }

    private void openFor(Minecraft mc, boolean keyboard) {
        ClientScreens.Hit hit = ScreenInteraction.crosshairHit(mc, screens);
        if (hit == null) {
            ClientNotices.show(LangKeys.CHAT_NO_SCREEN);
            return;
        }
        mc.displayGuiScreen(
            keyboard ? new KeyboardGui(screens, hit.screen.key) : new AddressBarGui(screens, hit.screen.key));
    }

    static PlayerView view(Minecraft mc) {
        EntityClientPlayerMP p = mc.thePlayer;
        if (mc.theWorld == null || p == null) return null;
        Vec3 look = p.getLookVec();
        return new PlayerView(
            mc.theWorld.provider.dimensionId,
            p.posX,
            p.posY + p.getEyeHeight(),
            p.posZ,
            look == null ? 0 : look.xCoord,
            look == null ? 0 : look.yCoord,
            look == null ? 1 : look.zCoord,
            p.getUniqueID(),
            mc.isGamePaused() || isMenu(mc.currentScreen),
            mc.gameSettings.getSoundLevel(SoundCategory.MASTER),
            mc.gameSettings.getSoundLevel(SoundCategory.RECORDS));
    }

    /**
     * Menus that hard-mute screens (ADR-007 "in menus"): pause, options, connection and dimension-loading screens.
     * Chat, inventories and our own GUIs keep the sound, otherwise opening the keyboard would mute the live.
     */
    static boolean isMenu(GuiScreen g) {
        if (g == null) return false;
        if (g instanceof GuiIngameMenu || g instanceof GuiMainMenu
            || g instanceof GuiOptions
            || g instanceof GuiScreenOptionsSounds
            || g instanceof GuiVideoSettings
            || g instanceof GuiControls
            || g instanceof GuiLanguage
            || g instanceof GuiScreenResourcePacks
            || g instanceof GuiConnecting
            || g instanceof GuiDisconnected) return true;
        // Forge's mod list and config screens, reached from the pause menu; matched by name (no compile link).
        String n = g.getClass()
            .getName();
        return n.startsWith("cpw.mods.fml.client.GuiModList") || n.startsWith("cpw.mods.fml.client.config.GuiConfig")
            || n.equals("net.minecraft.client.gui.GuiDownloadTerrain");
    }

    private static boolean tilePresent(World world, int dim, ScreenKey key) {
        if (key.dim != dim) return false;
        TileEntity te = world.getTileEntity(key.x, key.y, key.z);
        return te instanceof ScreenTile && ((ScreenTile) te).screen(key.side) != null;
    }
}
