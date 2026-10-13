package com.akashiic.web.client;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;

import com.akashiic.web.AkashicWeb;
import com.akashiic.web.client.gui.ConsentController;
import com.akashiic.web.client.gui.ConsentGui;
import com.akashiic.web.client.host.BrowserHost;
import com.akashiic.web.client.host.BrowserHostImpl;
import com.akashiic.web.client.host.HostExecutable;
import com.akashiic.web.client.host.HostLaunchConfig;
import com.akashiic.web.client.host.HostStatus;
import com.akashiic.web.client.install.EngineLocator;
import com.akashiic.web.client.install.EngineLocatorImpl;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.client.net.ClientTaskQueue;
import com.akashiic.web.client.screen.ClientServices;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Wires M3's engine installer and M2's browser host into the game (the one place that names their implementations,
 * kept small so the lead can reconcile it with parallel M2/M3 changes). Sequence, run once in pre-init:
 * <ol>
 * <li>{@code EngineLocatorImpl.forClient(<game dir>)}, published to {@link ClientServices}, then {@code start()}: the
 * probe runs on the installer's own thread; on Android/Pojav or macOS it ends UNSUPPORTED and nothing is
 * downloaded.</li>
 * <li>The host factory: {@code new BrowserHostImpl(HostLaunchConfig(HostExecutable.fromEngine(locator),
 * <game dir>/logs/akashicweb, locator.userDataDir()))} then {@code start()}. ClientScreens calls it on the client
 * thread only once the engine is READY (the profile folder is final only after the probe) and a screen is granted a
 * browser, and pushes the site policy before the first CREATE.</li>
 * <li>The install prompt ({@link ConsentGui} over {@link ConsentController}) as the consent opener.</li>
 * <li>A JVM shutdown hook that calls {@code BrowserHost.shutdown()} (up to about 3.5 s, never on the client
 * thread).</li>
 * </ol>
 * Every client tick {@link ServiceWatcher} decides whether to ask the installer to verify the engine after a launch
 * failure or to re-enable the host after an install or repair. A failure to wire is logged and leaves the services
 * unwired: screens then show HOST_UNAVAILABLE and the game plays normally.
 */
public final class ClientWiring {

    private final EngineLocator locator;
    private final File gameDir;
    private final ServiceWatcher watcher = new ServiceWatcher();
    private final ConsentController consent;

    ClientWiring(EngineLocator locator, File gameDir) {
        this.locator = locator;
        this.gameDir = gameDir;
        this.consent = new ConsentController(
            () -> this.locator,
            ClientWiring::hostStatus,
            ClientServices::setConsentPostponed);
    }

    /** Builds and publishes the services for the game in {@code gameDir}; null (logged) when that fails. */
    public static ClientWiring install(File gameDir) {
        try {
            EngineLocatorImpl l = EngineLocatorImpl.forClient(gameDir);
            ClientWiring w = new ClientWiring(l, gameDir);
            w.publish();
            l.start();
            addShutdownHook();
            AkashicWeb.LOG.info("AkashicWeb: engine installer and browser host wired (logs in {})", w.logDir());
            return w;
        } catch (RuntimeException | LinkageError e) {
            // Documented boundary: a client without the browser still joins and plays (AGENTS.md).
            AkashicWeb.LOG.error("AkashicWeb: could not wire the browser; screens show placeholders", e);
            return null;
        }
    }

    /** Publishes the locator, the host factory and the consent opener. */
    void publish() {
        ClientServices.setEngineLocator(locator);
        ClientServices.setHostFactory(this::createHost);
        ClientServices.setConsentOpener(this::openConsent);
    }

    /** Client thread, engine READY (ClientScreens checks both before asking). */
    BrowserHost createHost() {
        BrowserHostImpl host = new BrowserHostImpl(launchConfig());
        host.start();
        return host;
    }

    HostLaunchConfig launchConfig() {
        return HostLaunchConfig.builder(HostExecutable.fromEngine(locator), logDir(), locator.userDataDir())
            .build();
    }

    /** {@code <game dir>/logs/akashicweb}: next to the game's own logs, where players look for them. */
    File logDir() {
        return new File(new File(gameDir, "logs"), "akashicweb");
    }

    public EngineLocator locator() {
        return locator;
    }

    public ConsentController consent() {
        return consent;
    }

    /** Opens the install prompt now (client thread, outside a GUI's own event handling). */
    public void openConsent() {
        Minecraft.getMinecraft()
            .displayGuiScreen(new ConsentGui(consent));
    }

    /**
     * Opens the install prompt at the next tick: from a chat command, opening it now would be undone at once, since
     * the chat GUI closes itself after running the command.
     */
    public void openConsentLater() {
        ClientTaskQueue.add(this::openConsent);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase == TickEvent.Phase.END) tick(locator.state(), ClientServices.hostIfStarted());
    }

    /** Applies {@link ServiceWatcher}'s decision; cheap reads every tick, work only on a change. */
    void tick(EngineState engine, BrowserHost host) {
        Set<ServiceWatcher.Action> actions = watcher.update(engine, host == null ? null : host.status());
        if (host == null) return;
        if (actions.contains(ServiceWatcher.Action.VERIFY)) {
            // The installer reads App Control and antivirus refusals out of the launch error text (M3).
            String why = String.join("\n", host.diagnostics());
            AkashicWeb.LOG.warn("AkashicWeb: browser host stopped ({}); verifying the engine", host.status());
            locator.verifyAfterLoadFailure(why);
        }
        if (actions.contains(ServiceWatcher.Action.REENABLE)) {
            AkashicWeb.LOG.info("AkashicWeb: engine ready again ({}); restarting the browser host", engine);
            host.reenable();
        }
    }

    /** Lines for /awdoctor: the installer's state and detail, and the host's own diagnostics. */
    public List<String> diagnostics() {
        List<String> out = new ArrayList<>();
        out.add("engine: " + locator.state() + " | " + locator.detail());
        out.add("engine id: " + locator.engineId() + " | host logs: " + logDir().getPath());
        BrowserHost host = ClientServices.hostIfStarted();
        if (host != null) out.addAll(host.diagnostics());
        return out;
    }

    private static HostStatus hostStatus() {
        BrowserHost h = ClientServices.hostIfStarted();
        return h == null ? null : h.status();
    }

    /**
     * Game exit: QUIT to the host on a JVM shutdown hook thread, never on the client thread (shutdown waits up to
     * about 3.5 s). M2's supervisor also kills the process tree from its own hook if this one is too late.
     */
    private static void addShutdownHook() {
        Thread hook = new Thread(() -> {
            BrowserHost h = ClientServices.hostIfStarted();
            if (h != null) h.shutdown();
        }, "AkashicWeb-Host-Exit");
        try {
            Runtime.getRuntime()
                .addShutdownHook(hook);
        } catch (IllegalStateException | SecurityException e) {
            // Already exiting, or a security manager: M2's own hook and the socket EOF still end the host.
        }
    }
}
