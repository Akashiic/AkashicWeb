package com.akashiic.web.client.host;

import com.akashiic.web.common.ReasonCode;

/** Lifecycle of the host process. Immutable. Contract type. */
public final class HostStatus {

    public enum Kind {
        STARTING,
        READY,
        DOWN,
        DISABLED
    }

    public static final HostStatus STARTING = new HostStatus(Kind.STARTING, ReasonCode.OK);
    public static final HostStatus READY = new HostStatus(Kind.READY, ReasonCode.OK);

    public final Kind kind;
    public final ReasonCode reason;

    public HostStatus(Kind kind, ReasonCode reason) {
        this.kind = kind;
        this.reason = reason == null ? ReasonCode.OK : reason;
    }

    public static HostStatus down(ReasonCode reason) {
        return new HostStatus(Kind.DOWN, reason);
    }

    public static HostStatus disabled(ReasonCode reason) {
        return new HostStatus(Kind.DISABLED, reason);
    }

    @Override
    public String toString() {
        return reason == ReasonCode.OK ? kind.name() : kind + "(" + reason + ")";
    }
}
