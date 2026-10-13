package com.akashiic.web.client.screen;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * URL authority stays on the server (ADR-012): when the controller's own browser navigates by itself (a link, an SPA
 * route, a redirect), the controller reports the new URL with C2SSetUrl so the server can validate it, bump the epoch
 * and converge the followers. Followers never report; their own redirects are harmless and the next epoch fixes any
 * divergence. URLs are compared after normalisation so "https://Example.com:443/" and "https://example.com/" are the
 * same page. Pure; tested in UrlConvergenceTest.
 */
public final class UrlConvergence {

    /** One report per second at most, so a page rewriting its URL in a loop cannot flood the server. */
    public static final long MIN_REPORT_INTERVAL_MS = 1_000;
    /**
     * After we navigate on the server's order, the host may still report the previous page for a moment; reporting
     * that would revert the server. Wait this long (and for loading to finish) before trusting a different URL.
     */
    public static final long NAVIGATE_SETTLE_MS = 2_000;

    private UrlConvergence() {}

    /**
     * @param loading         the host's STATE says the page is loading (redirect chains finish before we report)
     * @param msSinceNavigate time since we last told the host to navigate for the server
     */
    public static boolean shouldReport(String serverUrl, String pageUrl, boolean isController, boolean loading,
        long msSinceNavigate, long nowMs, long lastReportMs, boolean reportedBefore) {
        if (!isController || loading || pageUrl == null || !isWeb(pageUrl)) return false;
        if (msSinceNavigate < NAVIGATE_SETTLE_MS) return false;
        if (sameDocument(serverUrl, pageUrl)) return false;
        return !reportedBefore || nowMs - lastReportMs >= MIN_REPORT_INTERVAL_MS;
    }

    public static boolean sameDocument(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    /** Lower-case scheme and host, default port removed, empty path as "/", empty fragment dropped. */
    public static String normalize(String url) {
        if (url == null) return "";
        String s = url.trim();
        try {
            URI u = new URI(s);
            String scheme = u.getScheme() == null ? null
                : u.getScheme()
                    .toLowerCase(Locale.ROOT);
            String host = u.getHost() == null ? null
                : u.getHost()
                    .toLowerCase(Locale.ROOT);
            if (scheme == null || host == null) return s;
            int port = u.getPort();
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) port = -1;
            String path = u.getRawPath() == null || u.getRawPath()
                .isEmpty() ? "/" : u.getRawPath();
            StringBuilder b = new StringBuilder(s.length());
            b.append(scheme)
                .append("://");
            if (u.getRawUserInfo() != null) b.append(u.getRawUserInfo())
                .append('@');
            b.append(host);
            if (port != -1) b.append(':')
                .append(port);
            b.append(path);
            if (u.getRawQuery() != null) b.append('?')
                .append(u.getRawQuery());
            if (u.getRawFragment() != null && !u.getRawFragment()
                .isEmpty()) b.append('#')
                    .append(u.getRawFragment());
            return b.toString();
        } catch (URISyntaxException e) {
            return s; // not a URI we understand: compare verbatim
        }
    }

    static boolean isWeb(String url) {
        String l = url.trim()
            .toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://");
    }
}
