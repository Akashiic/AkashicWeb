package com.akashiic.web.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.akashiic.web.client.sync.LocalMedia;
import com.akashiic.web.common.ReasonCode;

class HostMessagesTest {

    @Test
    void parsesMediaAndStampsArrival() {
        LocalMedia m = HostMessages.media(
            "{\"t\":123,\"mediaTime\":42.5,\"paused\":false,\"rate\":1.03,\"duration\":null,\"live\":true,"
                + "\"behindLiveEdge\":3.2,\"adShowing\":false,\"fullscreen\":true}",
            777,
            99_000.5);
        assertEquals(42.5, m.mediaTime, 1e-9);
        assertEquals(1.03f, m.rate, 1e-6);
        assertTrue(Double.isNaN(m.duration), "Infinity serialises as null in JSON");
        assertTrue(m.live);
        assertEquals(3.2, m.behindLiveEdge, 1e-9);
        assertTrue(m.fullscreen);
        assertEquals(777, m.receivedMs);
        assertEquals(99_000.5, m.serverStampMs, 1e-9);
    }

    @Test
    void rejectsMediaWithoutAUsablePosition() {
        assertNull(HostMessages.media("{\"paused\":true}", 0, 0));
        assertNull(HostMessages.media("{\"mediaTime\":-1}", 0, 0));
        assertNull(HostMessages.media("{\"mediaTime\":\"12\"}", 0, 0), "wrong type");
        assertNull(HostMessages.media("not json", 0, 0));
        assertNull(HostMessages.media("[1,2]", 0, 0));
        assertNull(HostMessages.media("", 0, 0));
        assertNull(HostMessages.media(null, 0, 0));
        LocalMedia defaults = HostMessages.media("{\"mediaTime\":1,\"rate\":\"fast\",\"behindLiveEdge\":null}", 0, 0);
        assertEquals(1f, defaults.rate, 0f);
        assertEquals(0, defaults.behindLiveEdge, 0);
        assertFalse(defaults.adShowing);
    }

    @Test
    void parsesStateAndCapsText() {
        PageState s = HostMessages.state(
            "{\"url\":\"https://example.com/\",\"title\":\"Ação\",\"loading\":true,\"canGoBack\":true,"
                + "\"canGoForward\":false,\"audible\":true,\"cursor\":\"pointer\"}");
        assertEquals("https://example.com/", s.url);
        assertEquals("Ação", s.title);
        assertTrue(s.loading);
        assertTrue(s.canGoBack);
        assertFalse(s.canGoForward);
        assertTrue(s.audible);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5_000; i++) big.append('t');
        assertEquals(HostMessages.MAX_TEXT, HostMessages.state("{\"title\":\"" + big + "\"}").title.length());
        assertEquals("", HostMessages.state("{\"url\":5}").url);
        assertNull(HostMessages.state("{"));
    }

    @Test
    void parsesEventKind() {
        assertEquals("renderer-gone", HostMessages.eventKind("{\"kind\":\"renderer-gone\",\"detail\":{}}"));
        assertNull(HostMessages.eventKind("{\"detail\":1}"));
        assertNull(HostMessages.eventKind("{\"kind\":\"\"}"));
        assertEquals(ReasonCode.PAGE_CRASHED, ClientReason.fromEventKind("renderer-gone"));
        assertEquals(ReasonCode.PAGE_LOAD_FAILED, ClientReason.fromEventKind("load-error"));
        assertNull(ClientReason.fromEventKind("blocked-url"));
        assertEquals("akashicweb.reason.page_crashed", ReasonCode.PAGE_CRASHED.langKey());
    }
}
