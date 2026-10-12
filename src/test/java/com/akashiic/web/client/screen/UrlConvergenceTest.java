package com.akashiic.web.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UrlConvergenceTest {

    private static final long SETTLED = UrlConvergence.NAVIGATE_SETTLE_MS;

    @Test
    void normalisesHostSchemeAndDefaultPorts() {
        assertEquals("https://example.com/", UrlConvergence.normalize("HTTPS://Example.COM:443"));
        assertEquals("http://example.com/a?b=1", UrlConvergence.normalize("http://example.com:80/a?b=1"));
        assertEquals("https://example.com:8443/", UrlConvergence.normalize("https://example.com:8443/"));
        assertEquals("https://example.com/#t=10", UrlConvergence.normalize("https://example.com/#t=10"));
        assertEquals("https://example.com/", UrlConvergence.normalize("https://example.com/#"));
        assertEquals("not a url", UrlConvergence.normalize(" not a url "));
        assertEquals("", UrlConvergence.normalize(null));
        assertTrue(UrlConvergence.sameDocument("https://youtube.com", "https://YOUTUBE.com:443/"));
        assertFalse(UrlConvergence.sameDocument("https://youtube.com/a", "https://youtube.com/b"));
    }

    @Test
    void onlyTheControllerReportsSettledWebNavigations() {
        String server = "https://youtube.com/";
        String page = "https://www.youtube.com/watch?v=x";
        assertTrue(UrlConvergence.shouldReport(server, page, true, false, SETTLED, 10_000, 0, false));
        assertFalse(UrlConvergence.shouldReport(server, page, false, false, SETTLED, 10_000, 0, false), "follower");
        assertFalse(UrlConvergence.shouldReport(server, page, true, true, SETTLED, 10_000, 0, false), "loading");
        assertFalse(UrlConvergence.shouldReport(server, page, true, false, 500, 10_000, 0, false), "just navigated");
        assertFalse(UrlConvergence.shouldReport(server, "https://youtube.com", true, false, SETTLED, 10_000, 0, false));
        assertFalse(UrlConvergence.shouldReport(server, "about:blank", true, false, SETTLED, 10_000, 0, false));
        assertFalse(UrlConvergence.shouldReport(server, "chrome-error://x", true, false, SETTLED, 10_000, 0, false));
        assertFalse(UrlConvergence.shouldReport(server, null, true, false, SETTLED, 10_000, 0, false));
    }

    @Test
    void reportsAreRateLimited() {
        String server = "https://a.com/";
        assertFalse(UrlConvergence.shouldReport(server, "https://a.com/1", true, false, SETTLED, 10_500, 10_000, true));
        assertTrue(UrlConvergence.shouldReport(server, "https://a.com/1", true, false, SETTLED, 11_000, 10_000, true));
    }
}
