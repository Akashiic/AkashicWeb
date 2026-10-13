package com.akashiic.web.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.HitMath;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.TestScreens;

/**
 * Who may take the controller lock (D-016, review finding "C2SControl has no line-of-sight or front-of-screen check"),
 * on every face: in front, near, looking at the screen and with a clear line of sight.
 */
class ViewCheckTest {

    private static final ViewCheck.Sight CLEAR = (ax, ay, az, bx, by, bz) -> true;
    private static final ViewCheck.Sight WALL = (ax, ay, az, bx, by, bz) -> false;

    @Test
    void lookingAtTheScreenFromTheFrontOnEveryFace() {
        for (BlockSide side : BlockSide.values()) {
            ScreenState s = TestScreens.state(side, 0, 64, 0, 4, 3, 90);
            double[] c = HitMath.worldPoint(s, 0.5, 0.5);
            double[] eye = { c[0] + side.fwdX * 4, c[1] + side.fwdY * 4, c[2] + side.fwdZ * 4 };
            double[] look = { -side.fwdX, -side.fwdY, -side.fwdZ };
            double[] away = { side.fwdX, side.fwdY, side.fwdZ };
            assertEquals(ReasonCode.OK, ViewCheck.canSee(s, eye, look, 24, 45, CLEAR), side.name());
            assertEquals(ReasonCode.OUT_OF_VIEW, ViewCheck.canSee(s, eye, away, 24, 45, CLEAR), side + " turned away");
            assertEquals(ReasonCode.TOO_FAR, ViewCheck.canSee(s, eye, look, 3, 45, CLEAR), side + " out of reach");
            assertEquals(
                ReasonCode.OUT_OF_VIEW,
                ViewCheck.canSee(s, eye, look, 24, 45, WALL),
                side + " through a wall");
            // Behind the screen, looking at its back: the wall that carries the screen is in front of the player.
            double[] behind = { c[0] - side.fwdX * 4, c[1] - side.fwdY * 4, c[2] - side.fwdZ * 4 };
            assertEquals(ReasonCode.OUT_OF_VIEW, ViewCheck.canSee(s, behind, away, 24, 45, CLEAR), side + " behind");
        }
    }

    @Test
    void theSightRayEndsJustInFrontOfTheFaceWhereThePlayerAims() {
        ScreenState s = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 4, 3, 0);
        List<double[]> rays = new ArrayList<>();
        ViewCheck.Sight recorder = (ax, ay, az, bx, by, bz) -> {
            rays.add(new double[] { ax, ay, az, bx, by, bz });
            return true;
        };
        // SOUTH: right is +X and up is +Y, so the face covers x 0..4, y 64..67, on the plane z = 1.
        double[] eye = { 2, 65.5, 6 };
        assertEquals(ReasonCode.OK, ViewCheck.canSee(s, eye, new double[] { 0, 0, -1 }, 24, 45, recorder));
        double[] r = rays.get(0);
        assertEquals(2, r[3], 1e-9);
        assertEquals(65.5, r[4], 1e-9);
        assertEquals(1 + ViewCheck.FACE_CLEARANCE, r[5], 1e-9, "never inside the screen's own blocks");
    }

    @Test
    void lookingJustBesideTheScreenCountsLookingFarAwayDoesNot() {
        ScreenState s = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 4, 3, 0);
        double[] eye = { 2, 65.5, 6 };
        // Aim 1 block beside the right edge (x = 5) at the face plane: about 9 degrees off the edge point.
        double[] beside = normalize(3, 0, -5);
        assertEquals(ReasonCode.OK, ViewCheck.canSee(s, eye, beside, 24, 45, CLEAR));
        // Far to the side (the ray meets the plane at x = 27): about 57 degrees off the nearest edge point.
        double[] far = normalize(25, 0, -5);
        assertEquals(ReasonCode.OUT_OF_VIEW, ViewCheck.canSee(s, eye, far, 24, 45, CLEAR));
        // Parallel to the face never meets it.
        assertEquals(ReasonCode.OUT_OF_VIEW, ViewCheck.canSee(s, eye, new double[] { 1, 0, 0 }, 24, 45, CLEAR));
    }

    @Test
    void aimPointIsClampedIntoTheRectangle() {
        ScreenState s = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 4, 3, 0);
        double[] hit = HitMath.aimPoint(s, 2, 65.5, 6, 0, 0, -1);
        assertNotNull(hit);
        assertEquals(2, hit[0], 1e-9);
        assertEquals(65.5, hit[1], 1e-9);
        assertEquals(1, hit[2], 1e-9);
        double[] edge = HitMath.aimPoint(s, 2, 65.5, 6, 1, 0, -1); // meets the plane at x = 7
        assertEquals(4, edge[0], 1e-9, "clamped to the right edge");
        assertNull(HitMath.aimPoint(s, 2, 65.5, 6, 0, 0, 1), "looking away");
        assertNull(HitMath.aimPoint(s, 2, 65.5, -3, 0, 0, -1), "behind the face");
    }

    @Test
    void nearCheckForActionsWithoutPosition() {
        ScreenState s = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 4, 3, 0);
        assertEquals(ReasonCode.OK, ViewCheck.near(s, 2, 65, 10, 24));
        assertEquals(ReasonCode.TOO_FAR, ViewCheck.near(s, 2, 65, 40, 24));
        assertEquals(ReasonCode.OK, ViewCheck.near(s, 2, 65, -3, 24), "distance only: no facing rule here");
        assertTrue(HitMath.heightAbove(s, 2, 65, -3) < 0);
    }

    private static double[] normalize(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[] { x / l, y / l, z / l };
    }
}
