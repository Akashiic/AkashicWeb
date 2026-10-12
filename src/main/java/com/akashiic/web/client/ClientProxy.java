package com.akashiic.web.client;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import com.akashiic.web.AkashicWeb;
import com.akashiic.web.client.gui.ClientNotices;
import com.akashiic.web.client.gui.DoctorCommand;
import com.akashiic.web.client.gui.LangKeys;
import com.akashiic.web.client.net.ClientNetHooks;
import com.akashiic.web.client.net.ClientTaskQueue;
import com.akashiic.web.client.render.AngelicaCompat;
import com.akashiic.web.client.render.FrameUploader;
import com.akashiic.web.client.render.ScreenRenderer;
import com.akashiic.web.client.screen.BrowserBudget;
import com.akashiic.web.client.screen.ClientEvents;
import com.akashiic.web.client.screen.ClientScreens;
import com.akashiic.web.client.screen.ClientServices;
import com.akashiic.web.client.screen.HostAccess;
import com.akashiic.web.client.screen.ScreenInteraction;
import com.akashiic.web.client.screen.SystemMemory;
import com.akashiic.web.client.screen.WorldEvents;
import com.akashiic.web.common.CommonProxy;
import com.akashiic.web.common.ModContent;
import com.akashiic.web.common.net.ClientHooks;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * Client side. The only side that may start the browser host process, and it does so lazily: nothing is spawned until
 * a screen near the player is granted a browser.
 */
public class ClientProxy extends CommonProxy {

    private ClientScreens screens;
    private ClientNetHooks hooks;
    private FrameUploader uploader;
    private ScreenRenderer renderer;

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event); // M4 assigns ModContent.screenBlock / screenTileClass here
        wireServices();
        LongSupplier clock = ClientProxy::monotonicMs;
        long ram = SystemMemory.totalPhysicalBytes();
        int budget = BrowserBudget.maxBrowsers(ram);
        screens = new ClientScreens(
            HostAccess.SERVICES,
            () -> ModContent.toServer,
            clock,
            ClientTaskQueue::add,
            ClientNotices::show,
            budget,
            new SecureRandom().nextLong());
        hooks = new ClientNetHooks(screens, clock, ClientTaskQueue::add, ClientNotices::show);
        uploader = new FrameUploader(screens, clock);
        AkashicWeb.LOG.info(
            "AkashicWeb: browser budget {} ({} MB physical memory)",
            budget,
            ram > 0 ? ram / (1024 * 1024) : "unknown");
        // Before any chunk meshes: Angelica caches a tile class's bounds kind on first sight (ADR-005 amendment).
        AngelicaCompat.registerDynamicBounds(ModContent.screenTileClass);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        if (AngelicaCompat.registration() == AngelicaCompat.Registration.NO_TILE_CLASS) {
            // M4 assigned the class after our preInit; init still runs before any world, so the registration holds.
            AngelicaCompat.registerDynamicBounds(ModContent.screenTileClass);
        }
        if (ModContent.screenTileClass == null) {
            AkashicWeb.LOG.error("AkashicWeb: ModContent.screenTileClass is null; screens will not render");
        } else {
            renderer = new ScreenRenderer(screens);
            ClientRegistry.bindTileEntitySpecialRenderer(ModContent.screenTileClass, renderer);
        }
        KeyBinding keyboardKey = new KeyBinding(LangKeys.KEY_KEYBOARD, Keyboard.KEY_NONE, LangKeys.KEY_CATEGORY);
        KeyBinding addressKey = new KeyBinding(LangKeys.KEY_ADDRESS, Keyboard.KEY_NONE, LangKeys.KEY_CATEGORY);
        ClientRegistry.registerKeyBinding(keyboardKey);
        ClientRegistry.registerKeyBinding(addressKey);
        FMLCommonHandler.instance()
            .bus()
            .register(new ClientEvents(screens, uploader, keyboardKey, addressKey));
        MinecraftForge.EVENT_BUS.register(new WorldEvents(screens, renderer));
        MinecraftForge.EVENT_BUS.register(new ScreenInteraction(screens));
        ClientCommandHandler.instance.registerCommand(new DoctorCommand(this::diagnostics));
    }

    @Override
    public ClientHooks clientHooks() {
        return hooks != null ? hooks : ClientHooks.NOOP;
    }

    /**
     * Integration point for the other client modules (docs/CONTRACTS.md): wire M2's host and M3's engine locator and
     * consent GUI here, e.g.
     *
     * <pre>
     * EngineLocator locator = new ...; // M3
     * ClientServices.setEngineLocator(locator);
     * ClientServices.setHostFactory(() -> new BrowserHostImpl(locator, ...)); // M2, started lazily
     * ClientServices.setConsentOpener(() -> ...); // M3's consent GUI
     * </pre>
     *
     * Until then every screen shows HOST_UNAVAILABLE and the client plays normally.
     */
    protected void wireServices() {
        if (ClientServices.engineLocator() == null)
            AkashicWeb.LOG.warn("AkashicWeb: no engine locator / browser host wired; screens show placeholders");
    }

    /** Lines for /awdoctor (and for M4's /web doctor if it wants the client view). */
    public List<String> diagnostics() {
        long now = monotonicMs();
        List<String> lines = new ArrayList<>();
        lines.add("AkashicWeb client doctor");
        if (screens == null) return lines;
        lines.addAll(screens.diagnostics(now));
        lines.add(
            uploader.stats()
                .snapshot(now));
        lines.add("angelica bounds: " + AngelicaCompat.registration() + " | task queue: " + ClientTaskQueue.size());
        return lines;
    }

    static long monotonicMs() {
        return System.nanoTime() / 1_000_000L;
    }
}
