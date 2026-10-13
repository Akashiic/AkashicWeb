package com.akashiic.web.client.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.common.net.ScreenInput;

/**
 * JSON payloads of every game-to-host message (docs/PROTOCOL.md section 1). Pure functions, so the exact wire text is
 * unit-tested. Values are clamped here, at the last point before they leave the JVM, so a bad caller can never send
 * the host a NaN volume, a zero-sized page or a frame rate that would starve the relay.
 */
public final class HostMessages {

    /** Page size limits: a full BGRA frame must fit one FRAME payload (16 MiB), see {@link FrameCanvas#MAX_PIXELS}. */
    public static final int MIN_SIZE = 16;

    public static final int MAX_SIZE = 4096;
    public static final int MIN_FPS = 1;
    public static final int MAX_FPS = 60;
    /** Longest URL we hand to the host; the server caps lower, this only bounds a buggy caller. */
    public static final int MAX_URL = 8192;
    /** Longest CHAR text per INPUT; composed text from dead keys is a few characters. */
    public static final int MAX_CHAR_TEXT = 256;

    public static final String DEFAULT_LOCALE = "pt-BR";

    private HostMessages() {}

    public static String welcome(int protoMajor, SitePolicy policy, String locale, String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return JsonWriter.object("protoMajor", protoMajor, "policy", policyMap(policy), "locale", locale(locale));
        }
        return JsonWriter.object(
            "protoMajor",
            protoMajor,
            "policy",
            policyMap(policy),
            "locale",
            locale(locale),
            "userAgent",
            userAgent);
    }

    public static String create(CreateSpec spec) {
        int[] size = clampSize(spec.width, spec.height);
        return JsonWriter.object(
            "width",
            size[0],
            "height",
            size[1],
            "fps",
            clampFps(spec.fps),
            "url",
            spec.url,
            "session",
            spec.session == CreateSpec.Session.PRIVATE ? "private" : "shared",
            "audio",
            spec.audio == CreateSpec.Audio.SPATIAL ? "spatial" : "os",
            "volume",
            clampVolume(spec.volume));
    }

    public static String navigate(String url) {
        return JsonWriter.object("url", url);
    }

    public static String action(String action) {
        return JsonWriter.object("action", action);
    }

    /**
     * Cheap shape check before a URL leaves the JVM: http(s) or about:blank, bounded length, no whitespace or control
     * characters, no backslash and no user info in the authority. It is the first stage of the client-side URL check,
     * which then applies the server's own {@code UrlGate} (internal addresses, site policy).
     */
    public static boolean isNavigable(String url) {
        return "about:blank".equals(url) || urlShapeProblem(url) == null;
    }

    /**
     * Why {@code url} fails the http(s) shape check, as a short stable word for logs and events, or null when it
     * passes. Backslashes are refused anywhere: Chromium's WHATWG parser reads {@code \} as {@code /} in http(s) URLs
     * while java.net.URI rejects it, so the two parsers could disagree about where the host ends
     * ({@code http://allowed.example\@evil.example/}). Any {@code @} in the authority is refused for the same reason
     * (and because a page URL never needs credentials).
     */
    static String urlShapeProblem(String url) {
        if (url == null || url.isEmpty()) return "empty";
        if (url.length() > MAX_URL) return "too_long";
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= 0x20 || c == 0x7F) return "malformed";
            if (c == '\\') return "backslash";
        }
        String lower = url.substring(0, Math.min(8, url.length()))
            .toLowerCase(Locale.ROOT);
        int authority;
        if (lower.startsWith("https://")) authority = 8;
        else if (lower.startsWith("http://")) authority = 7;
        else return "scheme";
        int end = authority;
        while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) end++;
        if (end == authority) return "no_host";
        int at = url.indexOf('@', authority);
        if (at >= 0 && at < end) return "credentials";
        return null;
    }

    /** True for the NAVIGATE actions the host understands. */
    public static boolean isAction(String action) {
        return "back".equals(action) || "forward".equals(action) || "reload".equals(action) || "stop".equals(action);
    }

    public static String input(InputEvent e) {
        switch (e.type) {
            case MOUSE_MOVE:
                return JsonWriter.object("type", e.type.wire, "x", e.x, "y", e.y);
            case MOUSE_DOWN:
            case MOUSE_UP:
                return JsonWriter.object(
                    "type",
                    e.type.wire,
                    "x",
                    e.x,
                    "y",
                    e.y,
                    "button",
                    button(e.button),
                    "clickCount",
                    Math.max(1, Math.min(3, e.clickCount)));
            case WHEEL:
                return JsonWriter.object(
                    "type",
                    e.type.wire,
                    "x",
                    e.x,
                    "y",
                    e.y,
                    "deltaX",
                    finite(e.deltaX),
                    "deltaY",
                    finite(e.deltaY));
            case KEY_DOWN:
            case KEY_UP:
                return JsonWriter
                    .object("type", e.type.wire, "keyCode", e.keyCode, "modifiers", modifiers(e.modifiers));
            case CHAR:
            default:
                String text = e.text.length() > MAX_CHAR_TEXT ? e.text.substring(0, MAX_CHAR_TEXT) : e.text;
                return JsonWriter.object("type", InputEvent.Type.CHAR.wire, "text", text);
        }
    }

    public static String policy(SitePolicy policy) {
        return JsonWriter.write(policyMap(policy));
    }

    public static String audio(boolean muted, float volume) {
        return JsonWriter.object("muted", muted, "volume", clampVolume(volume));
    }

    public static String render(int fps, boolean painting) {
        return JsonWriter.object("fps", clampFps(fps), "painting", painting);
    }

    public static String resize(int width, int height) {
        int[] size = clampSize(width, height);
        return JsonWriter.object("width", size[0], "height", size[1]);
    }

    /** SYNC; returns null when the target time is not a finite, non-negative number (nothing sensible to send). */
    public static String sync(SyncCommand c) {
        if (Double.isNaN(c.targetMediaTime) || Double.isInfinite(c.targetMediaTime) || c.targetMediaTime < 0)
            return null;
        float rate = Float.isNaN(c.rate) || Float.isInfinite(c.rate) ? 1f : Math.max(0.5f, Math.min(2f, c.rate));
        return JsonWriter
            .object("targetMediaTime", c.targetMediaTime, "rate", rate, "paused", c.paused, "seek", c.seek);
    }

    public static String ping(long nonce) {
        return JsonWriter.object("nonce", nonce);
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Site policy as sent in WELCOME and POLICY. A null policy, or one whose mode is null or unknown (a malformed or
     * newer S2CHello), becomes an empty allowlist, which denies every site: until the server has told us a policy we
     * understand, the safe answer is "nothing". Never an allow-all blocklist.
     */
    static Object policyMap(SitePolicy policy) {
        String mode = knownMode(policy);
        if (mode == null) {
            return JsonWriter
                .map("mode", "allowlist", "allow", new ArrayList<String>(), "block", new ArrayList<String>());
        }
        return JsonWriter.map("mode", mode, "allow", hosts(policy.allow), "block", hosts(policy.block));
    }

    /** "allowlist" or "blocklist", or null when the policy or its mode is missing or unknown (fail closed). */
    private static String knownMode(SitePolicy policy) {
        if (policy == null || policy.mode == null) return null;
        switch (policy.mode) {
            case ALLOWLIST:
                return "allowlist";
            case BLOCKLIST:
                return "blocklist";
            default:
                return null; // a mode added after this build: we cannot know what it allows
        }
    }

    /**
     * The policy the client-side URL gate applies: entries normalised like the server's ({@link SitePolicy#of}), and
     * deny-all for a null policy or a null or unknown mode, matching {@link #policyMap}.
     */
    static SitePolicy gatePolicy(SitePolicy policy) {
        if (knownMode(policy) == null) {
            return new SitePolicy(SitePolicy.Mode.ALLOWLIST, new ArrayList<String>(), new ArrayList<String>());
        }
        return SitePolicy.of(policy.mode, policy.allow, policy.block);
    }

    /**
     * EVENT for a URL refused on this side before it left the JVM: the same {@code blocked-url} kind and detail shape
     * as the host's own refusals ({@code reason}, {@code host}, {@code via}), plus {@code code}, the
     * {@link com.akashiic.web.common.ReasonCode} name (URL_INVALID, URL_INTERNAL or URL_BLOCKED).
     */
    static String blockedUrlEvent(String code, String reason, String host) {
        return JsonWriter.object(
            "kind",
            "blocked-url",
            "detail",
            JsonWriter.map("reason", reason, "code", code, "host", host == null ? "" : host, "via", "client"));
    }

    /**
     * EVENT for a screen whose page is too large to hold (frames skipped). The {@code load-error} kind is what the
     * game already shows as a failed page; {@code code} names the precise reason.
     */
    static String frameTooLargeEvent(int width, int height) {
        return JsonWriter.object(
            "kind",
            "load-error",
            "detail",
            JsonWriter.map(
                "reason",
                "frame-too-large",
                "code",
                "TEXTURE_TOO_LARGE",
                "width",
                width,
                "height",
                height,
                "maxSide",
                FrameCanvas.MAX_DIMENSION,
                "maxPixels",
                FrameCanvas.MAX_PIXELS));
    }

    private static List<String> hosts(List<String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (String h : in) if (h != null && !h.isEmpty()) out.add(h.toLowerCase(Locale.ROOT));
        return out;
    }

    static int[] clampSize(int width, int height) {
        int w = Math.max(MIN_SIZE, Math.min(MAX_SIZE, width));
        int h = Math.max(MIN_SIZE, Math.min(MAX_SIZE, height));
        // Keep a full frame inside one FRAME payload by shrinking the taller side, preserving the width.
        if ((long) w * h > FrameCanvas.MAX_PIXELS) h = (int) Math.max(MIN_SIZE, FrameCanvas.MAX_PIXELS / w);
        return new int[] { w, h };
    }

    static int clampFps(int fps) {
        return Math.max(MIN_FPS, Math.min(MAX_FPS, fps));
    }

    static float clampVolume(float v) {
        if (Float.isNaN(v)) return 0f;
        return Math.max(0f, Math.min(1f, v));
    }

    private static float finite(float v) {
        return Float.isNaN(v) || Float.isInfinite(v) ? 0f : v;
    }

    private static String button(String b) {
        return "middle".equals(b) || "right".equals(b) ? b : "left";
    }

    private static String locale(String l) {
        return l == null || l.isEmpty() ? DEFAULT_LOCALE : l;
    }

    /** ScreenInput.MOD_* bitmask to Electron modifier names. */
    static List<String> modifiers(int mask) {
        List<String> out = new ArrayList<>(4);
        if ((mask & ScreenInput.MOD_SHIFT) != 0) out.add("shift");
        if ((mask & ScreenInput.MOD_CTRL) != 0) out.add("control");
        if ((mask & ScreenInput.MOD_ALT) != 0) out.add("alt");
        if ((mask & ScreenInput.MOD_META) != 0) out.add("meta");
        return out;
    }
}
