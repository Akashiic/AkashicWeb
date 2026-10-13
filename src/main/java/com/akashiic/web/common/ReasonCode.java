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
    SCREEN_DISSOLVED,
    // integration of wave 1 (codes proposed in the M1, M2, M3 and M5 reports)
    /** No browser host is available on this client (not wired, or its factory failed). */
    HOST_UNAVAILABLE,
    /** The host executable could not be started (missing file, antivirus block). */
    HOST_LAUNCH_FAILED,
    /** The host stopped answering while keeping its connection open. */
    HOST_STALLED,
    /** The host sent something that breaks the protocol. */
    HOST_PROTOCOL_ERROR,
    /** The host refused a command-line switch that weakens its security. */
    HOST_FORBIDDEN_SWITCH,
    /** The host could not connect back to the game. */
    HOST_CONNECT_FAILED,
    /** The page's host name could not be resolved, so it could not be checked and was not loaded. */
    URL_DNS_FAILED,
    /** The page failed to load. */
    PAGE_LOAD_FAILED,
    /** The page's renderer process crashed. */
    PAGE_CRASHED,
    /** The page stopped responding. */
    PAGE_UNRESPONSIVE,
    /** Spatial audio capture failed; the screen falls back to silence or OS audio. */
    CAPTURE_FAILED,
    /** The page tried to download a file; downloads are never allowed. */
    DOWNLOAD_BLOCKED,
    /** The page asked for a permission (camera, location...); permissions are never granted. */
    PERMISSION_DENIED,
    /** The page tried to open a pop-up without a recent click or key press. */
    POPUP_BLOCKED,
    /** A frame could not be uploaded to the GPU. */
    UPLOAD_FAILED,
    /** The page size is larger than this GPU's maximum texture size. */
    TEXTURE_TOO_LARGE,
    /** The engine install folder is not writable. */
    INSTALL_DIR_NOT_WRITABLE,
    /** An old engine folder is still in use by another process. */
    ENGINE_IN_USE;

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
