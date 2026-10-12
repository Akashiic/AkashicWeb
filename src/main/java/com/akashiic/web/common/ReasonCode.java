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
    BROWSER_BUDGET,
    // server decisions (M4, added with the server implementation)
    URL_INVALID,
    WRONG_DIMENSION,
    UNKNOWN_SCREEN,
    /** A client packet could not be decoded or broke a length cap. */
    BAD_PACKET,
    /** Another player holds the controller lock and the requester may not pre-empt it. */
    CONTROLLER_BUSY,
    /** The claimed pointer position is behind the screen or outside the player's view. */
    OUT_OF_VIEW,
    UNKNOWN_PLAYER,
    TOO_MANY_FRIENDS,
    BAD_RESOLUTION,
    /** The action does not exist for this screen's mode (e.g. a controller on a PRIVATE screen). */
    WRONG_MODE,
    FAKE_PLAYER,
    // screen multiblock (M4)
    SCREEN_TOO_SMALL,
    SCREEN_TOO_BIG,
    SCREEN_INVALID_SHAPE,
    /** The new screen would cover a screen the player may not manage. */
    SCREEN_OVERLAP,
    /** The player already owns the configured maximum number of screens. */
    SCREEN_LIMIT,
    /** Part of the screen is in an unloaded chunk. */
    CHUNK_NOT_LOADED,
    /** Blocks of an existing screen were removed, so the screen was dissolved. */
    SCREEN_DISSOLVED;

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
