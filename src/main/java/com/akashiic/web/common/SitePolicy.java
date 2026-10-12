package com.akashiic.web.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Server site policy sent to clients in S2CHello and to the host in WELCOME/POLICY. Immutable data; the matching
 * logic (subdomains, internal addresses) is added by M4 next to it and mirrored by host/src/policy.js. Contract type.
 * <p>
 * Matching rules (M4): an entry matches the host itself and every subdomain ("youtube.com" matches
 * "www.youtube.com", never "evilyoutube.com"). The block list always wins, in both modes. In ALLOWLIST mode a host must
 * match an allow entry; in BLOCKLIST mode everything not blocked passes. Internal addresses are refused separately by
 * {@link UrlPolicy}, whatever the lists say.
 */
public final class SitePolicy {

    public enum Mode {
        ALLOWLIST,
        BLOCKLIST
    }

    /** Wire and config cap per list. */
    public static final int MAX_ENTRIES = 256;
    /** Longest DNS name. */
    public static final int MAX_ENTRY_LENGTH = 253;

    public final Mode mode;
    /** Lower-case host names; an entry also matches its subdomains. */
    public final List<String> allow;

    public final List<String> block;

    public SitePolicy(Mode mode, List<String> allow, List<String> block) {
        this.mode = mode;
        this.allow = Collections.unmodifiableList(new ArrayList<>(allow));
        this.block = Collections.unmodifiableList(new ArrayList<>(block));
    }

    public static SitePolicy blocklist(List<String> block) {
        return new SitePolicy(Mode.BLOCKLIST, Collections.<String>emptyList(), block);
    }

    /** Builds a policy from untrusted text (config, wire): entries are normalised, invalid ones dropped, capped. */
    public static SitePolicy of(Mode mode, Collection<String> allow, Collection<String> block) {
        return new SitePolicy(mode == null ? Mode.ALLOWLIST : mode, normalizeAll(allow), normalizeAll(block));
    }

    /** True if a page on this host may be shown. */
    public boolean allowsHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String bare = UrlPolicy.bareHost(host);
        if (matchesAny(bare, block)) return false;
        return mode == Mode.BLOCKLIST || matchesAny(bare, allow);
    }

    static boolean matchesAny(String bareHost, List<String> entries) {
        for (String e : entries) if (matches(bareHost, e)) return true;
        return false;
    }

    /** Host equals the entry or is a subdomain of it (label boundary). Both already normalised. */
    static boolean matches(String bareHost, String entry) {
        if (entry.isEmpty()) return false;
        return bareHost.equals(entry) || (bareHost.length() > entry.length() && bareHost.endsWith(entry)
            && bareHost.charAt(bareHost.length() - entry.length() - 1) == '.');
    }

    /**
     * Turns what an admin may write ("https://www.YouTube.com/", "*.twitch.tv", ".googlevideo.com.") into a bare,
     * lower-case host name. Null when it is not a plausible host name (spaces, wildcards in the middle, too long).
     */
    public static String normalizeEntry(String raw) {
        if (raw == null) return null;
        String s = raw.trim()
            .toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.lastIndexOf(':');
        if (colon >= 0 && s.indexOf(']') < 0) s = s.substring(0, colon);
        if (s.startsWith("*.")) s = s.substring(2);
        while (s.startsWith(".")) s = s.substring(1);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty() || s.length() > MAX_ENTRY_LENGTH) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.')) return null;
        }
        if (s.contains("..")) return null;
        return s;
    }

    static List<String> normalizeAll(Collection<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String r : raw) {
                String n = normalizeEntry(r);
                if (n != null) out.add(n);
                if (out.size() >= MAX_ENTRIES) break;
            }
        }
        return new ArrayList<>(out);
    }

    /** Lenient parse of a mode name; null when unknown. */
    public static Mode parseMode(String s) {
        if (s == null) return null;
        try {
            return Mode.valueOf(
                s.trim()
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SitePolicy)) return false;
        SitePolicy p = (SitePolicy) o;
        return mode == p.mode && allow.equals(p.allow) && block.equals(p.block);
    }

    @Override
    public int hashCode() {
        return (mode.hashCode() * 31 + allow.hashCode()) * 31 + block.hashCode();
    }

    @Override
    public String toString() {
        return mode + " allow=" + allow.size() + " block=" + block.size();
    }
}
