package com.akashiic.web.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** TextSanitizer, RateLimiter and ClockMath (copied from AkashicFM) plus ReasonCode and WebSettings. */
class SmallUtilsTest {

    @Test
    void sanitizerStripsFormattingAndControls() {
        assertEquals("Red text", TextSanitizer.clean("§cRed§r text\n", 64));
        assertEquals("abc", TextSanitizer.clean("a‮b​c", 64)); // bidi override, zero-width space
        assertEquals("ab", TextSanitizer.clean("abcdef", 2));
        assertEquals("a", TextSanitizer.clean("a😀", 2)); // emoji pair does not fit: dropped whole
        assertEquals("a😀", TextSanitizer.clean("a😀", 3));
        assertEquals("", TextSanitizer.clean(null, 5));
    }

    @Test
    void inputTextKeepsTheSectionSign() {
        assertEquals("a§b ção", TextSanitizer.cleanInput("a§b ção\u0007", 64));
        assertEquals("x", TextSanitizer.cleanInput("x\uD800", 64)); // lone surrogate dropped
    }

    @Test
    void urlCleaningDropsWhitespace() {
        assertEquals("https://a.com/b", TextSanitizer.cleanUrl(" https://a.com/ b\t", 64));
    }

    @Test
    void rateLimiterBurstsAndRefills() {
        AtomicLong now = new AtomicLong();
        RateLimiter l = new RateLimiter(now::get);
        UUID p = UUID.randomUUID();
        for (int i = 0; i < 8; i++) assertTrue(l.tryAcquire(p, 4), "burst " + i);
        assertFalse(l.tryAcquire(p, 4));
        now.addAndGet(250_000_000L); // a quarter second refills one token at 4/s
        assertTrue(l.tryAcquire(p, 4));
        assertFalse(l.tryAcquire(p, 4));
        assertTrue(l.tryAcquire(UUID.randomUUID(), 4), "buckets are per player");
        l.forget(p);
        assertTrue(l.tryAcquire(p, 4));
        assertFalse(l.tryAcquire(null, 4));
    }

    @Test
    void clockMath() {
        // Client clock 1000 ms behind the server, 40 ms each way.
        assertEquals(1000, ClockMath.offset(0, 1040, 1040, 80));
        assertEquals(80, ClockMath.rtt(0, 1040, 1040, 80));
        assertEquals(1000, ClockMath.offset(0, 1040, 80));
        ClockMath.Estimator e = new ClockMath.Estimator(8, 3);
        assertEquals(0, e.offset());
        e.add(1000, 80);
        e.add(1300, 600); // a slow exchange: ignored by the min-RTT filter
        e.add(1002, 82);
        e.add(998, 81);
        e.add(1, -5); // invalid RTT never counts
        assertEquals(1000, e.offset());
        assertEquals(80, e.bestRtt());
        assertTrue(e.ready(4));
        assertFalse(e.ready(5));
    }

    @Test
    void reasonCodesParseByName() {
        assertEquals(ReasonCode.URL_BLOCKED, ReasonCode.parse("URL_BLOCKED"));
        assertNull(ReasonCode.parse("FROM_THE_FUTURE"));
        assertNull(ReasonCode.parse(null));
        assertEquals("akashicweb.reason.screen_too_big", ReasonCode.SCREEN_TOO_BIG.langKey());
    }

    @Test
    void settingsDefaultsAndClamps() {
        WebSettings d = WebSettings.defaults();
        assertEquals(16, d.maxWidth);
        assertEquals(SitePolicy.Mode.ALLOWLIST, d.sitePolicy.mode);
        assertEquals(5, d.mediaClockIntervalTicks()); // 4 Hz
        assertFalse(d.killSwitch);
        WebSettings.Builder b = new WebSettings.Builder();
        b.maxWidth = 1000;
        b.adminMaxWidth = 2;
        b.mediaClockHz = 0;
        b.defaultResolutionW = 99999;
        WebSettings s = b.build();
        assertEquals(64, s.maxWidth);
        assertEquals(64, s.adminMaxWidth); // never below the player maximum
        assertEquals(1, s.mediaClockHz);
        assertEquals(20, s.mediaClockIntervalTicks());
        assertEquals(s.maxResolutionW, s.defaultResolutionW);
    }

    @Test
    void screenStateCopyIsDeep() {
        ScreenState s = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 2, 2, 0);
        ScreenState c = s.copy();
        c.friends.clear();
        c.friendNames.clear();
        c.url = "x";
        assertEquals(1, s.friends.size());
        assertEquals("Friend", s.friendNames.get(TestScreens.FRIEND));
        assertEquals(s.key, c.key);
    }
}
