package com.akashiic.web.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.ReasonCode;

/** Host EVENT JSON (as M1's host and M2's two local EVENTs send it) to screen failures and notices. */
class ClientReasonTest {

    private static ClientReason.Outcome map(String json) {
        return ClientReason.map(HostMessages.event(json));
    }

    @Test
    void m2LocalBlockedUrlCarriesItsOwnCode() {
        // HostMessages.blockedUrlEvent in client.host, via "client": the screen's own URL was refused.
        for (String code : new String[] { "URL_INVALID", "URL_INTERNAL", "URL_BLOCKED" }) {
            ClientReason.Outcome o = map(
                "{\"kind\":\"blocked-url\",\"detail\":{\"reason\":\"whatever\",\"code\":\"" + code
                    + "\",\"host\":\"x.example\",\"via\":\"client\"}}");
            assertEquals(ReasonCode.valueOf(code), o.failure, code);
            assertEquals("x.example", o.failureDetail);
            assertEquals(ReasonCode.valueOf(code), o.notice);
            assertEquals("x.example", o.noticeArg);
        }
    }

    @Test
    void m2LocalFrameTooLargeIsTextureTooLargeWithTheSize() {
        ClientReason.Outcome o = map(
            "{\"kind\":\"load-error\",\"detail\":{\"reason\":\"frame-too-large\",\"code\":\"TEXTURE_TOO_LARGE\","
                + "\"width\":5000,\"height\":3000,\"maxSide\":4096,\"maxPixels\":4194301}}");
        assertEquals(ReasonCode.TEXTURE_TOO_LARGE, o.failure);
        assertEquals("5000x3000", o.failureDetail);
        assertNull(o.notice);
    }

    @Test
    void hostBlockedUrlReasonsMapToCodes() {
        assertEquals(ReasonCode.URL_DNS_FAILED, blocked("dns-failed", "navigate").failure);
        assertEquals(ReasonCode.URL_DNS_FAILED, blocked("dns-via-proxy", "navigate").failure);
        assertEquals(ReasonCode.URL_INTERNAL, blocked("internal-ip", "response").failure);
        assertEquals(ReasonCode.URL_INTERNAL, blocked("internal-dns", "navigate").failure);
        assertEquals(ReasonCode.URL_INVALID, blocked("scheme", "navigate").failure);
        assertEquals(ReasonCode.URL_BLOCKED, blocked("not-allowed", "policy-change").failure);
        // A link or sub-request the page tried: the page itself is fine, the driver gets a notice.
        ClientReason.Outcome link = blocked("not-allowed", "will-navigate");
        assertNull(link.failure);
        assertEquals(ReasonCode.URL_BLOCKED, link.notice);
        assertNull(blocked("internal", "request-image").failure);
        assertEquals(ReasonCode.URL_INTERNAL, blocked("internal", "request-image").notice);
    }

    @Test
    void aBlockedPopupIsANoticeOnly() {
        ClientReason.Outcome o = blocked("popup", "window-open");
        assertNull(o.failure);
        assertEquals(ReasonCode.POPUP_BLOCKED, o.notice);
        assertEquals("a.example", o.noticeArg);
    }

    private static ClientReason.Outcome blocked(String reason, String via) {
        return map(
            "{\"kind\":\"blocked-url\",\"detail\":{\"url\":\"https://a.example/x?token=secret\",\"host\":\"a.example\","
                + "\"reason\":\""
                + reason
                + "\",\"via\":\""
                + via
                + "\"}}");
    }

    @Test
    void screenLimitIsBudgetOrSize() {
        assertEquals(
            ReasonCode.BROWSER_BUDGET,
            map("{\"kind\":\"screen-limit\",\"detail\":{\"reason\":\"count\",\"limit\":16}}").failure);
        ClientReason.Outcome size = map(
            "{\"kind\":\"screen-limit\",\"detail\":{\"reason\":\"size\",\"width\":8000,\"height\":100,"
                + "\"maxWidth\":4096,\"maxHeight\":4096,\"maxPixels\":4194301}}");
        assertEquals(ReasonCode.TEXTURE_TOO_LARGE, size.failure);
        assertEquals("8000x100", size.failureDetail);
    }

