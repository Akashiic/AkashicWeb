package com.akashiic.web.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.TestScreens;

/** URL authority and epoch handling (ADR-012): every change bumps the epoch, nothing else does. */
class NavLogicTest {

    private static ScreenState screen() {
        ScreenState s = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 4, 3, 0);
        s.url = "";
        s.epoch = 10;
        return s;
    }

    @Test
    void urlChangeBumpsEpochOnce() {
        ScreenState s = screen();
        UrlHistory h = new UrlHistory();
        assertTrue(NavLogic.setUrl(s, h, "https://a.com/"));
        assertEquals(11, s.epoch);
        assertFalse(NavLogic.setUrl(s, h, "https://a.com/"), "same URL: no new epoch");
        assertEquals(11, s.epoch);
        assertTrue(NavLogic.setUrl(s, h, "https://b.com/"));
        assertEquals(12, s.epoch);
    }

    @Test
    void epochWrapsWithoutBreakingEquality() {
        ScreenState s = screen();
        s.epoch = Integer.MAX_VALUE;
        NavLogic.setUrl(s, new UrlHistory(), "https://a.com/");
        assertEquals(Integer.MIN_VALUE, s.epoch);
    }

    @Test
    void backAndForwardWalkTheServerHistory() {
        ScreenState s = screen();
        UrlHistory h = new UrlHistory();
        NavLogic.setUrl(s, h, "https://a.com/");
        NavLogic.setUrl(s, h, "https://b.com/");
        NavLogic.setUrl(s, h, "https://c.com/");
        assertNull(NavLogic.target("forward", s, h, ""));
        String back = NavLogic.target("back", s, h, "");
        assertEquals("https://b.com/", back);
        int before = s.epoch;
        NavLogic.commit("back", s, h, back);
        assertEquals("https://b.com/", s.url);
        assertEquals(before + 1, s.epoch);
        assertEquals("https://c.com/", NavLogic.target("forward", s, h, ""));
        // A new page after going back drops the forward entry, like a browser.
        NavLogic.setUrl(s, h, "https://d.com/");
        assertNull(NavLogic.target("forward", s, h, ""));
        assertEquals("https://b.com/", NavLogic.target("back", s, h, ""));
    }

    @Test
    void peekingDoesNotMoveTheCursor() {
        ScreenState s = screen();
        UrlHistory h = new UrlHistory();
        NavLogic.setUrl(s, h, "https://a.com/");
        NavLogic.setUrl(s, h, "https://b.com/");
        NavLogic.target("back", s, h, ""); // refused later by the site policy: nothing committed
        assertEquals("https://a.com/", NavLogic.target("back", s, h, ""));
        assertNull(NavLogic.target("forward", s, h, ""));
    }

    @Test
    void reloadHomeStopAndClear() {
        ScreenState s = screen();
        UrlHistory h = new UrlHistory();
        assertNull(NavLogic.target("reload", s, h, "https://home/"), "nothing to reload on a blank screen");
        NavLogic.setUrl(s, h, "https://a.com/");
        assertEquals("https://a.com/", NavLogic.target("reload", s, h, ""));
        int e = s.epoch;
        NavLogic.commit("reload", s, h, "https://a.com/");
        assertEquals(e + 1, s.epoch, "reload must reach every viewer");
        assertNull(NavLogic.target("stop", s, h, "https://home/"));
        assertEquals("https://home/", NavLogic.target("home", s, h, "https://home/"));
        assertNull(NavLogic.target("home", s, h, ""));
        NavLogic.commit("home", s, h, "https://home/");
        assertEquals("https://a.com/", NavLogic.target("back", s, h, ""));
        NavLogic.clear(s, h);
        assertEquals("", s.url);
        assertEquals(0, h.size());
    }

    @Test
    void historyIsBounded() {
        UrlHistory h = new UrlHistory();
        for (int i = 0; i < 40; i++) h.push("https://a.com/" + i);
        assertEquals(UrlHistory.MAX, h.size());
        assertEquals("https://a.com/38", h.peekBack());
    }
}
