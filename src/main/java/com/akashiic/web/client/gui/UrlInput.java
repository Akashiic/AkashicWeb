package com.akashiic.web.client.gui;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * What the player typed in the address bar to a URL to send in C2SSetUrl. Only http(s) with a dotted host passes;
 * "youtube.com/live" becomes "https://youtube.com/live". Anything else (javascript:, file:, data:, spaces, a bare word)
 * is refused here for immediate feedback. This is UX only: the server's site policy and internal-address block and the
 * host's policy decide (ADR-013). Pure; tested in UrlInputTest.
 */
public final class UrlInput {

    public static final int MAX_LENGTH = 2048;

    private UrlInput() {}

    /** Normalised URL, or null when it is not an acceptable web address. */
    public static String normalize(String typed) {
        if (typed == null) return null;
        String s = typed.trim();
        if (s.isEmpty() || s.length() > MAX_LENGTH) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) return null;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (hasScheme(s)) return null;
            s = "https://" + s;
        }
        try {
            URI u = new URI(s);
            String host = u.getHost();
            if (host == null || host.indexOf('.') <= 0 || host.endsWith(".")) return null;
            return s;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** "javascript:", "file:", "data:"… but not "example.com:8080/x" (a port is digits after the colon). */
    static boolean hasScheme(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0) return false;
        for (int i = 0; i < colon; i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.')) return false;
        }
        if (s.indexOf('.') >= 0 && s.indexOf('.') < colon) {
            // "host.tld:port" form: a dotted host before the colon and digits after it.
            int end = colon + 1;
            while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
            return end == colon + 1;
        }
        return true;
    }
}
