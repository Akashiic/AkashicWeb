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
    /** The process exited before connecting back. */
    EXITED_BEFORE_CONNECT(ReasonCode.HOST_EXITED, false),
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
