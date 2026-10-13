package com.akashiic.web.common;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Syntactic URL rules for screens, applied by the server to every URL change and by the client before navigating:
 * http/https only, no credentials, default ports unless configured, and no internal address (loopback, private,
 * link-local, CGNAT, ULA, IPv4-mapped forms). No DNS is ever done here (safe on the main thread); names that resolve
 * to internal addresses are the host's job (ADR-013 amendment: resolveHost + Local Network Access denial).
 * <p>
 * Copied from AkashicFM (same owner) {@code com.akashiic.fm.audio.http.UrlPolicy} (isInternal, literal parsing,
 * internal names), adapted for a web browser:
 * <ul>
 * <li>IPv4 literals follow the WHATWG URL host parser that Chromium uses: hex ({@code 0x7f.1}), octal
 * ({@code 0177.0.0.1}) and short forms ({@code 127.1}) are internal too. AkashicFM parsed decimal only, which is
 * enough for its own HTTP client but would let {@code http://0177.0.0.1/} reach loopback in a browser;</li>
 * <li>a host whose last label is numeric must be a valid IPv4 address (WHATWG "ends in a number"), else the URL is
 * refused;</li>
 * <li>the domain allowlist moved to {@link SitePolicy}; DNS resolution (resolvePublic) was dropped.</li>
 * </ul>
 */
public final class UrlPolicy {

    /** Hard cap on the wire and in NBT; the configured cap may be lower. */
    public static final int MAX_URL_LENGTH = 2048;

    private final boolean allowHighPorts;
    private final int maxUrlLength;

    public UrlPolicy(boolean allowHighPorts, int maxUrlLength) {
        this.allowHighPorts = allowHighPorts;
        this.maxUrlLength = Math.min(MAX_URL_LENGTH, Math.max(16, maxUrlLength));
    }

    /**
     * Address-bar style normalisation before checking: trims, drops whitespace/control characters and adds
     * {@code https://} when no scheme was typed ("youtube.com/x" -> "https://youtube.com/x").
     */
    public String normalize(String raw) {
        String s = TextSanitizer.cleanUrl(raw, maxUrlLength + 16);
        if (s.isEmpty()) return s;
        if (!hasScheme(s)) s = "https://" + s;
        return s;
    }

    /** "scheme:" per RFC 3986 at the start (letters, digits, + - .), followed by "//" or not. */
    static boolean hasScheme(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0) return false;
        for (int i = 0; i < colon; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'));
            if (!ok) return false;
        }
        // "localhost:8080/x" has a "scheme" that is really a host with a port: a scheme is never followed by digits.
        int after = colon + 1;
        return !(after < s.length() && Character.isDigit(s.charAt(after)));
    }

    /**
     * Checks a normalised URL. Returns the parsed URI, or throws with a stable code: {@link ReasonCode#URL_INVALID}
     * for syntax, scheme, credentials, port and length problems, {@link ReasonCode#URL_INTERNAL} for internal hosts.
     */
    public URI check(String url) throws PolicyException {
        if (url == null || url.isEmpty()) throw new PolicyException(ReasonCode.URL_INVALID, "empty");
        if (url.length() > maxUrlLength) throw new PolicyException(ReasonCode.URL_INVALID, "too_long");
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            throw new PolicyException(ReasonCode.URL_INVALID, "malformed");
        }
        String scheme = uri.getScheme() == null ? ""
            : uri.getScheme()
                .toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https"))
            throw new PolicyException(ReasonCode.URL_INVALID, "scheme");
        if (uri.getRawUserInfo() != null) throw new PolicyException(ReasonCode.URL_INVALID, "credentials");
        String host = uri.getHost();
        // java.net.URI leaves the host null for anything that is not a plain ASCII host name or IP literal
        // (percent-escapes, unicode, underscores): Chromium could decode those into an internal address, so refuse.
        if (host == null || host.isEmpty()) throw new PolicyException(ReasonCode.URL_INVALID, "no_host");
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443 && !(allowHighPorts && port >= 1024 && port <= 65535)) {
            throw new PolicyException(ReasonCode.URL_INVALID, "port");
        }
        String bare = bareHost(host);
        if (isInternalName(bare)) throw new PolicyException(ReasonCode.URL_INTERNAL, bare);
        InetAddress literal = parseLiteral(bare);
        if (literal != null && isInternal(literal)) throw new PolicyException(ReasonCode.URL_INTERNAL, bare);
        return uri;
    }

    /** Lower-case host without the trailing dot ("localhost." is "localhost"). */
    public static String bareHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    static boolean isInternalName(String bare) {
        return bare.equals("localhost") || bare.endsWith(".localhost")
            || bare.endsWith(".local")
            || bare.endsWith(".internal")
            || bare.endsWith(".lan")
            || bare.endsWith(".localdomain")
            || bare.endsWith(".home.arpa");
    }

    /**
     * IP literal of a host without DNS: a bracketed IPv6 literal, or an IPv4 address in any form the WHATWG parser
     * accepts. Returns null for ordinary names. A host that ends in a number but is not a valid IPv4 address is
     * refused, as Chromium refuses it.
     */
    static InetAddress parseLiteral(String host) throws PolicyException {
        try {
            if (host.startsWith("[")) {
                if (!host.endsWith("]") || host.indexOf(':') < 0)
                    throw new PolicyException(ReasonCode.URL_INVALID, "malformed");
                // With brackets Java accepts only an IPv6 literal and never consults DNS.
                return InetAddress.getByName(host);
            }
            if (!endsInNumber(host)) return null;
            byte[] v4 = parseIpv4(host);
            if (v4 == null) throw new PolicyException(ReasonCode.URL_INVALID, "malformed");
            return InetAddress.getByAddress(v4);
        } catch (UnknownHostException e) {
            throw new PolicyException(ReasonCode.URL_INVALID, "malformed");
        }
    }

    /** WHATWG "ends in a number checker": the last label is all digits or a 0x hex number. */
    static boolean endsInNumber(String host) {
        String[] labels = host.split("\\.", -1);
        String last = labels[labels.length - 1];
        if (last.isEmpty() && labels.length > 1) last = labels[labels.length - 2];
        if (last.isEmpty()) return false;
        if (last.chars()
            .allMatch(c -> c >= '0' && c <= '9')) return true;
        return parseIpv4Number(last) >= 0;
    }

    /**
     * WHATWG IPv4 parser: 1 to 4 parts, each decimal, octal (leading 0) or hex (0x); the last part fills the remaining
     * bytes. Returns null when invalid.
     */
    static byte[] parseIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        int n = parts.length;
        if (n > 1 && parts[n - 1].isEmpty()) n--; // one trailing dot is allowed
        if (n < 1 || n > 4) return null;
        long[] v = new long[n];
        for (int i = 0; i < n; i++) {
            if (parts[i].isEmpty()) return null;
            v[i] = parseIpv4Number(parts[i]);
            if (v[i] < 0) return null;
        }
        for (int i = 0; i < n - 1; i++) if (v[i] > 255) return null;
        long last = v[n - 1];
        if (last >= (1L << (8 * (5 - n)))) return null;
        long value = last;
        for (int i = 0; i < n - 1; i++) value |= v[i] << (8 * (3 - i));
        return new byte[] { (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value };
    }

    /** One IPv4 part: "0x.." hex, "0.." octal, else decimal. -1 if not a number or above 2^32-1. */
    static long parseIpv4Number(String p) {
        int radix = 10;
        String digits = p;
        if (p.length() >= 2 && (p.startsWith("0x") || p.startsWith("0X"))) {
            radix = 16;
            digits = p.substring(2);
            if (digits.isEmpty()) return 0; // WHATWG: "0x" alone is zero
        } else if (p.length() >= 2 && p.charAt(0) == '0') {
            radix = 8;
            digits = p.substring(1);
        }
        if (digits.length() > 32) return -1;
        long value = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = Character.digit(digits.charAt(i), radix);
            if (d < 0) return -1;
            value = value * radix + d;
            if (value > 0xFFFFFFFFL) return -1;
        }
        return value;
    }

    /**
     * Copied from AkashicFM UrlPolicy.isInternal, plus IPv6 rules added here (review finding): Teredo, documentation,
     * discard and local-use NAT64 prefixes are refused whole, and the IPv6 forms that carry an IPv4 address
     * ({@link #embeddedIpv4}) are judged by that address. The IPv4 documentation ranges (TEST-NET-1/2/3) came from
     * host/src/policy.js. Both sides apply the same rule set (docs/PROTOCOL.md section 1, "Internal addresses"),
     * checked
     * against the shared vectors in {@code shared/address-vectors.json}.
     */
    public static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress()
            || a.isLinkLocalAddress()
            || a.isSiteLocalAddress()
            || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
            if (b0 == 0) return true; // 0.0.0.0/8
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true; // 100.64.0.0/10 (CGNAT)
            if (b0 == 192 && b1 == 0 && (b[2] & 0xFF) == 0) return true; // 192.0.0.0/24
            if (b0 == 192 && b1 == 0 && (b[2] & 0xFF) == 2) return true; // 192.0.2.0/24 TEST-NET-1
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true; // 198.18.0.0/15
            if (b0 == 198 && b1 == 51 && (b[2] & 0xFF) == 100) return true; // 198.51.100.0/24 TEST-NET-2
            if (b0 == 203 && b1 == 0 && (b[2] & 0xFF) == 113) return true; // 203.0.113.0/24 TEST-NET-3
            if (b0 >= 240) return true; // reserved + broadcast
        } else if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xFF;
            if ((b0 & 0xFE) == 0xFC) return true; // fc00::/7 (ULA)
            if (b0 == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0) return true; // 2001::/32 Teredo: refused whole
            if (b0 == 0x20 && b[1] == 0x01 && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8) return true; // docs
            if (b0 == 0x01 && b[1] == 0x00 && zero(b, 2, 8)) return true; // 100::/64 discard (as host/src/policy.js)
            if (b0 == 0x00 && b[1] == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B && b[4] == 0 && b[5] == 1)
                return true; // 64:ff9b:1::/48, local-use NAT64 (RFC 8215): a translator inside the site
            byte[] v4 = embeddedIpv4(b);
            if (v4 != null) {
                try {
                    return isInternal(InetAddress.getByAddress(v4));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The IPv4 address carried inside an IPv6 one, for the forms that route to it (review finding: these passed the
     * internal check): IPv4-mapped ::ffff:0:0/96 (Java usually hands those over as Inet4Address already),
     * IPv4-compatible ::/96, IPv4-translated ::ffff:0:0:0/96, NAT64 64:ff9b::/96 and 6to4 2002::/16. Null otherwise.
     * Added to the rules copied from AkashicFM.
     */
    static byte[] embeddedIpv4(byte[] b) {
        if (b.length != 16) return null;
        if (zero(b, 0, 10) && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) return tail(b); // ::ffff:a.b.c.d
        if (zero(b, 0, 12)) return tail(b); // ::a.b.c.d (:: and ::1 are caught before as any-local and loopback)
        if (zero(b, 0, 8) && (b[8] & 0xFF) == 0xFF && (b[9] & 0xFF) == 0xFF && zero(b, 10, 12)) return tail(b);
        if (b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B && zero(b, 4, 12)) {
            return tail(b); // 64:ff9b::a.b.c.d
        }
        if (b[0] == 0x20 && b[1] == 0x02) return new byte[] { b[2], b[3], b[4], b[5] }; // 2002:aabb:ccdd::/48
        return null;
    }

    private static boolean zero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) if (b[i] != 0) return false;
        return true;
    }

    private static byte[] tail(byte[] b) {
        return new byte[] { b[12], b[13], b[14], b[15] };
    }

    /** Policy refusal with a reason code and a short, stable detail ("scheme", "port", the host...). */
    public static final class PolicyException extends Exception {

        public final ReasonCode reason;
        public final String detail;

        public PolicyException(ReasonCode reason, String detail) {
            super(reason.name() + (detail == null || detail.isEmpty() ? "" : ": " + detail));
            this.reason = reason;
            this.detail = detail == null ? "" : detail;
        }
    }
}
