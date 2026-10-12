package com.akashiic.web.client.screen;

import com.akashiic.web.client.gui.LangKeys;
import com.akashiic.web.client.host.HostStatus;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.common.ReasonCode;

/**
 * Decides what a screen shows when it cannot show a live page, in a fixed precedence: server decisions first (the
 * player cannot fix them), then the engine install, then the host process, then this screen's own state. A client
 * without the browser still joins and plays; it just sees these cards (AGENTS.md "Failure is never silent"). Pure;
 * tested in PlaceholderModelTest.
 */
public final class PlaceholderModel {

    /** Everything the decision depends on; filled by ClientScreens each frame it is needed. */
    public static final class Inputs {

        public ServerSession session;
        /** Null when no EngineLocator is wired (M3 absent). */
        public EngineState engine;
        /** False when no BrowserHost implementation is wired or its factory failed. */
        public boolean hostAvailable;
        /** Null while the host has not been started (it starts lazily on the first granted screen). */
        public HostStatus hostStatus;

        public boolean browserLive;
        public boolean granted;
        public boolean hasFrame;
        public boolean painting;
        /** This screen's own failure (upload, texture size, page crash), or null. */
        public ClientReason failure;

        public boolean hasUrl;
    }

    private PlaceholderModel() {}

    /** Null means: draw the live texture without any overlay. */
    public static Placeholder resolve(Inputs in) {
        ServerSession s = in.session;
        if (s != null && s.helloReceived() && !s.protocolOk())
            return Placeholder.card(LangKeys.TITLE, ReasonCode.PROTOCOL_MISMATCH.langKey());
        if (s != null && s.killSwitch())
            return Placeholder.card(LangKeys.TITLE, ReasonCode.DISABLED_BY_SERVER.langKey());
        if (s == null || !s.helloReceived()) return Placeholder.card(LangKeys.WAITING_SERVER);

        Placeholder engine = engine(in.engine);
        if (engine != null) return engine;

        if (!in.hostAvailable) return Placeholder.card(LangKeys.TITLE, ClientReason.HOST_UNAVAILABLE.langKey());
        if (in.hostStatus != null && in.hostStatus.kind != HostStatus.Kind.READY
            && in.hostStatus.kind != HostStatus.Kind.STARTING) {
            return Placeholder.card(LangKeys.TITLE, in.hostStatus.reason.langKey());
        }

        if (in.failure != null) {
            return in.hasFrame ? new Placeholder(in.failure.langKey(), null, null, true)
                : Placeholder.card(LangKeys.TITLE, in.failure.langKey());
        }
        if (!in.granted && !in.browserLive) {
            // Budget or range: keep the last frame with "aproxime-se" if we have one.
            return in.hasFrame ? Placeholder.banner(ReasonCode.BROWSER_BUDGET.langKey())
                : Placeholder.card(LangKeys.TITLE, ReasonCode.BROWSER_BUDGET.langKey());
        }
        if (!in.hasFrame) {
            if (!in.hasUrl) return Placeholder.card(LangKeys.NO_URL);
            if (in.hostStatus == null || in.hostStatus.kind == HostStatus.Kind.STARTING)
                return Placeholder.card(LangKeys.STARTING);
            return Placeholder.card(LangKeys.LOADING);
        }
        return null;
    }

    private static Placeholder engine(EngineState e) {
        if (e == null) return Placeholder.card(LangKeys.TITLE, ClientReason.HOST_UNAVAILABLE.langKey());
        switch (e.kind) {
            case READY:
                return null;
            case ABSENT:
                return Placeholder.card(LangKeys.ENGINE_ABSENT);
            case CONSENT_NEEDED:
                return Placeholder.card(LangKeys.CONSENT_NEEDED, LangKeys.CONSENT_HINT);
            case DOWNLOADING:
                return Placeholder.card(LangKeys.INSTALLING, Math.round(Math.max(0, Math.min(1, e.progress)) * 100));
            case FAILED:
            case UNSUPPORTED:
            default:
                return Placeholder.card(LangKeys.ENGINE_FAILED, e.reason.langKey());
        }
    }
}
