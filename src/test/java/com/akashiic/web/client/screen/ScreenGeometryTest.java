package com.akashiic.web.client.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScreenGeometryTest {

    private static final double EPS = 1e-9;
    private static final double B = ScreenGeometry.FRAME_INSET;

    @Test
    void southFaceSpansTheMultiblock() {
        // Origin (0,0,0), 3 wide, 2 high, facing south (+z): the picture covers x 0..3, y 0..2 at z = 1, minus the
        // frame.
        ScreenGeometry g = ScreenGeometry.of(3, 3, 2, 0);
        double lift = ScreenGeometry.SURFACE_OFFSET;
        assertArrayEquals(new double[] { B, 2 - B, 1 + lift }, g.corner(0), EPS); // top-left
        assertArrayEquals(new double[] { 3 - B, 2 - B, 1 + lift }, g.corner(1), EPS);
        assertArrayEquals(new double[] { 3 - B, B, 1 + lift }, g.corner(2), EPS);
        assertArrayEquals(new double[] { B, B, 1 + lift }, g.corner(3), EPS);
        assertEquals(3 - 2 * B, g.quadWidth, EPS);
        assertEquals(2 - 2 * B, g.quadHeight, EPS);
    }

    @Test
    void everyFaceCoversItsBlocksAndIsSeenUnmirrored() {
        int w = 4, h = 3;
        for (int side = 0; side < 6; side++) {
            ScreenGeometry g = ScreenGeometry.of(side, w, h, 0);
            int[] n = ScreenGeometry.NORMAL[side];
            int[] r = ScreenGeometry.EXTENT_RIGHT[side];
            int[] u = ScreenGeometry.EXTENT_UP[side];
            // Every corner lies inside the bounding box of the multiblock's blocks, on the outer face plane.
            double minX = Math.min(0, Math.min(r[0] * (w - 1), 0) + Math.min(u[0] * (h - 1), 0));
            double minY = Math.min(0, Math.min(r[1] * (w - 1), 0) + Math.min(u[1] * (h - 1), 0));
            double minZ = Math.min(0, Math.min(r[2] * (w - 1), 0) + Math.min(u[2] * (h - 1), 0));
            double[] lo = { minX, minY, minZ };
            double[] hi = { lo[0] + Math.abs(r[0]) * w + Math.abs(u[0]) * h + Math.abs(n[0]),
                lo[1] + Math.abs(r[1]) * w + Math.abs(u[1]) * h + Math.abs(n[1]),
                lo[2] + Math.abs(r[2]) * w + Math.abs(u[2]) * h + Math.abs(n[2]) };
            for (int i = 0; i < 4; i++) {
                double[] c = g.corner(i);
                for (int a = 0; a < 3; a++) {
                    double slack = n[a] != 0 ? ScreenGeometry.SURFACE_OFFSET + EPS : EPS;
                    assertTrue(c[a] >= lo[a] - slack && c[a] <= hi[a] + slack, "side " + side + " corner " + i);
                }
            }
            // The basis is a proper rotation (no mirror) and its z axis points into the face.
            float[] m = g.basisMatrix();
            double[] x = { m[0], m[1], m[2] }, y = { m[4], m[5], m[6] }, z = { m[8], m[9], m[10] };
            assertEquals(1, ScreenGeometry.dot(ScreenGeometry.cross(x, y), z), EPS, "det of side " + side);
            assertArrayEquals(new double[] { -n[0], -n[1], -n[2] }, z, EPS);
            // The viewer, looking against the normal with the picture's up as up, has x on the right.
            double[] forward = { -n[0], -n[1], -n[2] };
            double[] up = { -y[0], -y[1], -y[2] };
            assertArrayEquals(x, ScreenGeometry.cross(forward, up), EPS, "mirrored on side " + side);
        }
    }

    @Test
    void textureCornersAndHitMappingAgreeForEveryRotation() {
        for (int side = 0; side < 6; side++) {
            for (int rot = 0; rot < 360; rot += 90) {
                ScreenGeometry g = ScreenGeometry.of(side, 5, 2, rot);
                for (int i = 0; i < 4; i++) {
                    double[] c = g.corner(i);
                    // Step a hair toward the centre so the point is strictly inside the picture.
                    double[] mid = g.center();
                    double[] p = { c[0] + (mid[0] - c[0]) * 1e-6, c[1] + (mid[1] - c[1]) * 1e-6,
                        c[2] + (mid[2] - c[2]) * 1e-6 };
                    double[] uv = g.pageUv(p[0], p[1], p[2]);
                    assertNotNull(uv, "side " + side + " rot " + rot + " corner " + i);
                    float[] t = g.texCoord(i);
                    assertEquals(t[0], uv[0], 1e-4, "u side " + side + " rot " + rot + " corner " + i);
                    assertEquals(t[1], uv[1], 1e-4, "v side " + side + " rot " + rot + " corner " + i);
                }
            }
        }
    }

    @Test
    void clockwiseRotationPutsThePageTopOnTheRight() {
        ScreenGeometry g = ScreenGeometry.of(3, 2, 2, 90);
        assertArrayEquals(new float[] { 0, 0 }, g.texCoord(1), 0f); // page top-left at the face's top-right
        assertArrayEquals(new float[] { 1, 0 }, g.texCoord(2), 0f); // page top-right at the face's bottom-right
        assertArrayEquals(new double[] { 0.25, 0.75 }, ScreenGeometry.faceToPage(0.25, 0.75, 0), EPS);
        assertArrayEquals(new double[] { 0.75, 0.75 }, ScreenGeometry.faceToPage(0.25, 0.75, 1), EPS);
        assertArrayEquals(new double[] { 0.75, 0.25 }, ScreenGeometry.faceToPage(0.25, 0.75, 2), EPS);
        assertArrayEquals(new double[] { 0.25, 0.25 }, ScreenGeometry.faceToPage(0.25, 0.75, 3), EPS);
        assertEquals(1, ScreenGeometry.quarterTurns(-270));
        assertEquals(0, ScreenGeometry.quarterTurns(360));
        assertEquals(2, ScreenGeometry.quarterTurns(170));
    }

    @Test
    void hitsOnTheFrameOrOffThePlaneMiss() {
        ScreenGeometry g = ScreenGeometry.of(3, 2, 2, 0);
        assertNotNull(g.faceUv(1.0, 1.0, 1.0));
        assertArrayEquals(new double[] { 0.5, 0.5 }, g.faceUv(1.0, 1.0, 1.0), EPS);
        assertNull(g.faceUv(0.05, 1.0, 1.0), "on the left frame");
        assertNull(g.faceUv(1.0, 1.95, 1.0), "on the top frame");
        assertNull(g.faceUv(1.0, 1.0, 0.0), "the back of the block");
        assertNull(g.faceUv(1.0, 1.0, 1.2), "in the air in front");
        assertNull(g.faceUv(2.5, 1.0, 1.0), "beside the screen");
    }

    @Test
    void bottomFaceReadsCorrectlyFromBelow() {
        // Web Displays extends a bottom screen toward -z; a viewer below with the picture's up as up sees +x on the
        // right and the page's top toward +z.
        ScreenGeometry g = ScreenGeometry.of(0, 2, 3, 0);
        double[] tl = g.corner(0);
        double[] br = g.corner(2);
        assertEquals(B, tl[0], EPS);
        assertEquals(1 - B, tl[2], EPS, "top edge at the +z end of the multiblock");
        assertEquals(2 - B, br[0], EPS);
        assertEquals(-2 + B, br[2], EPS);
        assertEquals(-ScreenGeometry.SURFACE_OFFSET, tl[1], EPS, "below the block's bottom face");
    }

    @Test
    void distanceToTheNearestPointOfThePicture() {
        ScreenGeometry g = ScreenGeometry.of(3, 4, 2, 0);
        assertEquals(5.0, g.distanceTo(2.0, 1.0, 6.0), EPS); // straight in front of the centre
        double dx = -10 - B, dz = 3;
        assertEquals(Math.sqrt(dx * dx + dz * dz), g.distanceTo(-10, 1.0, 4.0), EPS); // off to the side
        assertEquals(0, g.distanceTo(2.0, 1.0, 1.0), EPS);
        assertEquals(0.5 * Math.hypot(4 - 2 * B, 2 - 2 * B), g.boundingRadius(), EPS);
    }

    @Test
    void rejectsUndrawableRecords() {
        assertNull(ScreenGeometry.of(6, 2, 2, 0));
        assertNull(ScreenGeometry.of(2, 0, 2, 0));
        assertNull(ScreenGeometry.of(null));
    }
}
