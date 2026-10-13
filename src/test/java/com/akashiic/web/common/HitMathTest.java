package com.akashiic.web.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.util.Vec3;

import org.junit.jupiter.api.Test;

class HitMathTest {

    private static final double EPS = 1e-9;
    private static final int[] ROTATIONS = { 0, 90, 180, 270 };

    @Test
    void worldPointAndUvAreInverseOnEveryFaceAndRotation() {
        for (BlockSide side : BlockSide.values()) {
            for (int rot : ROTATIONS) {
                ScreenState s = TestScreens.state(side, 10, 64, -5, 4, 3, rot);
                for (double u = 0; u <= 1.0001; u += 0.125) {
                    for (double v = 0; v <= 1.0001; v += 0.125) {
                        double uu = Math.min(1, u), vv = Math.min(1, v);
                        double[] p = HitMath.worldPoint(s, uu, vv);
                        assertEquals(0, HitMath.heightAbove(s, p[0], p[1], p[2]), EPS, side + " on plane");
                        double[] back = HitMath.uvOnFace(s, p[0], p[1], p[2]);
                        assertNotNull(back, side + "/" + rot + " " + uu + "," + vv);
                        assertEquals(uu, back[0], 1e-9, side + "/" + rot + " u");
                        assertEquals(vv, back[1], 1e-9, side + "/" + rot + " v");
                    }
                }
            }
        }
    }

    @Test
    void pageTopLeftOnANorthWallIsTheViewersTopLeft() {
        // North face: the viewer looks south, so their left is +X. Origin (0,64,0) is the bottom-left block.
        ScreenState s = TestScreens.state(BlockSide.NORTH, 0, 64, 0, 4, 3, 0);
        double[] tl = HitMath.worldPoint(s, 0, 0);
        assertEquals(1 - HitMath.BORDER, tl[0], EPS); // east edge of the origin block, minus the frame
        assertEquals(64 + 3 - HitMath.BORDER, tl[1], EPS); // top of the screen, minus the frame
        assertEquals(0, tl[2], EPS); // north surface of the blocks
        double[] br = HitMath.worldPoint(s, 1, 1);
        assertEquals(1 - 4 + HitMath.BORDER, br[0], EPS);
        assertEquals(64 + HitMath.BORDER, br[1], EPS);
    }

    @Test
    void rotationIsClockwiseOnTheFace() {
        // Turned 90 degrees clockwise, the page's top-left corner sits at the face's top-right corner.
        assertArrayEquals(new double[] { 0, 0 }, HitMath.faceToPage(90, 1, 0), EPS);
        assertArrayEquals(new double[] { 0, 0 }, HitMath.faceToPage(180, 1, 1), EPS);
        assertArrayEquals(new double[] { 0, 0 }, HitMath.faceToPage(270, 0, 1), EPS);
        for (int rot : ROTATIONS) {
            for (double a = 0; a <= 1; a += 0.25) {
                for (double b = 0; b <= 1; b += 0.25) {
                    double[] page = HitMath.faceToPage(rot, a, b);
                    assertArrayEquals(new double[] { a, b }, HitMath.pageToFace(rot, page[0], page[1]), EPS);
                }
            }
        }
    }

    @Test
    void quarterTurnsNormalise() {
        assertEquals(1, HitMath.quarterTurns(450));
        assertEquals(3, HitMath.quarterTurns(-90));
        assertEquals(0, HitMath.quarterTurns(45)); // invalid angles count as 0
    }

    @Test
    void frameAndOutsideAreNotOnThePage() {
        ScreenState s = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 2, 2, 0);
        // South face plane is z = 1; frame is the outer 2/16.
        assertNull(HitMath.uvOnFace(s, 0.05, 64.5, 1));
        assertNull(HitMath.uvOnFace(s, 2.5, 64.5, 1));
        assertNotNull(HitMath.uvOnFace(s, 1, 65, 1));
        assertNotNull(HitMath.uvOnFace(s, Vec3.createVectorHelper(1, 65, 1)));
    }

    @Test
    void pixelsAreClamped() {
        ScreenState s = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 2, 2, 0);
        assertArrayEquals(new int[] { 0, 0 }, HitMath.pixel(s, 0, 0));
        assertArrayEquals(new int[] { 1279, 719 }, HitMath.pixel(s, 1, 1));
        assertArrayEquals(new int[] { 640, 360 }, HitMath.pixel(s, 0.5, 0.5));
        assertArrayEquals(new int[] { 0, 719 }, HitMath.pixel(s, -3, 7));
    }

    @Test
    void rayHitsFromTheFrontOnly() {
        for (BlockSide side : BlockSide.values()) {
            ScreenState s = TestScreens.state(side, 0, 64, 0, 4, 4, 0);
            double[] c = HitMath.worldPoint(s, 0.5, 0.5);
            double ex = c[0] + side.fwdX * 3, ey = c[1] + side.fwdY * 3, ez = c[2] + side.fwdZ * 3;
            double[] hit = HitMath.rayHit(s, ex, ey, ez, -side.fwdX, -side.fwdY, -side.fwdZ, 10);
            assertNotNull(hit, side.name());
            assertEquals(0.5, hit[0], 1e-9);
            assertEquals(0.5, hit[1], 1e-9);
            assertEquals(3, hit[2], 1e-9);
            // From behind, looking the same way through the blocks: no hit.
            double bx = c[0] - side.fwdX * 3, by = c[1] - side.fwdY * 3, bz = c[2] - side.fwdZ * 3;
            assertNull(HitMath.rayHit(s, bx, by, bz, side.fwdX, side.fwdY, side.fwdZ, 10), side + " behind");
            // Looking away, or too far.
            assertNull(HitMath.rayHit(s, ex, ey, ez, side.fwdX, side.fwdY, side.fwdZ, 10), side + " away");
            assertNull(HitMath.rayHit(s, ex, ey, ez, -side.fwdX, -side.fwdY, -side.fwdZ, 2.5), side + " far");
        }
    }

    @Test
    void lookVectorMatchesMinecraftConvention() {
        assertArrayEquals(new double[] { 0, 0, 1 }, HitMath.lookVector(0, 0), 1e-9); // yaw 0 = south
        assertArrayEquals(new double[] { -1, 0, 0 }, HitMath.lookVector(90, 0), 1e-9); // yaw 90 = west
        assertArrayEquals(new double[] { 0, -1, 0 }, HitMath.lookVector(0, 90), 1e-9); // pitch 90 = down
    }

    @Test
    void angleAndDistance() {
        assertEquals(0, HitMath.angleDeg(0, 0, 0, new double[] { 0, 0, 1 }, 0, 0, 5), 1e-9);
        assertEquals(90, HitMath.angleDeg(0, 0, 0, new double[] { 0, 0, 1 }, 5, 0, 0), 1e-9);
        ScreenState s = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 2, 2, 0);
        assertEquals(4, HitMath.distanceToScreen(s, 1, 65, 5), 1e-9);
        // Nearest point is the right edge (x = 2) of the plane z = 1: 3 to the side and 4 in front.
        assertEquals(5, HitMath.distanceToScreen(s, 5, 65, 5), 1e-9);
    }
}
