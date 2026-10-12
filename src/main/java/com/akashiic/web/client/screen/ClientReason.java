package com.akashiic.web.client.screen;

import java.util.Locale;

/**
 * Client-side failure codes that {@code common.ReasonCode} (owned by M4) does not have yet. They use the same lang-key
 * convention ({@code akashicweb.reason.<lower>}) so merging them into ReasonCode at integration is a rename only.
 * Listed in the M5 report.
 */
public enum ClientReason {

    /** No BrowserHost implementation was wired, or its factory threw. */
    HOST_UNAVAILABLE,
    /** A frame could not be uploaded (GL threw); the screen shows the placeholder. */
    UPLOAD_FAILED,
    /** The page size exceeds GL_MAX_TEXTURE_SIZE on this GPU. */
    TEXTURE_TOO_LARGE,
    /** The page's renderer process crashed (host EVENT renderer-gone). */
    PAGE_CRASHED,
    /** The page failed to load (host EVENT load-error). */
    PAGE_LOAD_FAILED,
    /** The page stopped responding (host EVENT unresponsive). */
    PAGE_UNRESPONSIVE;

    public String langKey() {
        return "akashicweb.reason." + name().toLowerCase(Locale.ROOT);
    }

    /** Host EVENT kind to a code; null for kinds that are not screen failures. */
    public static ClientReason fromEventKind(String kind) {
        if (kind == null) return null;
        switch (kind) {
            case "renderer-gone":
                return PAGE_CRASHED;
            case "load-error":
                return PAGE_LOAD_FAILED;
            case "unresponsive":
                return PAGE_UNRESPONSIVE;
            default:
                return null;
        }
    }
}
