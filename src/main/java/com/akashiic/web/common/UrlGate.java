package com.akashiic.web.common;

import java.net.URI;

/**
 * The full URL decision for a screen, shared by server (authority) and client (pre-check before asking the host):
 * normalise, {@link UrlPolicy} syntax and internal-address rules, then {@link SitePolicy}. Immutable; rebuilt when
 * the config is reloaded.
 */
public final class UrlGate {

    /** Outcome: {@link #url} is the normalised URL to store when {@link #reason} is OK. */
    public static final class Result {

        public final ReasonCode reason;
        public final String url;
        /** Short stable detail for logs and notices ("scheme", "port", the refused host). */
        public final String detail;

        Result(ReasonCode reason, String url, String detail) {
            this.reason = reason;
            this.url = url;
            this.detail = detail;
        }

        public boolean ok() {
            return reason == ReasonCode.OK;
        }
    }

    private final UrlPolicy urls;
    private final SitePolicy sites;

    public UrlGate(UrlPolicy urls, SitePolicy sites) {
        this.urls = urls;
        this.sites = sites;
    }

    public SitePolicy sites() {
        return sites;
    }

    public Result check(String raw) {
        String url = urls.normalize(raw);
        URI uri;
        try {
            uri = urls.check(url);
        } catch (UrlPolicy.PolicyException e) {
            return new Result(e.reason, url, e.detail);
        }
        String host = UrlPolicy.bareHost(uri.getHost());
        if (!sites.allowsHost(host)) return new Result(ReasonCode.URL_BLOCKED, url, host);
        return new Result(ReasonCode.OK, url, host);
    }
}
