package com.akashiic.web.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GainModelTest {

    @Test
    void curveIsFullNearAndReachesZeroExactlyAtRange() {
        assertEquals(1, GainModel.distanceAttenuation(0, 32), 0);
        assertEquals(1, GainModel.distanceAttenuation(GainModel.NEAR_DISTANCE, 32), 0);
        assertEquals(0, GainModel.distanceAttenuation(32, 32), 0);
        assertEquals(0, GainModel.distanceAttenuation(100, 32), 0);
        assertEquals(0.25, GainModel.distanceAttenuation(17, 32), 1e-12); // halfway between 2 and 32: (1-0.5)^2
        assertEquals(0, GainModel.distanceAttenuation(Double.NaN, 32), 0);
        assertEquals(0, GainModel.distanceAttenuation(-1, 32), 0);
        assertEquals(0, GainModel.distanceAttenuation(1, 0), 0);
        assertEquals(1, GainModel.distanceAttenuation(1, 1.5), 0, "tiny ranges are a hard edge");
        assertEquals(0, GainModel.distanceAttenuation(1.6, 1.5), 0);
    }

    @Test
    void curveIsMonotonicAndSmoothAtTheRange() {
        double prev = 1;
        for (double d = 0; d <= 40; d += 0.25) {
            double g = GainModel.distanceAttenuation(d, 40);
            assertTrue(g <= prev + 1e-12, "monotonic at " + d);
            prev = g;
        }
        // The derivative vanishes at the range: the last step is tiny compared with a linear fade.
        double step = GainModel.distanceAttenuation(39.75, 40) - GainModel.distanceAttenuation(40, 40);
        assertTrue(step < 0.001, "no audible cut when walking out of range");
    }

    @Test
    void screenGainMultipliesMasterAndRecords() {
        assertEquals(0.25f * 0.5f * 0.8f, GainModel.screenGain(17, 32, 0.5f, 0.8f), 1e-6);
        assertEquals(0f, GainModel.screenGain(0, 32, 0f, 1f), 0);
        assertEquals(1f, GainModel.screenGain(0, 32, 2f, 7f), 0, "levels are clamped to 1");
        assertEquals(0f, GainModel.screenGain(0, 32, -1f, 1f), 0);
    }

    @Test
    void smoothingApproachesTheTarget() {
        float v = GainModel.smooth(0f, 1f, 0.15, 0.15);
        assertEquals(1 - Math.exp(-1), v, 1e-6);
        assertEquals(1f, GainModel.smooth(0f, 1f, 0.05, 0), 0, "no smoothing without a time constant");
        // Verbatim AkashicFM semantics: without elapsed time there is nothing to smooth over, so the target is taken.
        assertEquals(1f, GainModel.smooth(0.3f, 1f, 0, 0.15), 0);
    }
}
