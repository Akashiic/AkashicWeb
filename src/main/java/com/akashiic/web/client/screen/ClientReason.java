package com.akashiic.web.client.screen;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.akashiic.web.common.ReasonCode;

/**
 * Maps host EVENTs (docs/PROTOCOL.md section 1, plus the two EVENTs M2 raises locally) to what the game shows: a
 * failure on the screen itself (every viewer with a browser sees it on the picture) and/or a chat notice for the
 * player who drives the page. Pure; tested in ClientReasonTest.
 * <ul>
 * <li>{@code blocked-url}: the code M2 put in {@code detail.code} (its own URL check), else the host's reason. A
 * refusal of the screen's own page (the URL the server asked for, an internal address reached, a policy change) fails
 * the screen; a link, redirect or sub-request the page tried is only a notice; a pop-up is POPUP_BLOCKED, notice
 * only.</li>
 * <li>{@code load-error}: {@code detail.code} TEXTURE_TOO_LARGE for a page too large to hold, URL_DNS_FAILED for an
 * unresolvable name, else PAGE_LOAD_FAILED with the Chromium error name.</li>
 * <li>{@code screen-limit}: BROWSER_BUDGET for the host's screen count, TEXTURE_TOO_LARGE for a page size.</li>
 * <li>{@code renderer-gone} PAGE_CRASHED, {@code unresponsive} PAGE_UNRESPONSIVE (cleared by {@code responsive}),
 * {@code capture-error} CAPTURE_FAILED, {@code download-cancelled} and {@code permission-denied} notices.</li>
 * </ul>
 * Host-level EVENTs (screen 0, e.g. {@code policy-error}) never reach this mapper: ClientScreens logs them, lists
 * them in /awdoctor and, for {@code policy-error}, fails every screen that has a browser with POLICY_INVALID.
 */
public final class ClientReason {

    /** What one EVENT changes. Immutable. */
    public static final class Outcome {

        static final Outcome NONE = new Outcome(null, "", null, "", false);

        /** Screen failure to show, or null. */
        public final ReasonCode failure;
        public final String failureDetail;
        /** Chat notice for the driving player, or null. */
        public final ReasonCode notice;
        public final String noticeArg;
        /** The page answers again: drop a PAGE_UNRESPONSIVE failure. */
        public final boolean clearsUnresponsive;

        Outcome(ReasonCode failure, String failureDetail, ReasonCode notice, String noticeArg,
            boolean clearsUnresponsive) {
            this.failure = failure;
            this.failureDetail = failureDetail == null ? "" : failureDetail;
            this.notice = notice;
            this.noticeArg = noticeArg == null ? "" : noticeArg;
            this.clearsUnresponsive = clearsUnresponsive;
        }

        static Outcome failure(ReasonCode r, String detail) {
            return new Outcome(r, detail, null, "", false);
        }

        static Outcome notice(ReasonCode r, String arg) {
            return new Outcome(null, "", r, arg, false);
        }

        static Outcome both(ReasonCode r, String detail) {
            return new Outcome(r, detail, r, detail, false);
        }

        @Override
        public String toString() {
            return "failure=" + failure + " notice=" + notice + (clearsUnresponsive ? " clearsUnresponsive" : "");
        }
    }

    /**
     * {@code via} values that mean the screen's own page was refused, so the picture is blank or stale: M2's check of
     * the server's URL ("client"), the host's check of CREATE/NAVIGATE ("navigate"), the internal-address detector
     * ("response", the host blanks the page), a policy change, and the main-frame request filter.
     */
    static final Set<String> PAGE_VIA = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("client", "navigate", "response", "policy-change", "request-mainFrame")));

    private ClientReason() {}

    /** Host EVENT kind to a screen failure code; null for kinds that are notices, not screen failures. */
    public static ReasonCode fromEventKind(String kind) {
        if (kind == null) return null;
        return map(new HostEvent(kind, "", "", "", "", "", false)).failure;
    }

    public static Outcome map(HostEvent e) {
        if (e == null || e.suppressedSummary) return Outcome.NONE;
        switch (e.kind) {
            case "blocked-url":
                return blockedUrl(e);
            case "load-error": {
                ReasonCode coded = ReasonCode.parse(e.code);
                if (coded != null && coded != ReasonCode.OK) return Outcome.failure(coded, e.text);
                if (e.text.equals("ERR_NAME_NOT_RESOLVED")) return Outcome.failure(ReasonCode.URL_DNS_FAILED, e.host);
                return Outcome.failure(ReasonCode.PAGE_LOAD_FAILED, e.text);
            }
            case "screen-limit":
                return "count".equals(e.reason) ? Outcome.failure(ReasonCode.BROWSER_BUDGET, "")
                    : Outcome.failure(ReasonCode.TEXTURE_TOO_LARGE, e.text);
            case "renderer-gone":
                return Outcome.failure(ReasonCode.PAGE_CRASHED, e.text);
            case "unresponsive":
                return Outcome.failure(ReasonCode.PAGE_UNRESPONSIVE, "");
            case "responsive":
                return new Outcome(null, "", null, "", true);
            case "capture-error":
                return Outcome.failure(ReasonCode.CAPTURE_FAILED, "");
            case "download-cancelled":
                return Outcome.notice(ReasonCode.DOWNLOAD_BLOCKED, "");
            case "permission-denied":
                return Outcome.notice(ReasonCode.PERMISSION_DENIED, e.text);
            default:
                return Outcome.NONE;
        }
    }

    private static Outcome blockedUrl(HostEvent e) {
        if ("popup".equals(e.reason)) return Outcome.notice(ReasonCode.POPUP_BLOCKED, e.host);
        ReasonCode code = ReasonCode.parse(e.code);
        if (code == null || code == ReasonCode.OK) code = blockedReason(e.reason);
        return PAGE_VIA.contains(e.via) ? Outcome.both(code, e.host) : Outcome.notice(code, e.host);
    }

    /** The host's blocked-url reasons (host/src/policy.js) to reason codes. */
    static ReasonCode blockedReason(String reason) {
        switch (reason) {
            case "malformed":
            case "too-long":
            case "scheme":
            case "credentials":
                return ReasonCode.URL_INVALID;
            case "internal":
            case "internal-dns":
            case "internal-ip":
                return ReasonCode.URL_INTERNAL;
            case "dns-failed":
                return ReasonCode.URL_DNS_FAILED;
            case "dns-via-proxy":
                // The host could not resolve the name itself (it sits behind a proxy), so it could not check it either.
                return ReasonCode.URL_DNS_VIA_PROXY;
            case "popup":
                return ReasonCode.POPUP_BLOCKED;
            case "not-allowed":
            case "blocked":
            default:
                return ReasonCode.URL_BLOCKED;
        }
    }
}
