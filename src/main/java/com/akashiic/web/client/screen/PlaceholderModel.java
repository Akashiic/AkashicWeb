package com.akashiic.web.client.screen;

import com.akashiic.web.client.gui.LangKeys;
import com.akashiic.web.client.host.HostStatus;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.common.ReasonCode;

/**
 * Decides what a screen shows when it cannot show a live page, in a fixed precedence: server decisions first (the
 * player cannot fix them), then the engine install, then the host process, then this screen's own state. A client
 * without the browser still joins and plays; it just sees these cards (AGENTS.md "Failure is never silent"). Every
 * reason is built with exactly one detail argument ({@link Placeholder}). Pure; tested in PlaceholderModelTest and
 * PlaceholderTextTest.
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
        public ReasonCode failure;
        /** The failure's detail argument (a host name, a size), "" when there is none. */
        public String failureDetail = "";

        public boolean hasUrl;
        /** The player chose "Not now" on the install prompt this session: the hint names /awinstall instead. */
        public boolean consentPostponed;
    }

    private PlaceholderModel() {}

    /** Null means: draw the live texture without any overlay. */
    public static Placeholder resolve(Inputs in) {
        ServerSession s = in.session;
        if (s != null && s.helloReceived() && !s.protocolOk())
            return Placeholder.reasonCard(ReasonCode.PROTOCOL_MISMATCH, "");
        if (s != null && s.killSwitch()) return Placeholder.reasonCard(ReasonCode.DISABLED_BY_SERVER, "");
        if (s == null || !s.helloReceived()) return Placeholder.card(LangKeys.WAITING_SERVER);

        Placeholder engine = engine(in.engine, in.consentPostponed);
        if (engine != null) return engine;

        if (!in.hostAvailable) return Placeholder.reasonCard(ReasonCode.HOST_UNAVAILABLE, "");
        if (in.hostStatus != null && in.hostStatus.kind != HostStatus.Kind.READY
            && in.hostStatus.kind != HostStatus.Kind.STARTING) {
            return Placeholder.reasonCard(in.hostStatus.reason, "");
        }

        // A screen without a page (freshly formed, or cleared by an admin) has no browser by design: no "get closer".
        if (!in.hasUrl) return Placeholder.card(LangKeys.NO_URL);
        if (in.failure != null) {
            return in.hasFrame ? Placeholder.reasonBanner(in.failure, in.failureDetail)
                : Placeholder.reasonCard(in.failure, in.failureDetail);
        }
        if (!in.granted && !in.browserLive) {
            // Budget or range: keep the last frame with "aproxime-se" if we have one.
            return in.hasFrame ? Placeholder.reasonBanner(ReasonCode.BROWSER_BUDGET, "")
                : Placeholder.reasonCard(ReasonCode.BROWSER_BUDGET, "");
        }
        if (!in.hasFrame) {
            if (in.hostStatus == null || in.hostStatus.kind == HostStatus.Kind.STARTING)
                return Placeholder.card(LangKeys.STARTING);
            return Placeholder.card(LangKeys.LOADING);
        }
        return null;
    }

    private static Placeholder engine(EngineState e, boolean consentPostponed) {
        if (e == null) return Placeholder.reasonCard(ReasonCode.HOST_UNAVAILABLE, "");
        switch (e.kind) {
            case READY:
                return null;
            case ABSENT:
                return Placeholder.card(LangKeys.ENGINE_ABSENT);
            case CONSENT_NEEDED:
                return Placeholder.hint(
                    LangKeys.CONSENT_NEEDED,
                    consentPostponed ? LangKeys.CONSENT_POSTPONED : LangKeys.CONSENT_HINT);
            case DOWNLOADING:
                return Placeholder.card(LangKeys.INSTALLING, Math.round(Math.max(0, Math.min(1, e.progress)) * 100));
            case FAILED:
            case UNSUPPORTED:
            default:
                return Placeholder.reason(LangKeys.ENGINE_FAILED, e.reason, "");
        }
    }
}
