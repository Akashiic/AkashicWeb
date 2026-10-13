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

    /** Review low finding (security): a login callback must not reach the server log or the followers' browsers. */
    @Test
    void urlsCarryingCredentialsOrTooLongAreNeverReported() {
        String server = "https://a.com/";
        for (String login : new String[] { "https://a.com/cb?code=abc&state=1", "https://a.com/cb#access_token=x",
            "https://a.com/?ID_TOKEN=x", "https://a.com/magic?login_token=1", "https://a.com/x?a=1&password=2" }) {
            assertTrue(UrlConvergence.carriesCredentials(login), login);
            assertFalse(UrlConvergence.shouldReport(server, login, true, false, SETTLED, 10_000, 0, false), login);
        }
        assertFalse(UrlConvergence.carriesCredentials("https://www.youtube.com/watch?v=abc&t=10"));
        assertFalse(UrlConvergence.carriesCredentials("https://a.com/codes/token"), "path words are fine");
        StringBuilder longUrl = new StringBuilder("https://a.com/?q=");
        while (longUrl.length() <= UrlConvergence.MAX_REPORT_LENGTH) longUrl.append('x');
        assertFalse(UrlConvergence.reportable(longUrl.toString()), "NetCodec would cut it into another URL");
    }

    @Test
    void reportsAreRateLimited() {
        String server = "https://a.com/";
        assertFalse(UrlConvergence.shouldReport(server, "https://a.com/1", true, false, SETTLED, 10_500, 10_000, true));
        assertTrue(UrlConvergence.shouldReport(server, "https://a.com/1", true, false, SETTLED, 11_000, 10_000, true));
    }
}
