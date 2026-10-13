package com.akashiic.web.client.screen;

import java.util.function.Supplier;

import com.akashiic.web.AkashicWeb;
import com.akashiic.web.client.host.BrowserHost;
import com.akashiic.web.client.install.EngineLocator;

/**
 * Where M5 gets the other client modules' services without depending on their classes: M2's BrowserHost (through a
 * factory, so the host process starts lazily when the first screen is granted a browser, not at game start) and M3's
 * EngineLocator and the consent prompt. {@code ClientWiring} wires them during pre-init; until then every getter
 * returns null and screens show {@link com.akashiic.web.common.ReasonCode#HOST_UNAVAILABLE}. {@link #host()} runs on
 * the client thread; the other members are safe from any thread (the shutdown hook reads {@link #hostIfStarted()}).
 */
public final class ClientServices {

    private static volatile Supplier<BrowserHost> hostFactory;
    private static volatile EngineLocator engineLocator;
    private static volatile Runnable consentOpener;
    private static volatile boolean consentPostponed;

    private static volatile BrowserHost host;
    private static volatile boolean hostFailed;

    private ClientServices() {}

    public static void setHostFactory(Supplier<BrowserHost> factory) {
        hostFactory = factory;
    }

    public static void setEngineLocator(EngineLocator locator) {
        engineLocator = locator;
    }

    /** Opens the consent/install prompt; called when a player right-clicks a screen while consent is needed. */
    public static void setConsentOpener(Runnable opener) {
        consentOpener = opener;
    }

    public static EngineLocator engineLocator() {
        return engineLocator;
    }

    public static Runnable consentOpener() {
        return consentOpener;
    }

    /**
     * "Not now" on the install prompt: right-clicking a screen stops asking for the rest of this game session (the
     * placeholder then names /awinstall, which still opens the prompt).
     */
    public static void setConsentPostponed(boolean postponed) {
        consentPostponed = postponed;
    }

    public static boolean consentPostponed() {
        return consentPostponed;
    }

    /** True when a host can exist (a factory is wired and it has not failed). */
    public static boolean hostAvailable() {
        return host != null || (hostFactory != null && !hostFailed);
    }

    /** The host if it was already started, else null. Never starts it. */
    public static BrowserHost hostIfStarted() {
        return host;
    }

    /**
     * The host, started on first use (client thread). A factory that throws is a wiring or platform bug: it is logged
     * once, the host is marked unavailable for the session and screens show HOST_UNAVAILABLE instead of retrying
     * every tick.
     */
    public static BrowserHost host() {
        if (host != null || hostFailed) return host;
        Supplier<BrowserHost> f = hostFactory;
        if (f == null) return null;
        try {
            host = f.get();
            if (host == null) hostFailed = true;
        } catch (RuntimeException | LinkageError e) {
            // Documented boundary: a broken host module must not take the game down (AGENTS.md).
            hostFailed = true;
            AkashicWeb.LOG.error("AkashicWeb: browser host could not be created; screens stay on placeholders", e);
        }
        return host;
    }
}
