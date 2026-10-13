package com.akashiic.web.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.akashiic.web.client.host.JsonParser;

class UrlPolicyTest {

    private final UrlPolicy policy = new UrlPolicy(false, 1024);

    private ReasonCode reason(String url) {
        try {
            policy.check(policy.normalize(url));
            return ReasonCode.OK;
        } catch (UrlPolicy.PolicyException e) {
            return e.reason;
        }
    }

    @Test
    void publicPagesPass() {
        assertEquals(ReasonCode.OK, reason("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals(ReasonCode.OK, reason("http://twitch.tv/akashic"));
        assertEquals(ReasonCode.OK, reason("https://8.8.8.8/"));
        assertEquals(ReasonCode.OK, reason("https://youtube.com:443/"));
    }

    @Test
    void normalizeAddsHttpsAndDropsWhitespace() {
        assertEquals("https://youtube.com/watch?v=x", policy.normalize("  youtube.com/watch?v=x "));
        assertEquals("https://localhost:8080/", policy.normalize("localhost:8080/"));
        assertEquals("http://a.com/b", policy.normalize("http://a.com/\u200Bb"));
        assertEquals("javascript:alert(1)", policy.normalize("javascript:alert(1)"));
        assertEquals("", policy.normalize("   "));
    }

    @Test
    void onlyHttpAndHttps() {
        for (String u : new String[] { "javascript:alert(1)", "file:///C:/Windows/win.ini", "ftp://example.com/",
            "data:text/html,<b>x</b>", "chrome://settings", "devtools://devtools/x", "about:blank",
            "view-source:https://a.com" }) {
            assertEquals(ReasonCode.URL_INVALID, reason(u), u);
        }
    }

    @Test
    void credentialsPortsAndLength() {
        assertEquals(ReasonCode.URL_INVALID, reason("https://user:pw@youtube.com/"));
        assertEquals(ReasonCode.URL_INVALID, reason("https://youtube.com:8080/"));
        assertEquals(ReasonCode.URL_INVALID, reason("https://youtube.com:22/"));
        assertEquals(ReasonCode.OK, new UrlPolicyTestHelper(true).reason("https://example.com:8080/"));
        assertEquals(ReasonCode.URL_INVALID, new UrlPolicyTestHelper(true).reason("https://example.com:22/"));
        StringBuilder longUrl = new StringBuilder("https://a.com/");
        while (longUrl.length() < 1100) longUrl.append('x');
        assertEquals(ReasonCode.URL_INVALID, reason(longUrl.toString()));
        assertEquals(ReasonCode.URL_INVALID, reason(""));
    }

    @Test
    void internalAddressesAreRefused() {
        String[] internal = { "http://127.0.0.1/", "http://localhost/", "http://LOCALHOST./", "http://foo.localhost/",
            "http://printer.local/", "http://router.lan/", "http://x.home.arpa/", "http://10.0.0.1/",
            "http://192.168.1.1/", "http://172.16.5.4/", "http://169.254.169.254/latest/meta-data/",
            "http://100.64.0.1/", "http://0.0.0.0/", "http://[::1]/", "http://[fc00::1]/", "http://[fe80::1]/",
            "http://[::ffff:127.0.0.1]/", "http://[::ffff:192.168.0.1]/", "http://2130706433/", "http://224.0.0.1/",
            "http://255.255.255.255/" };
        for (String u : internal) assertEquals(ReasonCode.URL_INTERNAL, reason(u), u);
    }

    /**
     * Forms Chromium reads as loopback but java.net.URI does not parse as a host: they must be refused, never let
     * through as "a name".
     */
    @Test
    void obfuscatedLoopbackNeverPasses() {
        for (String u : new String[] { "http://0x7f.0.0.1/", "http://0x7f000001/", "http://0177.0.0.1/",
            "http://127.1/", "http://127.0.0.%31/", "http://%6c%6f%63%61%6c%68%6f%73%74/", "http://1.2.3.4.5/",
            "http://08.0.0.1/", "http://foo.0x10/" }) {
            assertNotEquals(ReasonCode.OK, reason(u), u);
        }
    }

    @Test
    void whatwgIpv4Parser() throws Exception {
        byte[] loop = { 127, 0, 0, 1 };
        assertArrayEquals(loop, UrlPolicy.parseIpv4("127.0.0.1"));
        assertArrayEquals(loop, UrlPolicy.parseIpv4("127.1"));
        assertArrayEquals(loop, UrlPolicy.parseIpv4("0x7f.1"));
        assertArrayEquals(loop, UrlPolicy.parseIpv4("0177.0.0.1"));
        assertArrayEquals(loop, UrlPolicy.parseIpv4("2130706433"));
        assertArrayEquals(loop, UrlPolicy.parseIpv4("0x7F000001"));
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, UrlPolicy.parseIpv4("1.2.3.4."));
        assertArrayEquals(new byte[] { 15, 0, 0, 1 }, UrlPolicy.parseIpv4("017.0.0.1")); // octal 017 = 15
        assertNull(UrlPolicy.parseIpv4("256.0.0.1"));
        assertNull(UrlPolicy.parseIpv4("1.2.3.4.5"));
        assertNull(UrlPolicy.parseIpv4("08.0.0.1"));
        assertNull(UrlPolicy.parseIpv4("1.2.3.256"));
        assertNull(UrlPolicy.parseIpv4("4294967296"));
        assertTrue(UrlPolicy.endsInNumber("1.2.3.4"));
        assertTrue(UrlPolicy.endsInNumber("foo.0x10"));
        assertTrue(UrlPolicy.endsInNumber("foo.12."));
        assertFalse(UrlPolicy.endsInNumber("example.com"));
        assertFalse(UrlPolicy.endsInNumber("foo.bar1"));
        assertNull(UrlPolicy.parseLiteral("www.youtube.com"));
        assertEquals(InetAddress.getByAddress(loop), UrlPolicy.parseLiteral("0x7f.1"));
        assertThrows(UrlPolicy.PolicyException.class, () -> UrlPolicy.parseLiteral("foo.0x"));
    }

    /** Review finding: IPv6 forms that embed an internal IPv4 address passed the check (matters in BLOCKLIST mode). */
    @Test
    void ipv6FormsThatEmbedAnInternalIpv4AreRefused() {
        for (String u : new String[] { "http://[::127.0.0.1]/", "http://[::ffff:0:7f00:1]/",
            "http://[64:ff9b::7f00:1]/", "http://[64:ff9b::192.168.0.1]/", "http://[2002:c0a8:101::1]/",
            "http://[2002:7f00:1::]/", "http://[64:ff9b:1::1]/", "http://[2001:0:5ef5:79fd:0:59d:a0e5:ba1]/" }) {
            assertEquals(ReasonCode.URL_INTERNAL, reason(u), u);
        }
        // The same translation prefixes with a public IPv4 inside stay usable.
        assertEquals(ReasonCode.OK, reason("http://[64:ff9b::808:808]/"));
        assertEquals(ReasonCode.OK, reason("http://[2002:808:808::1]/"));
        assertEquals(ReasonCode.OK, reason("http://[2606:4700:4700::1111]/"));
    }

    /**
     * W3-0: one rule set on both sides. Every vector in {@code shared/address-vectors.json}, which host/test/
     * policy.test.js asserts too, as an address ({@link UrlPolicy#isInternal}) and as the host of a URL
     * ({@link UrlPolicy#check}).
     */
    @Test
    void sharedAddressVectors() throws Exception {
        File file = sharedFile("address-vectors.json");
        assertNotNull(file, "shared/address-vectors.json not found above " + System.getProperty("user.dir"));
        Object parsed = JsonParser.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        assertTrue(parsed instanceof List, "a JSON array");
        List<?> vectors = (List<?>) parsed;
        assertTrue(vectors.size() >= 60, "at least 60 vectors: " + vectors.size());
        Set<String> seen = new HashSet<>();
        for (Object o : vectors) {
            Map<?, ?> v = (Map<?, ?>) o;
            String address = (String) v.get("address");
            boolean internal = (Boolean) v.get("internal");
            String what = address + " (" + v.get("note") + ")";
            assertTrue(seen.add(address), "duplicate " + address);
            boolean v6 = address.indexOf(':') >= 0;
            InetAddress literal = v6 ? UrlPolicy.parseLiteral("[" + address + "]")
                : InetAddress.getByAddress(UrlPolicy.parseIpv4(address));
            assertEquals(internal, UrlPolicy.isInternal(literal), "address " + what);
            String host = v6 ? "[" + address + "]" : address;
            assertEquals(
                internal ? ReasonCode.URL_INTERNAL : ReasonCode.OK,
                reason("http://" + host + "/"),
                "url " + what);
        }
    }

    /** {@code shared/<name>} of the repository: walks up from the working directory (the project dir under Gradle). */
    static File sharedFile(String name) {
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null; d = d.getParentFile()) {
            File f = new File(new File(d, "shared"), name);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** Review finding: '§' (Minecraft formatting) survived into stored URLs and from there into chat. */
    @Test
    void theSectionSignIsPercentEncoded() {
        assertEquals("https://a.com/x%C2%A7cy", TextSanitizer.cleanUrl("https://a.com/x§cy", 64));
        assertEquals("https://a.com/", TextSanitizer.cleanUrl("https://a.com/§", 17), "never past the cap");
        assertEquals(ReasonCode.URL_INVALID, reason("https://a§b.com/"), "not a host name");
    }

    @Test
    void isInternalCopiedRules() throws Exception {
        assertTrue(UrlPolicy.isInternal(InetAddress.getByAddress(new byte[] { 10, 1, 2, 3 })));
        assertTrue(UrlPolicy.isInternal(InetAddress.getByAddress(new byte[] { (byte) 198, 18, 0, 1 })));
        assertTrue(UrlPolicy.isInternal(InetAddress.getByAddress(new byte[] { (byte) 192, 0, 0, 5 })));
        assertFalse(UrlPolicy.isInternal(InetAddress.getByAddress(new byte[] { (byte) 142, (byte) 250, 1, 1 })));
        assertFalse(UrlPolicy.isInternal(InetAddress.getByAddress(new byte[] { 100, (byte) 128, 0, 1 })));
    }

    /** Same checks with high ports allowed. */
    private static final class UrlPolicyTestHelper {

        private final UrlPolicy p;

        UrlPolicyTestHelper(boolean highPorts) {
            p = new UrlPolicy(highPorts, 1024);
        }

        ReasonCode reason(String url) {
            try {
                p.check(p.normalize(url));
                return ReasonCode.OK;
            } catch (UrlPolicy.PolicyException e) {
                return e.reason;
            }
        }
    }
}
