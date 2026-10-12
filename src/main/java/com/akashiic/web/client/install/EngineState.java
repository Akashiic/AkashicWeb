package com.akashiic.web.client.install;

import com.akashiic.web.common.ReasonCode;

/** Installer state shown on placeholders. Immutable. Contract type. */
public final class EngineState {

    public enum Kind {
        ABSENT,
        CONSENT_NEEDED,
        DOWNLOADING,
        READY,
        FAILED,
        UNSUPPORTED
    }

    public final Kind kind;
    /** 0..1 while DOWNLOADING. */
    public final float progress;

    public final ReasonCode reason;

    public EngineState(Kind kind, float progress, ReasonCode reason) {
        this.kind = kind;
        this.progress = progress;
        this.reason = reason == null ? ReasonCode.OK : reason;
    }

    @Override
    public String toString() {
        return kind + (kind == Kind.DOWNLOADING ? " " + Math.round(progress * 100) + "%" : "")
            + (reason == ReasonCode.OK ? "" : " " + reason);
    }
}
