package com.akashiic.web.client.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.SitePolicy;

/**
 * The client-side URL decision (task item 3; CLAUDE.md: site policy and internal-address block on both server and
 * client): M4's UrlGate with {@code new UrlPolicy(true, 2048)}, after a shape check, before any URL leaves the JVM.
 */
class ClientUrlCheckTest {

    private static final SitePolicy OPEN = SitePolicy.blocklist(Collections.<String>emptyList());

    private static ClientUrlCheck.Decision check(SitePolicy p, String url) {
        return new ClientUrlCheck(p, false).check(url);
    }

    @Test
    void blankAndEmptyMeanABlankPage() {
        for (SitePolicy p : Arrays.asList(OPEN, null)) {
            for (String u : new String[] { null, "", "about:blank" }) {
                ClientUrlCheck.Decision d = check(p, u);
                assertTrue(d.ok(), String.valueOf(u));
                assertEquals("about:blank", d.url);
            }
        }
    }

    @Test
    void publicHttpUrlsPassUnchanged() {
        for (String u : new String[] { "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "http://example.com:8080/x",
            "https://example.com:443/", "HTTPS://Example.com/Path?q=a@b#frag", "https://example.com/ação" }) {
            ClientUrlCheck.Decision d = check(OPEN, u);
            assertTrue(d.ok(), u + " -> " + d.code + " " + d.reason);
            assertEquals(u, d.url, "what is checked is what is sent");
        }
    }

    @Test
    void internalAddressesAreRefusedWhateverThePolicy() {
        String[] internal = { "http://127.0.0.1/", "http://localhost:8080/", "http://foo.localhost/", "http://[::1]/",
            "http://[::ffff:127.0.0.1]/", "http://2130706433/", "http://0x7f000001/", "http://10.0.0.1/",
            "http://192.168.0.10/", "http://172.16.5.4/", "http://100.64.1.1/", "http://169.254.169.254/latest",
            "http://router.local/", "http://nas.lan/", "http://0.0.0.0/" };
        for (String u : internal) {
            ClientUrlCheck.Decision d = check(OPEN, u);
            assertEquals(ReasonCode.URL_INTERNAL, d.code, u);
            assertEquals("internal", d.reason, u);
            assertFalse(d.host.isEmpty(), u);
        }
        // Short, hex and octal IPv4 forms that Chromium reads as loopback. Depending on how java.net.URI (the
        // server's parser) splits them, the gate refuses them as internal or as invalid: refused either way.
        for (String u : new String[] { "http://127.1/", "http://0x7f.1/", "http://0177.0.0.1/",
            "http://0x7f.0.0.1/" }) {
            ClientUrlCheck.Decision d = check(OPEN, u);
            assertFalse(d.ok(), u);
            assertTrue(d.code == ReasonCode.URL_INTERNAL || d.code == ReasonCode.URL_INVALID, u + " -> " + d.code);
        }
        assertEquals(
            "{\"kind\":\"blocked-url\",\"detail\":{\"reason\":\"internal\",\"code\":\"URL_INTERNAL\",\"host\":\"127.0.0.1\",\"via\":\"client\"}}",
            check(OPEN, "http://127.0.0.1/").event());
    }

    @Test
    void theSitePolicyAppliesAndUnknownModesDenyEverything() {
        SitePolicy allow = new SitePolicy(
            SitePolicy.Mode.ALLOWLIST,
            Collections.singletonList("youtube.com"),
            Collections.singletonList("music.youtube.com"));
        assertTrue(check(allow, "https://m.youtube.com/").ok());
        ClientUrlCheck.Decision notAllowed = check(allow, "https://evilyoutube.com/");
        assertEquals(ReasonCode.URL_BLOCKED, notAllowed.code);
        assertEquals("not-allowed", notAllowed.reason);
        assertEquals("evilyoutube.com", notAllowed.host);
        ClientUrlCheck.Decision blocked = check(allow, "https://music.youtube.com/");
        assertEquals(ReasonCode.URL_BLOCKED, blocked.code);
        assertEquals("blocked", blocked.reason);
        assertEquals(
            ReasonCode.URL_BLOCKED,
            check(SitePolicy.blocklist(Collections.singletonList("bad.example")), "https://www.bad.example/").code);

        // No policy yet, or a mode this build does not know: deny-all, never allow-all.
        SitePolicy nullMode = new SitePolicy(
            null,
            Collections.singletonList("example.com"),
            Collections.<String>emptyList());
        for (SitePolicy p : Arrays.asList(null, nullMode)) {
            ClientUrlCheck.Decision d = check(p, "https://example.com/");
            assertEquals(ReasonCode.URL_BLOCKED, d.code);
            assertEquals("not-allowed", d.reason);
        }
    }

    @Test
    void badSchemesCredentialsBackslashesAndPortsAreInvalid() {
        String[][] cases = { { "javascript:alert(1)", "scheme" }, { "file:///etc/passwd", "scheme" },
            { "data:text/html,x", "scheme" }, { "chrome://settings", "scheme" }, { "youtube.com", "scheme" },
            { "https://user:pw@example.com/", "credentials" },
            { "http://allowed.example@evil.example/", "credentials" },
            { "http://allowed.example\\@evil.example/", "backslash" }, { "https://example.com:22/", "port" },
            { "https://exa mple.com/", "malformed" }, { "https:///example.com", "no_host" } };
        for (String[] c : cases) {
            ClientUrlCheck.Decision d = check(OPEN, c[0]);
            assertEquals(ReasonCode.URL_INVALID, d.code, c[0]);
            assertEquals(c[1], d.reason, c[0]);
        }
        char[] longPath = new char[2100];
        Arrays.fill(longPath, 'a');
        assertEquals(ReasonCode.URL_INVALID, check(OPEN, "https://example.com/" + new String(longPath)).code);
    }

    /** Characters one parser drops and another keeps: the URL must already be in its normal form. */
    @Test
    void whatIsCheckedIsWhatIsSent() {
        // Built from code points so the source shows what they are: zero-width space, soft hyphen, line separator,
        // next line (a C1 control) and right-to-left override.
        String base = "https://example.com/";
        for (String u : new String[] { base + (char) 0x200B, "https://exa" + (char) 0x00AD + "mple.com/",
            base + (char) 0x2028, base + (char) 0x0085, base + (char) 0x202E }) {
            ClientUrlCheck.Decision d = check(OPEN, u);
            assertEquals(ReasonCode.URL_INVALID, d.code, u);
            assertEquals("malformed", d.reason, u);
        }
    }

    @Test
    void fileUrlsPassOnlyThroughThePackagePrivateTestSeam() {
        assertEquals(ReasonCode.URL_INVALID, check(OPEN, "file:///tmp/page.html").code);
        ClientUrlCheck seam = new ClientUrlCheck(null, true);
        assertTrue(
            seam.check("file:///tmp/page.html")
                .ok());
        assertFalse(
            seam.check("file:///tmp/a b.html")
                .ok(),
            "no whitespace even then");
        assertFalse(
            seam.check("file://server/share/x.html")
                .ok(),
            "local paths only");
        assertEquals(ReasonCode.URL_INTERNAL, seam.check("http://127.0.0.1/").code, "the seam changes nothing else");
    }
}
