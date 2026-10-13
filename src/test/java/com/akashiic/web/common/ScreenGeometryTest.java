package com.akashiic.web.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

class ScreenGeometryTest {

    private static final double M = ScreenGeometry.BOX_MARGIN;

    @Test
    void renderBoxCoversTheWholeMultiblock() {
        // North face, origin (10,64,0), 4 wide towards -X, 3 high.
        ScreenState s = TestScreens.state(BlockSide.NORTH, 10, 64, 0, 4, 3, 0);
        double[] b = ScreenGeometry.renderBox(10, 64, 0, Collections.singletonList(s));
        assertArrayEquals(new double[] { 7 - M, 64 - M, 0 - M, 11 + M, 67 + M, 1 + M }, b, 1e-9);
    }

    @Test
    void renderBoxIsTheUnionOfFaces() {
        ScreenState north = TestScreens.state(BlockSide.NORTH, 0, 64, 0, 2, 2, 0);
        ScreenState top = TestScreens.state(BlockSide.TOP, 0, 64, 0, 3, 2, 0); // right +X, up -Z
        double[] b = ScreenGeometry.renderBox(0, 64, 0, Arrays.asList(north, null, top));
        assertArrayEquals(new double[] { -1 - M, 64 - M, -1 - M, 3 + M, 66 + M, 1 + M }, b, 1e-9);
        double[] none = ScreenGeometry.renderBox(5, 5, 5, Collections.<ScreenState>emptyList());
        assertArrayEquals(new double[] { 5 - M, 5 - M, 5 - M, 6 + M, 6 + M, 6 + M }, none, 1e-9);
    }

    /**
     * Review finding: the renderer measures from the origin block, so a fixed 128 blocks culled a whole 64x64 screen
     * for a viewer near its far corner, about 90 blocks from the origin.
     */
    @Test
    void bigScreensRenderFromAsFarBeyondTheirFarCorner() {
        ScreenState big = TestScreens.state(BlockSide.NORTH, 0, 64, 0, 64, 64, 0);
        double d = ScreenGeometry.renderDistance(128, Arrays.asList(null, big));
        double farCorner = Math.sqrt(64 * 64 + 64 * 64);
        assertTrue(d >= 128 + farCorner, "a viewer 128 blocks beyond the far corner still sees it: " + d);
        ScreenState small = TestScreens.state(BlockSide.NORTH, 0, 64, 0, 2, 2, 0);
        assertEquals(
            128 + farCorner + 1,
            ScreenGeometry.renderDistance(128, Arrays.asList(small, big, null)),
            1e-9,
            "the largest face decides");
        assertEquals(128 + Math.sqrt(8) + 1, ScreenGeometry.renderDistance(128, Arrays.asList(small)), 1e-9);
        assertEquals(129, ScreenGeometry.renderDistance(128, Collections.<ScreenState>emptyList()), 1e-9);
    }

    @Test
    void defaultResolutionFollowsTheFaceAspect() {
        int[] wide = ScreenGeometry.defaultResolution(16, 9, 0, 1280, 720);
        assertEquals(1280, wide[0]);
        assertTrue(wide[1] > 700 && wide[1] <= 720, "16x9 blocks is about 16:9");
        assertArrayEquals(new int[] { 720, 720 }, ScreenGeometry.defaultResolution(2, 2, 0, 1280, 720));
        int[] tall = ScreenGeometry.defaultResolution(4, 2, 90, 1280, 720); // page is portrait when turned
        assertTrue(tall[0] < tall[1]);
        int[] thin = ScreenGeometry.defaultResolution(2, 64, 0, 1280, 720);
        assertEquals(ScreenGeometry.MIN_RESOLUTION, thin[0]);
    }

    @Test
    void resolutionLimits() {
        assertTrue(ScreenGeometry.resolutionAllowed(1280, 720, 1920, 1920, 1920L * 1080));
        assertFalse(ScreenGeometry.resolutionAllowed(1920, 1920, 1920, 1920, 1920L * 1080));
        assertFalse(ScreenGeometry.resolutionAllowed(63, 720, 1920, 1920, 1920L * 1080));
        assertFalse(ScreenGeometry.resolutionAllowed(2000, 100, 1920, 1920, 1920L * 1080));
    }

    @Test
    void floorScreensFacePageTopAwayFromThePlacer() {
        assertEquals(0, ScreenGeometry.initialRotation(BlockSide.TOP, 180)); // facing north
        assertEquals(90, ScreenGeometry.initialRotation(BlockSide.TOP, -90)); // facing east
        assertEquals(180, ScreenGeometry.initialRotation(BlockSide.TOP, 0)); // facing south
        assertEquals(270, ScreenGeometry.initialRotation(BlockSide.TOP, 90)); // facing west
        assertEquals(90, ScreenGeometry.initialRotation(BlockSide.TOP, 270 + 360 * 3)); // yaw is unbounded
    }

    @Test
    void ceilingScreensFacePageTopTowardsThePlacer() {
        assertEquals(180, ScreenGeometry.initialRotation(BlockSide.BOTTOM, 180));
        assertEquals(90, ScreenGeometry.initialRotation(BlockSide.BOTTOM, -90));
        assertEquals(0, ScreenGeometry.initialRotation(BlockSide.BOTTOM, 0));
        assertEquals(270, ScreenGeometry.initialRotation(BlockSide.BOTTOM, 90));
    }

    /**
     * Cross-check with the face basis: on a floor screen placed while facing a direction, the page's "up" (from
     * bottom to top of the page) must point the way the placer faces.
     */
    @Test
    void floorRotationAgreesWithHitMath() {
        float[] yaws = { 0, 90, 180, 270 };
        double[][] facing = { { 0, 1 }, { -1, 0 }, { 0, -1 }, { 1, 0 } }; // (x, z) per yaw
        for (int i = 0; i < 4; i++) {
            ScreenState s = TestScreens.state(BlockSide.TOP, 0, 64, 0, 4, 4, 0);
            s.rotation = ScreenGeometry.initialRotation(BlockSide.TOP, yaws[i]);
            double[] bottom = HitMath.worldPoint(s, 0.5, 1), top = HitMath.worldPoint(s, 0.5, 0);
            assertEquals(facing[i][0], Math.signum(top[0] - bottom[0]), 1e-9, "yaw " + yaws[i]);
            assertEquals(facing[i][1], Math.signum(top[2] - bottom[2]), 1e-9, "yaw " + yaws[i]);
        }
    }

    @Test
    void wallsStartUnrotated() {
        for (BlockSide s : new BlockSide[] { BlockSide.NORTH, BlockSide.SOUTH, BlockSide.EAST, BlockSide.WEST }) {
            assertEquals(0, ScreenGeometry.initialRotation(s, 123));
        }
    }
}
