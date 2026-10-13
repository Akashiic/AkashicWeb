package com.akashiic.web.client.screen;

import com.akashiic.web.client.host.BrowserHost;
import com.akashiic.web.client.install.EngineLocator;
import com.akashiic.web.client.install.EngineState;

/**
 * How {@link ClientScreens} reaches the host and the engine installer. The game uses {@link #SERVICES} (backed by
 * {@link ClientServices}); tests pass a fake, so the whole screen lifecycle is tested without M2/M3.
 */
public interface HostAccess {

    /** False when no host implementation is wired or it failed to construct. */
    boolean available();

    /** The host if already started, else null; never starts it. */
    BrowserHost started();

    /** The host, starting it if needed; null when unavailable. */
    BrowserHost obtain();

    /** Null when no EngineLocator is wired. */
    EngineState engine();

    /** The player chose "Not now" on the install prompt this session. */
    default boolean consentPostponed() {
        return false;
    }

    HostAccess SERVICES = new HostAccess() {

        @Override
        public boolean consentPostponed() {
            return ClientServices.consentPostponed();
        }

        @Override
        public boolean available() {
            return ClientServices.hostAvailable();
        }

        @Override
        public BrowserHost started() {
            return ClientServices.hostIfStarted();
        }

        @Override
        public BrowserHost obtain() {
            return ClientServices.host();
        }

        @Override
        public EngineState engine() {
            EngineLocator l = ClientServices.engineLocator();
            return l == null ? null : l.state();
        }
    };
}
