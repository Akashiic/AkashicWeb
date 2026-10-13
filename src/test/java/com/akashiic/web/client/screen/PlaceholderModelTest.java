package com.akashiic.web.client.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.akashiic.web.client.gui.LangKeys;
import com.akashiic.web.client.host.HostStatus;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.protocol.Protocol;

class PlaceholderModelTest {

    private static PlaceholderModel.Inputs live() {
        PlaceholderModel.Inputs in = new PlaceholderModel.Inputs();
        in.session = new ServerSession();
        in.session.onHello(Protocol.MAJOR, 0, false, SitePolicy.blocklist(Collections.<String>emptyList()));
        in.engine = new EngineState(EngineState.Kind.READY, 1, ReasonCode.OK);
        in.hostAvailable = true;
        in.hostStatus = HostStatus.READY;
        in.browserLive = true;
        in.granted = true;
        in.hasFrame = true;
        in.painting = true;
        in.hasUrl = true;
        return in;
    }

    @Test
    void aLivePageHasNoPlaceholder() {
        assertNull(PlaceholderModel.resolve(live()));
    }

    @Test
    void serverDecisionsComeFirst() {
        PlaceholderModel.Inputs in = live();
        in.session.onHello(Protocol.MAJOR + 1, 0, false, null);
        assertEquals(ReasonCode.PROTOCOL_MISMATCH.langKey(), PlaceholderModel.resolve(in).detailKey);
        in.session.onHello(Protocol.MAJOR, 0, true, null);
        assertEquals(ReasonCode.DISABLED_BY_SERVER.langKey(), PlaceholderModel.resolve(in).detailKey);
        in.session.reset();
        assertEquals(LangKeys.WAITING_SERVER, PlaceholderModel.resolve(in).key);
    }

    @Test
    void engineInstallStates() {
        PlaceholderModel.Inputs in = live();
        in.engine = new EngineState(EngineState.Kind.ABSENT, 0, ReasonCode.ENGINE_ABSENT);
        assertEquals(LangKeys.ENGINE_ABSENT, PlaceholderModel.resolve(in).key);
        in.engine = new EngineState(EngineState.Kind.CONSENT_NEEDED, 0, null);
        Placeholder consent = PlaceholderModel.resolve(in);
        assertEquals(LangKeys.CONSENT_NEEDED, consent.key);
        assertEquals(LangKeys.CONSENT_HINT, consent.detailKey);
        in.engine = new EngineState(EngineState.Kind.DOWNLOADING, 0.426f, null);
        Placeholder dl = PlaceholderModel.resolve(in);
        assertEquals(LangKeys.INSTALLING, dl.key);
        assertArrayEquals(new Object[] { 43 }, dl.args);
        in.engine = new EngineState(EngineState.Kind.FAILED, 0, ReasonCode.HASH_MISMATCH);
        assertEquals(ReasonCode.HASH_MISMATCH.langKey(), PlaceholderModel.resolve(in).detailKey);
        in.engine = new EngineState(EngineState.Kind.UNSUPPORTED, 0, ReasonCode.UNSUPPORTED_ARCH);
        assertEquals(ReasonCode.UNSUPPORTED_ARCH.langKey(), PlaceholderModel.resolve(in).detailKey);
        in.engine = null;
        assertEquals(ClientReason.HOST_UNAVAILABLE.langKey(), PlaceholderModel.resolve(in).detailKey);
    }

    @Test
    void hostStates() {
        PlaceholderModel.Inputs in = live();
        in.hostAvailable = false;
        assertEquals(ClientReason.HOST_UNAVAILABLE.langKey(), PlaceholderModel.resolve(in).detailKey);
        in.hostAvailable = true;
        in.hostStatus = HostStatus.down(ReasonCode.HOST_CRASH_LOOP);
        assertEquals(ReasonCode.HOST_CRASH_LOOP.langKey(), PlaceholderModel.resolve(in).detailKey);
        in.hostStatus = HostStatus.STARTING;
        in.hasFrame = false;
        assertEquals(LangKeys.STARTING, PlaceholderModel.resolve(in).key);
        in.hostStatus = HostStatus.READY;
        assertEquals(LangKeys.LOADING, PlaceholderModel.resolve(in).key);
        in.hasUrl = false;
        assertEquals(LangKeys.NO_URL, PlaceholderModel.resolve(in).key);
    }

    @Test
    void budgetKeepsTheLastFrameWithABanner() {
        PlaceholderModel.Inputs in = live();
        in.granted = false;
        in.browserLive = false;
        Placeholder banner = PlaceholderModel.resolve(in);
        assertTrue(banner.overFrame);
        assertEquals(ReasonCode.BROWSER_BUDGET.langKey(), banner.key);
        in.hasFrame = false;
        Placeholder card = PlaceholderModel.resolve(in);
        assertFalse(card.overFrame);
        assertEquals(ReasonCode.BROWSER_BUDGET.langKey(), card.detailKey);
    }

    @Test
    void pageFailuresShowTheirReason() {
        PlaceholderModel.Inputs in = live();
        in.failure = ClientReason.PAGE_CRASHED;
        Placeholder p = PlaceholderModel.resolve(in);
        assertTrue(p.overFrame);
        assertEquals(ClientReason.PAGE_CRASHED.langKey(), p.key);
        in.hasFrame = false;
        assertEquals(ClientReason.PAGE_CRASHED.langKey(), PlaceholderModel.resolve(in).detailKey);
    }

    @Test
    void placeholdersCompareByValue() {
        assertEquals(Placeholder.card("k", 1), Placeholder.card("k", 1));
        assertFalse(
            Placeholder.card("k", 1)
                .equals(Placeholder.card("k", 2)));
        assertFalse(
            Placeholder.banner("k")
                .equals(Placeholder.card("k")));
    }
}