    @Test
    void pageEvents() {
        assertEquals(ReasonCode.CAPTURE_FAILED, map("{\"kind\":\"capture-error\",\"detail\":{}}").failure);
        ClientReason.Outcome crash = map("{\"kind\":\"renderer-gone\",\"detail\":{\"reason\":\"oom\",\"exitCode\":9}}");
        assertEquals(ReasonCode.PAGE_CRASHED, crash.failure);
        assertEquals("oom", crash.failureDetail);
        assertEquals(ReasonCode.PAGE_UNRESPONSIVE, map("{\"kind\":\"unresponsive\",\"detail\":{}}").failure);
        assertTrue(map("{\"kind\":\"responsive\",\"detail\":{}}").clearsUnresponsive);
        ClientReason.Outcome dns = map(
            "{\"kind\":\"load-error\",\"detail\":{\"code\":-105,\"description\":\"ERR_NAME_NOT_RESOLVED\","
                + "\"url\":\"https://nowhere.example/a\"}}");
        assertEquals(ReasonCode.URL_DNS_FAILED, dns.failure);
        assertEquals("nowhere.example", dns.failureDetail, "the host only, never the path or query");
        ClientReason.Outcome refused = map(
            "{\"kind\":\"load-error\",\"detail\":{\"code\":-102,\"description\":\"ERR_CONNECTION_REFUSED\"}}");
        assertEquals(ReasonCode.PAGE_LOAD_FAILED, refused.failure);
        assertEquals("ERR_CONNECTION_REFUSED", refused.failureDetail);
        assertEquals(ReasonCode.DOWNLOAD_BLOCKED, map("{\"kind\":\"download-cancelled\",\"detail\":{}}").notice);
        ClientReason.Outcome perm = map("{\"kind\":\"permission-denied\",\"detail\":{\"permission\":\"media\"}}");
        assertNull(perm.failure);
        assertEquals(ReasonCode.PERMISSION_DENIED, perm.notice);
        assertEquals("media", perm.noticeArg);
    }

    @Test
    void summariesUnknownKindsAndGarbageChangeNothing() {
        ClientReason.Outcome s = map("{\"kind\":\"blocked-url\",\"detail\":{\"suppressed\":12}}");
        assertNull(s.failure);
        assertNull(s.notice);
        assertNull(map("{\"kind\":\"new-kind-from-a-newer-host\"}").failure);
        assertNull(HostMessages.event("not json"));
        assertNull(HostMessages.event("{\"detail\":{}}"));
    }

    @Test
    void detailTextIsCleanedAndCapped() {
        StringBuilder longHost = new StringBuilder();
        for (int i = 0; i < 300; i++) longHost.append('a');
        HostEvent e = HostMessages.event(
            "{\"kind\":\"blocked-url\",\"detail\":{\"host\":\"§kevil\\u0007" + longHost + "\",\"via\":\"client\"}}");
        assertFalse(e.host.contains("§"));
        assertFalse(e.host.contains("\u0007"));
        assertTrue(e.host.length() <= HostMessages.MAX_DETAIL);
    }

    @Test
    void noticeThrottleAllowsOnePerKeyPerInterval() {
        NoticeThrottle t = new NoticeThrottle(10_000);
        assertTrue(t.allow("a", 0));
        assertFalse(t.allow("a", 9_999));
        assertTrue(t.allow("b", 1));
        assertTrue(t.allow("a", 10_000));
        for (int i = 0; i < 500; i++) t.allow("k" + i, 100_000 + i * 100_000L);
        assertTrue(t.size() <= 65, "bounded: " + t.size());
    }
}
