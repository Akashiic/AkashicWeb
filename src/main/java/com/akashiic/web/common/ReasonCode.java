package com.akashiic.web.common;

/**
 * Every user-visible failure has a code; the text lives in the lang files under {@code akashicweb.reason.<lower>}.
 * Owned by M4; other modules add codes by listing them at integration. Never reorder: the wire form is the name.
 */
public enum ReasonCode {

    OK,
    // engine installer (M3)
    ENGINE_ABSENT,
    DOWNLOAD_FAILED,
    HASH_MISMATCH,
    DISK_FULL,
    TLS_FAILED,
    AV_BLOCKED,
    SAC_BLOCKED,
    UNSUPPORTED_OS,
    UNSUPPORTED_ARCH,
    // host process (M2)
    HOST_EXITED,
    HOST_CRASH_LOOP,
    HOST_HANDSHAKE,
    PROTOCOL_MISMATCH,
    // server decisions (M4)
    DISABLED_BY_SERVER,
    NO_PERMISSION,
    URL_BLOCKED,
    URL_INTERNAL,
    RATE_LIMITED,
    SCREEN_LOCKED,
    NOT_CONTROLLER,
    TOO_FAR,
    STALE_EPOCH,
    // client budget (M5)
    BROWSER_BUDGET;

    public String langKey() {
        return "akashicweb.reason." + name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Lenient parse for wire input: unknown names map to null so old clients ignore new codes. */
    public static ReasonCode parse(String name) {
        if (name == null) return null;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
