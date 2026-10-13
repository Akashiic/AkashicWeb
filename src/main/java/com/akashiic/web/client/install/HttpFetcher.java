package com.akashiic.web.client.install;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/**
 * Opens one HTTP(S) request for the installer and follows redirects itself, from the canonical URL, on every call:
 * GitHub answers {@code github.com/.../releases/download/...} with a 302 to a short-lived signed URL on
 * release-assets.githubusercontent.com, so a stored redirect target would expire (ADR-011 amendment). Following by
 * hand also keeps the Range/If-Range headers on every hop and refuses HTTPS-to-HTTP downgrades. Every HTTPS hop uses
 * the
 * installer's own TLS context ({@link InstallerTls}), never the JVM default.
 */
final class HttpFetcher {

    static final int CONNECT_TIMEOUT_MS = 15_000;
    static final int READ_TIMEOUT_MS = 30_000;
    static final int MAX_REDIRECTS = 5;
    static final String PROJECT_URL = "https://github.com/Akashiic/AkashicWeb";

    /** A response that is not a redirect. The caller must {@link HttpURLConnection#disconnect()} it. */
    static final class Response {

        final HttpURLConnection conn;
        final int code;
        /** Host that answered (for logs: never the signed query string). */
        final String host;
        final int redirects;

        Response(HttpURLConnection conn, int code, String host, int redirects) {
            this.conn = conn;
            this.code = code;
            this.host = host;
            this.redirects = redirects;
        }
    }

    private final String userAgent;
    private final InstallerTls tls;
    /** Tests serve plain HTTP on loopback; production accepts HTTPS only. */
    private final boolean allowPlainHttp;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    /** Host of the hop opened last, so a failure can name it (the canonical host or the redirect target's). */
    private volatile String lastHost = "";

    HttpFetcher(String userAgent, InstallerTls tls, boolean allowPlainHttp, int connectTimeoutMs, int readTimeoutMs) {
        this.userAgent = userAgent;
        this.tls = tls;
        this.allowPlainHttp = allowPlainHttp;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    static HttpFetcher production(String modVersion, InstallerTls tls) {
        return new HttpFetcher(userAgent(modVersion), tls, false, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    static String userAgent(String modVersion) {
        String v = modVersion == null || modVersion.trim()
            .isEmpty() ? "dev" : modVersion.trim();
        return "AkashicWeb/" + v + " (+" + PROJECT_URL + ")";
    }

    /** Host of the hop opened last (for messages; never a signed URL). */
    String lastHost() {
        return lastHost;
    }

    /**
     * @param rangeFrom first byte wanted, or 0 for the whole entity
     * @param ifRange   validator for If-Range, or null
     */
    Response open(String canonicalUrl, long rangeFrom, String ifRange) throws IOException {
        URL url = parse(canonicalUrl);
        for (int hop = 0;; hop++) {
            checkScheme(url);
            lastHost = url.getHost();
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            if (c instanceof HttpsURLConnection) {
                ((HttpsURLConnection) c).setSSLSocketFactory(tls.socketFactory());
            }
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setConnectTimeout(connectTimeoutMs);
            c.setReadTimeout(readTimeoutMs);
            c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Accept", "application/octet-stream");
            // Byte offsets must refer to the stored entity, not to a compressed transfer of it.
            c.setRequestProperty("Accept-Encoding", "identity");
            if (rangeFrom > 0) {
                c.setRequestProperty("Range", "bytes=" + rangeFrom + "-");
                if (ifRange != null) c.setRequestProperty("If-Range", ifRange);
            }
            int code;
            try {
                code = c.getResponseCode();
            } catch (IOException e) {
                c.disconnect();
                throw e;
            }
            if (!isRedirect(code)) return new Response(c, code, url.getHost(), hop);
            String location = c.getHeaderField("Location");
            c.disconnect();
            if (location == null || location.isEmpty()) {
                throw new InstallException(
                    InstallFailure.DOWNLOAD_FAILED,
                    "HTTP " + code + " without Location from " + url.getHost(),
                    true,
                    null);
            }
            if (hop + 1 > MAX_REDIRECTS) {
                throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "too many redirects", true, null);
            }
            URL next;
            try {
                next = new URL(url, location);
            } catch (MalformedURLException e) {
                throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "bad redirect from " + url.getHost(), e);
            }
            if ("https".equals(url.getProtocol()) && !"https".equals(next.getProtocol())) {
                throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "redirect to plain HTTP refused");
            }
            url = next;
        }
    }

    static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private URL parse(String s) throws InstallException {
        try {
            return new URL(s);
        } catch (MalformedURLException e) {
            throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "bad URL", e);
        }
    }

    private void checkScheme(URL url) throws InstallException {
        String p = url.getProtocol()
            .toLowerCase(Locale.ROOT);
        if ("https".equals(p) || ("http".equals(p) && allowPlainHttp)) return;
        throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "refusing " + p + " URL for " + url.getHost());
    }
}
