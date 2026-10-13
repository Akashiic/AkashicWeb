package com.akashiic.web.client.host;

import com.akashiic.web.common.ReasonCode;

/**
 * Why one host run ended, finer than {@link ReasonCode} so /web doctor can tell a timeout from a bad token. Each kind
 * maps to the user-facing reason code shown on placeholders (merged into {@link ReasonCode} at the wave-1
 * integration).
 */
public enum HostFailure {

    /**
     * ProcessBuilder.start failed (missing file, antivirus, permissions). When every exit in the crash-loop window is
     * a launch failure, the supervisor disables with this reason instead of HOST_CRASH_LOOP.
     */
    LAUNCH_FAILED(ReasonCode.HOST_LAUNCH_FAILED, false),
    /** The process exited before connecting back, with an exit code that says no more (0, 70, a signal). */
    EXITED_BEFORE_CONNECT(ReasonCode.HOST_EXITED, false),
    /**
     * Exit 64: the host refused one of our {@code --akw-*} arguments or could not create its profile directory.
     * Relaunching the same command line cannot help, so this disables the host like a launch failure.
     */
    BAD_ARGUMENT(ReasonCode.HOST_LAUNCH_FAILED, true),
    /**
     * Exit 65: the host refused a forbidden switch or environment variable (a packaged host accepts only
     * {@code --akw-*}). Relaunching the same command line cannot help, so this disables the host.
     */
    FORBIDDEN_SWITCH(ReasonCode.HOST_FORBIDDEN_SWITCH, true),
    /** Exit 66: the host found no valid token on its stdin nor in AKW_TOKEN. */
    NO_TOKEN(ReasonCode.HOST_HANDSHAKE, false),
    /** Exit 67: the host could not connect back to our loopback port within its 5 s (a firewall, a security suite). */
    CONNECT_FAILED(ReasonCode.HOST_CONNECT_FAILED, false),
    /** Exit 68 after our WELCOME was written: the host refused it (protocol major mismatch or a malformed WELCOME). */
    WELCOME_REFUSED(ReasonCode.HOST_PROTOCOL_ERROR, false),
    /** Exit 68 before our WELCOME was written: the host gave up waiting for it (10 s). */
    WELCOME_TIMEOUT(ReasonCode.HOST_HANDSHAKE, false),
    /** Exit 69: the host read bytes from us that are not protocol v1. */
    BYTES_REFUSED(ReasonCode.HOST_PROTOCOL_ERROR, false),
    /** No connection within the connect timeout. */
    CONNECT_TIMEOUT(ReasonCode.HOST_HANDSHAKE, false),
    /** The first message was not a well-formed HELLO. */
    BAD_HELLO(ReasonCode.HOST_HANDSHAKE, false),
    /** HELLO carried the wrong token. */
    BAD_TOKEN(ReasonCode.HOST_HANDSHAKE, false),
    /** HELLO's protoMajor differs from ours: relaunching the same files cannot fix it, so this disables the host. */
    PROTOCOL_MAJOR(ReasonCode.PROTOCOL_MISMATCH, true),
    /** The socket reached EOF or reset: the host exited or crashed. */
    CONNECTION_LOST(ReasonCode.HOST_EXITED, false),
    /** The host broke the framing or sent an impossible message after the handshake. */
    PROTOCOL_ERROR(ReasonCode.HOST_PROTOCOL_ERROR, false),
    /** The host stopped reading and our outgoing queue filled up. */
    WRITE_STALLED(ReasonCode.HOST_STALLED, false),
    /**
     * The host kept its connection open but left a PING unanswered for {@link HostLaunchConfig#stallIntervals} ping
     * intervals (a hung main thread): frames are frozen, so the run ends and the host is replaced.
     */
    STALLED(ReasonCode.HOST_STALLED, false),
    /**
     * Our own reader, writer or supervisor code failed with an unexpected exception or Error (for example a
     * direct-memory OutOfMemoryError while allocating a frame buffer). The run ends visibly and the host is relaunched
     * instead of leaving a dead thread behind a READY status.
     */
    INTERNAL_ERROR(ReasonCode.HOST_EXITED, false),
    /** Five exits within ten minutes (ADR-001); stays disabled until re-enabled explicitly. */
    CRASH_LOOP(ReasonCode.HOST_CRASH_LOOP, true),
    /** The engine is not installed or not usable; the installer's reason is reported instead. */
    ENGINE_UNAVAILABLE(ReasonCode.ENGINE_ABSENT, true),
    /** We asked it to stop (game exit). Not a failure. */
    SHUTDOWN(ReasonCode.OK, true);

    // Host exit codes: host/src/args.js EXIT and docs/PROTOCOL.md section 1. Keep them in step with the host.
    static final int EXIT_OK = 0;
    static final int EXIT_BAD_ARGS = 64;
    static final int EXIT_FORBIDDEN_SWITCH = 65;
    static final int EXIT_NO_TOKEN = 66;
    static final int EXIT_CONNECT_FAILED = 67;
    static final int EXIT_HANDSHAKE = 68;
    static final int EXIT_PROTOCOL = 69;
    static final int EXIT_INTERNAL = 70;

    /**
     * The failure a host exit code stands for, or null when the code says no more than "the host ended" (0, 70, a
     * signal, an unknown code), so the caller keeps the failure it saw itself.
     *
     * @param welcomeSent whether our WELCOME was written before the host ended: exit 68 then means the host refused
     *                    it, otherwise that it gave up waiting for it
     */
    static HostFailure forExitCode(int code, boolean welcomeSent) {
        switch (code) {
            case EXIT_BAD_ARGS:
                return BAD_ARGUMENT;
            case EXIT_FORBIDDEN_SWITCH:
                return FORBIDDEN_SWITCH;
            case EXIT_NO_TOKEN:
                return NO_TOKEN;
            case EXIT_CONNECT_FAILED:
                return CONNECT_FAILED;
            case EXIT_HANDSHAKE:
                return welcomeSent ? WELCOME_REFUSED : WELCOME_TIMEOUT;
            case EXIT_PROTOCOL:
                return BYTES_REFUSED;
            default:
                return null;
        }
    }

    /** What a host exit code means, for /web doctor and the log; null for a code the host does not define. */
    static String describeExit(int code) {
        switch (code) {
            case EXIT_OK:
                return "normal exit";
            case EXIT_BAD_ARGS:
                return "bad --akw-* argument or profile directory";
            case EXIT_FORBIDDEN_SWITCH:
                return "forbidden switch or environment variable";
            case EXIT_NO_TOKEN:
                return "no token on stdin or in AKW_TOKEN";
            case EXIT_CONNECT_FAILED:
                return "could not connect back within 5 s";
            case EXIT_HANDSHAKE:
                return "no WELCOME in time, protocol major mismatch or malformed WELCOME";
            case EXIT_PROTOCOL:
                return "received bytes that are not protocol v1";
            case EXIT_INTERNAL:
                return "uncaught exception or child process crash loop";
            default:
                return null;
        }
    }

    private final ReasonCode reason;
    private final boolean disables;

    HostFailure(ReasonCode reason, boolean disables) {
        this.reason = reason;
        this.disables = disables;
    }

    public ReasonCode reason() {
        return reason;
    }

    /** True when relaunching cannot help, so the supervisor goes DISABLED instead of backing off. */
    public boolean disables() {
        return disables;
    }
}
