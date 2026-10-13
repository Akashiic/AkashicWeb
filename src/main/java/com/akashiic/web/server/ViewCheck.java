package com.akashiic.web.server;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.HitMath;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.ScreenState;

/**
 * Where a player stands and looks relative to a screen, with the server's own positions (ADR-010). Used for URL,
 * navigation and rights changes (distance only) and, since D-016, for the controller lock: whoever takes control must
 * be near, in front of the screen, looking at it and with nothing in between. Pure; line of sight is injected
 * ({@link Sight}, a ray trace in {@link ScreenWorld}) so the rules are tested without a world.
 */
public final class ViewCheck {

    /**
     * The sight ray ends this far in front of the face, so it can never touch the screen's own blocks (they lie behind
     * the face plane) while any block standing between the player and the face still blocks it.
     */
    static final double FACE_CLEARANCE = 0.05;

    /** Line of sight between two points, ignoring blocks without a collision box (torches, grass). */
    public interface Sight {

        boolean clear(double fromX, double fromY, double fromZ, double toX, double toY, double toZ);
    }

    private ViewCheck() {}

    /** For actions without a position (URL, navigation, rights): the player must be near the screen. */
    public static ReasonCode near(ScreenState s, double ex, double ey, double ez, double range) {
        return HitMath.distanceToScreen(s, ex, ey, ez) <= range ? ReasonCode.OK : ReasonCode.TOO_FAR;
    }

    /**
     * Taking (or keeping) the controller lock (D-016): the eye is in front of the face, within {@code range} of the
     * screen, the look direction points at the screen (the aim point, clamped into the rectangle, is within
     * {@code maxAngleDeg} of the look) and nothing blocks the ray from the eye to that point. Refusals:
     * {@link ReasonCode#TOO_FAR}, or {@link ReasonCode#OUT_OF_VIEW} for behind, looking away and through a wall.
     */
    public static ReasonCode canSee(ScreenState s, double[] eye, double[] look, double range, double maxAngleDeg,
        Sight sight) {
        if (HitMath.heightAbove(s, eye[0], eye[1], eye[2]) <= 0) return ReasonCode.OUT_OF_VIEW;
        if (HitMath.distanceToScreen(s, eye[0], eye[1], eye[2]) > range) return ReasonCode.TOO_FAR;
        double[] aim = HitMath.aimPoint(s, eye[0], eye[1], eye[2], look[0], look[1], look[2]);
        if (aim == null) return ReasonCode.OUT_OF_VIEW;
        if (HitMath.angleDeg(eye[0], eye[1], eye[2], look, aim[0], aim[1], aim[2]) > maxAngleDeg) {
            return ReasonCode.OUT_OF_VIEW;
        }
        BlockSide side = BlockSide.fromSide(s.key.side);
        double tx = aim[0] + side.fwdX * FACE_CLEARANCE;
        double ty = aim[1] + side.fwdY * FACE_CLEARANCE;
        double tz = aim[2] + side.fwdZ * FACE_CLEARANCE;
        return sight.clear(eye[0], eye[1], eye[2], tx, ty, tz) ? ReasonCode.OK : ReasonCode.OUT_OF_VIEW;
    }
}
