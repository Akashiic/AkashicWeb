package com.akashiic.web.client.screen;

import com.akashiic.web.common.ReasonCode;

/**
 * Maps host EVENT kinds (docs/PROTOCOL.md) to the reason code a screen shows. The client-only codes this class used to
 * define now live in {@link ReasonCode} (merged at integration of wave 1).
 */
public final class ClientReason {

    private ClientReason() {}

    /** Host EVENT kind to a screen failure code; null for kinds that are notices, not screen failures. */
    public static ReasonCode fromEventKind(String kind) {
        if (kind == null) return null;
        switch (kind) {
            case "renderer-gone":
                return ReasonCode.PAGE_CRASHED;
            case "load-error":
                return ReasonCode.PAGE_LOAD_FAILED;
            case "unresponsive":
                return ReasonCode.PAGE_UNRESPONSIVE;
            case "capture-error":
                return ReasonCode.CAPTURE_FAILED;
            default:
                return null;
        }
    }
}
