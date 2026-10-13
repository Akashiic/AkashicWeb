package com.akashiic.web.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScreenLodTest {

    @Test
    void frameRateFallsWithDistanceAndView() {
        int dim = 2; // a 2x2 monitor: near 20, mid 40, paint 60
        assertEquals(ScreenLod.FPS_NEAR, ScreenLod.of(5, true, dim).fps);
        assertEquals(ScreenLod.FPS_NEAR, ScreenLod.of(20, true, dim).fps);
        assertEquals(ScreenLod.FPS_MID, ScreenLod.of(30, true, dim).fps);
        assertEquals(ScreenLod.FPS_FAR_VISIBLE, ScreenLod.of(50, true, dim).fps);
        assertEquals(ScreenLod.FPS_OFF_VIEW, ScreenLod.of(5, false, dim).fps);
        assertTrue(ScreenLod.of(59, false, dim).painting);
        ScreenLod idle = ScreenLod.of(61, true, dim);
        assertFalse(idle.painting, "beyond range the host stops painting");
        assertEquals(ScreenLod.FPS_IDLE, idle.fps);
        assertFalse(ScreenLod.of(Double.POSITIVE_INFINITY, true, dim).painting);
        assertFalse(ScreenLod.of(Double.NaN, true, dim).painting);
    }

    @Test
    void frameRatesStayInsideTheAdrBands() {
        for (double d = 0; d < 200; d += 0.5) {
            for (int dim = 1; dim <= 32; dim *= 2) {
                ScreenLod near = ScreenLod.of(d, true, dim);
                ScreenLod off = ScreenLod.of(d, false, dim);
                assertTrue(near.fps == 30 || (near.fps >= 10 && near.fps <= 15) || (near.fps >= 1 && near.fps <= 5));
                assertTrue(off.fps >= 1 && off.fps <= 5, "off-view is 1-5 fps");
            }
        }
    }

    @Test
    void bigScreensReachFurtherButRangesAreCapped() {
        assertEquals(60, ScreenLod.paintRange(2), 1e-9);
        assertEquals(144, ScreenLod.paintRange(16), 1e-9); // spawn telão
        assertEquals(160, ScreenLod.paintRange(64), 1e-9);
        assertEquals(54, ScreenLod.paintRange(1), 1e-9);
        assertEquals(ScreenLod.paintRange(16) + 16, ScreenLod.keepRange(16), 1e-9);
        assertTrue(ScreenLod.of(100, true, 16).painting);
    }

    @Test
    void viewConeIncludesTheScreenWhenAnyPartIsAhead() {
        double[] eye = { 0, 0, 0 };
        double[] lookNorth = { 0, 0, -1 };
        assertTrue(ScreenLod.inView(eye, lookNorth, new double[] { 0, 0, -10 }, 1));
        assertFalse(ScreenLod.inView(eye, lookNorth, new double[] { 0, 0, 10 }, 1), "behind");
        assertTrue(ScreenLod.inView(eye, lookNorth, new double[] { 0, 0, 10 }, 12), "standing inside a huge screen");
        assertTrue(ScreenLod.inView(eye, lookNorth, new double[] { 10, 0, -4 }, 1), "off to the side but in the cone");
        assertFalse(ScreenLod.inView(eye, lookNorth, new double[] { 10, 0, 3 }, 1));
        assertTrue(ScreenLod.inView(eye, new double[] { 0, 0, 0 }, new double[] { 5, 5, 5 }, 1), "no look vector");
    }
}
