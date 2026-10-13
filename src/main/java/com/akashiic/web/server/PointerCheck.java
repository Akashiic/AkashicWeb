package com.akashiic.web.server;

import com.akashiic.web.common.HitMath;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.ScreenState;

/**
 * Server-side plausibility of a pointer position (ADR-010: reach and ray-plane checks with the server's own
 * positions). The client sends page (u, v); the server maps it back to the world point on the face and requires:
 * <ol>
 * <li>the player's eye is in front of the face (no clicking through the back of a screen);</li>
 * <li>the point is within reach of the eye;</li>
 * <li>the point lies within {@code maxAngleDeg} of the player's look direction.</li>
 * </ol>
 * The angle test is deliberately a cone, not "the crosshair ray must hit (u, v)": the client GUI moves a free cursor
 * over the screen while the view is fixed, and the server's copy of the player's rotation lags by up to a tick
 * anyway. Pure.
 */
public final class PointerCheck {

    private PointerCheck() {}

    public static ReasonCode check(ScreenState s, double ex, double ey, double ez, double[] look, double u, double v,
        double reach, double maxAngleDeg) {
        if (!(u >= 0 && u <= 1 && v >= 0 && v <= 1)) return ReasonCode.BAD_PACKET;
        if (HitMath.heightAbove(s, ex, ey, ez) <= 0) return ReasonCode.OUT_OF_VIEW;
        double[] p = HitMath.worldPoint(s, u, v);
        double dx = p[0] - ex, dy = p[1] - ey, dz = p[2] - ez;
        if (dx * dx + dy * dy + dz * dz > reach * reach) return ReasonCode.TOO_FAR;
        if (HitMath.angleDeg(ex, ey, ez, look, p[0], p[1], p[2]) > maxAngleDeg) return ReasonCode.OUT_OF_VIEW;
        return ReasonCode.OK;
    }

    /** For actions without a position (URL, keys, rights): the player must be in front of and near the screen. */
    public static ReasonCode near(ScreenState s, double ex, double ey, double ez, double range, boolean needFront) {
        if (needFront && HitMath.heightAbove(s, ex, ey, ez) <= 0) return ReasonCode.OUT_OF_VIEW;
        return HitMath.distanceToScreen(s, ex, ey, ez) <= range ? ReasonCode.OK : ReasonCode.TOO_FAR;
    }
}
