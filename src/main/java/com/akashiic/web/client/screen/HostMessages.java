package com.akashiic.web.client.screen;

import com.akashiic.web.client.sync.LocalMedia;
import com.akashiic.web.common.TextSanitizer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Parses the host's JSON messages (STATE, MEDIA, EVENT; docs/PROTOCOL.md). The host is our own process but its input is
 * web content, so every field is optional and every malformed message is rejected (null) instead of throwing into the
 * reader thread. Uses the Gson 2.2.4 that ships with Minecraft 1.7.10. Pure; tested in HostMessagesTest.
 */
public final class HostMessages {

    /** Titles and URLs are shown in GUIs; nothing legitimate is longer. */
    static final int MAX_TEXT = 2048;

    private HostMessages() {}

    /** MEDIA, stamped by the caller with arrival time; null when malformed or without a position. */
    public static LocalMedia media(String json, long receivedMs, double serverStampMs) {
        JsonObject o = object(json);
        if (o == null) return null;
        double mediaTime = num(o, "mediaTime", Double.NaN);
        if (Double.isNaN(mediaTime) || Double.isInfinite(mediaTime) || mediaTime < 0) return null;
        double rate = num(o, "rate", 1);
        return new LocalMedia(
            mediaTime,
            bool(o, "paused", false),
            Double.isNaN(rate) ? 1f : (float) rate,
            num(o, "duration", Double.NaN),
            bool(o, "live", false),
            finiteOr(num(o, "behindLiveEdge", 0), 0),
            bool(o, "adShowing", false),
            bool(o, "fullscreen", false),
            receivedMs,
            serverStampMs);
    }

    /** STATE; null when malformed. */
    public static PageState state(String json) {
        JsonObject o = object(json);
        if (o == null) return null;
        return new PageState(
            text(o, "url"),
            text(o, "title"),
            bool(o, "loading", false),
            bool(o, "canGoBack", false),
            bool(o, "canGoForward", false),
            bool(o, "audible", false));
    }

    /** EVENT kind (e.g. "renderer-gone"); null when malformed. */
    public static String eventKind(String json) {
        HostEvent e = event(json);
        return e == null ? null : e.kind;
    }

    /**
     * EVENT {kind, detail}; null when malformed or without a kind. Detail strings are cleaned (no formatting codes or
     * control characters) and capped at {@link #MAX_DETAIL}, since they end up in placeholders and chat lines.
     */
    public static HostEvent event(String json) {
        JsonObject o = object(json);
        if (o == null) return null;
        String kind = clean(text(o, "kind"));
        if (kind.isEmpty()) return null;
        JsonElement de = o.get("detail");
        JsonObject d = de != null && de.isJsonObject() ? de.getAsJsonObject() : new JsonObject();
        String host = text(d, "host");
        if (host.isEmpty()) host = hostOf(text(d, "url"));
        String detailText = firstText(d, "description", "permission", "error");
        if (detailText.isEmpty() && "renderer-gone".equals(kind)) detailText = text(d, "reason");
        if (detailText.isEmpty()) {
            double w = num(d, "width", Double.NaN), h = num(d, "height", Double.NaN);
            if (!Double.isNaN(w) && !Double.isNaN(h)) detailText = (long) w + "x" + (long) h;
        }
        return new HostEvent(
            kind,
            clean(text(d, "reason")),
            clean(text(d, "code")),
            clean(host),
            clean(text(d, "via")),
            clean(detailText),
            d.has("suppressed"));
    }

    /** Detail strings shown to players are short: a host name, an error name, a size. */
    static final int MAX_DETAIL = 128;

    private static String clean(String s) {
        return TextSanitizer.clean(s, MAX_DETAIL);
    }

    private static String firstText(JsonObject o, String... names) {
        for (String n : names) {
            String s = text(o, n);
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    /** Host of a URL (never the path or query, which can carry tokens), or "". */
    static String hostOf(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            String h = new java.net.URI(url).getHost();
            return h == null ? "" : h;
        } catch (java.net.URISyntaxException e) {
            return "";
        }
    }

    private static JsonObject object(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JsonElement e = new JsonParser().parse(json);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    private static double num(JsonObject o, String name, double fallback) {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive()
            || !e.getAsJsonPrimitive()
                .isNumber())
            return fallback;
        return e.getAsDouble();
    }

    private static boolean bool(JsonObject o, String name, boolean fallback) {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive()
            || !e.getAsJsonPrimitive()
                .isBoolean())
            return fallback;
        return e.getAsBoolean();
    }

    private static String text(JsonObject o, String name) {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive()
            || !e.getAsJsonPrimitive()
                .isString())
            return "";
        String s = e.getAsString();
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    private static double finiteOr(double v, double fallback) {
        return Double.isNaN(v) || Double.isInfinite(v) ? fallback : v;
    }
}
