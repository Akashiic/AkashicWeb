package com.akashiic.web.client.host;

import java.util.Locale;

import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.common.UrlGate;
import com.akashiic.web.common.UrlPolicy;

/**
 * The client half of the URL rules (CLAUDE.md: keep the site policy and the internal-address block on both server and
 * client). Every URL is decided here before it leaves the JVM in CREATE or NAVIGATE, and again when a screen is
 * re-created after a host restart, with the same {@link UrlGate} the server uses. A client bug, a stale server state
 * or a forged packet therefore never hands the engine a URL the server's rules would refuse:
 * <ol>
 * <li>an empty URL and {@code about:blank} mean a blank page and pass;</li>
 * <li>{@link HostMessages#urlShapeProblem}: http(s) only, no control characters, no backslash, no user info;</li>
 * <li>{@code new UrlGate(new UrlPolicy(true, 2048), policy)}: syntax, credentials, ports, internal addresses
 * (URL_INTERNAL), then the site policy (URL_BLOCKED);</li>
 * <li>the URL must already be in normal form ({@link UrlPolicy#normalize} changes nothing), so what was checked is
 * exactly what is sent: no invisible or whitespace characters that one parser drops and another keeps.</li>
 * </ol>
 * The site policy is {@link HostMessages#gatePolicy}: deny-all until the server's policy is known, and for a null or
 * unknown mode. Immutable; {@link BrowserHostImpl} builds a new one whenever the policy changes. The host repeats the
 * checks (and resolves names) on its side: this layer adds to the host's, never replaces it.
 */
final class ClientUrlCheck {

    static final String BLANK = "about:blank";

    /** High ports allowed, 2048 characters: the server's most permissive settings, so any URL it can approve passes. */
    static final UrlPolicy URLS = new UrlPolicy(true, UrlPolicy.MAX_URL_LENGTH);

    /** Outcome for one URL. */
    static final class Decision {

        /** OK, or URL_INVALID, URL_INTERNAL, URL_BLOCKED. */
        final ReasonCode code;
        /** The URL to send when {@link #ok()}. */
        final String url;
        /** Short stable word in the host's vocabulary: internal, blocked, not-allowed, scheme, credentials ... */
        final String reason;
        /** The refused host when known, else "". */
        final String host;

        private Decision(ReasonCode code, String url, String reason, String host) {
            this.code = code;
            this.url = url;
            this.reason = reason == null ? "" : reason;
            this.host = host == null ? "" : host;
        }

        boolean ok() {
            return code == ReasonCode.OK;
        }

        /** The local blocked-url EVENT for a refusal. */
        String event() {
            return HostMessages.blockedUrlEvent(code.name(), reason, host);
        }
    }

    private final SitePolicy sites;
    private final UrlGate gate;
    private final boolean allowFileUrls;

    /**
     * @param allowFileUrls test seam only (package-private callers): lets {@code file:///} pages through so the
     *                      end-to-end test can drive a real host started with its own test-only switch. The public
     *                      BrowserHostImpl constructor always passes false.
     */
    ClientUrlCheck(SitePolicy policy, boolean allowFileUrls) {
        this.sites = HostMessages.gatePolicy(policy);
        this.gate = new UrlGate(URLS, sites);
        this.allowFileUrls = allowFileUrls;
    }

    Decision check(String raw) {
        if (raw == null || raw.isEmpty() || BLANK.equals(raw)) return new Decision(ReasonCode.OK, BLANK, "", "");
        if (allowFileUrls && isLocalFile(raw)) return new Decision(ReasonCode.OK, raw, "", "");
        String shape = HostMessages.urlShapeProblem(raw);
        if (shape != null) return refuse(ReasonCode.URL_INVALID, shape, "");
        UrlGate.Result r = gate.check(raw);
        if (r.reason == ReasonCode.URL_INTERNAL) return refuse(ReasonCode.URL_INTERNAL, "internal", r.detail);
        if (r.reason == ReasonCode.URL_BLOCKED) {
            String why = SitePolicy.blocklist(sites.block)
                .allowsHost(r.detail) ? "not-allowed" : "blocked";
            return refuse(ReasonCode.URL_BLOCKED, why, r.detail);
        }
        if (r.reason != ReasonCode.OK) return refuse(ReasonCode.URL_INVALID, r.detail, "");
        if (!raw.equals(r.url)) return refuse(ReasonCode.URL_INVALID, "malformed", "");
        return new Decision(ReasonCode.OK, raw, "", r.detail);
    }

    private static Decision refuse(ReasonCode code, String reason, String host) {
        return new Decision(code, null, reason, host);
    }

    private static boolean isLocalFile(String raw) {
        if (raw.length() <= 8 || !raw.substring(0, 8)
            .toLowerCase(Locale.ROOT)
            .equals("file:///")) return false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c <= 0x20 || c == 0x7F || c == '\\') return false;
        }
        return true;
    }
}
