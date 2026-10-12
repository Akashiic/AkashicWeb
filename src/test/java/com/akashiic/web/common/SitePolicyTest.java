package com.akashiic.web.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class SitePolicyTest {

    private static SitePolicy allow(String... hosts) {
        return SitePolicy.of(SitePolicy.Mode.ALLOWLIST, Arrays.asList(hosts), Collections.<String>emptyList());
    }

    @Test
    void allowlistMatchesSubdomainsOnLabelBoundaries() {
        SitePolicy p = allow("youtube.com", "twitch.tv");
        assertTrue(p.allowsHost("youtube.com"));
        assertTrue(p.allowsHost("www.youtube.com"));
        assertTrue(p.allowsHost("M.YouTube.com."));
        assertFalse(p.allowsHost("evilyoutube.com"));
        assertFalse(p.allowsHost("youtube.com.evil.net"));
        assertFalse(p.allowsHost("example.com"));
        assertFalse(p.allowsHost(""));
        assertFalse(p.allowsHost(null));
    }

    @Test
    void blockAlwaysWins() {
        SitePolicy p = SitePolicy
            .of(SitePolicy.Mode.ALLOWLIST, Arrays.asList("example.com"), Arrays.asList("bad.example.com"));
        assertTrue(p.allowsHost("good.example.com"));
        assertFalse(p.allowsHost("bad.example.com"));
        assertFalse(p.allowsHost("x.bad.example.com"));
        SitePolicy b = SitePolicy
            .of(SitePolicy.Mode.BLOCKLIST, Collections.<String>emptyList(), Arrays.asList("grabify.link"));
        assertTrue(b.allowsHost("anything.org"));
        assertFalse(b.allowsHost("grabify.link"));
    }

    @Test
    void entriesAreNormalised() {
        assertEquals("www.youtube.com", SitePolicy.normalizeEntry(" https://www.YouTube.com/watch "));
        assertEquals("twitch.tv", SitePolicy.normalizeEntry("*.twitch.tv"));
        assertEquals("googlevideo.com", SitePolicy.normalizeEntry(".googlevideo.com."));
        assertEquals("a.com", SitePolicy.normalizeEntry("a.com:8080"));
        assertNull(SitePolicy.normalizeEntry("bad host"));
        assertNull(SitePolicy.normalizeEntry("a..com"));
        assertNull(SitePolicy.normalizeEntry("*"));
        assertNull(SitePolicy.normalizeEntry(""));
    }

    @Test
    void listsAreDedupedAndCapped() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 400; i++) many.add("h" + i + ".com");
        many.add(0, "h0.com");
        SitePolicy p = SitePolicy.of(SitePolicy.Mode.ALLOWLIST, many, many);
        assertEquals(SitePolicy.MAX_ENTRIES, p.allow.size());
        assertEquals("h0.com", p.allow.get(0));
        assertEquals("h1.com", p.allow.get(1));
    }

    @Test
    void modeParsing() {
        assertEquals(SitePolicy.Mode.BLOCKLIST, SitePolicy.parseMode(" blocklist "));
        assertNull(SitePolicy.parseMode("both"));
        assertEquals(SitePolicy.Mode.ALLOWLIST, SitePolicy.of(null, null, null).mode);
    }

    @Test
    void gateCombinesEverything() {
        UrlGate gate = WebSettings.defaults()
            .urlGate();
        UrlGate.Result ok = gate.check("www.youtube.com/watch?v=abc");
        assertEquals(ReasonCode.OK, ok.reason);
        assertEquals("https://www.youtube.com/watch?v=abc", ok.url);
        assertEquals(ReasonCode.URL_BLOCKED, gate.check("https://example.com/").reason);
        assertEquals("example.com", gate.check("https://example.com/").detail);
        assertEquals(ReasonCode.URL_INTERNAL, gate.check("http://192.168.0.1/").reason);
        assertEquals(ReasonCode.URL_INVALID, gate.check("file:///etc/passwd").reason);
        assertEquals(ReasonCode.URL_BLOCKED, gate.check("https://grabify.link/abc").reason);
    }

    @Test
    void equalityIsByValue() {
        assertEquals(allow("a.com"), allow("A.com"));
        assertFalse(allow("a.com").equals(allow("b.com")));
    }
}
