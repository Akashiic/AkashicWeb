package com.akashiic.web.client.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.net.MediaSample;

class MediaSamplerTest {

    private static LocalMedia sample(double stamp) {
        return new LocalMedia(42.5, false, 1f, 600, true, 3.25, true, false, 0, stamp);
    }

    @Test
    void sendsAtMostFourTimesASecondStampedInServerTime() {
        MediaSampler s = new MediaSampler();
        MediaSample m = s.next(7, sample(123_456.6), 1_000);
        assertNotNull(m);
        assertEquals(7, m.epoch);
        assertEquals(42.5, m.mediaTime, 1e-9);
        assertEquals(123_457, m.clientServerTimeMs);
        assertTrue(m.adShowing, "the ad flag travels so the server can hold its clock");
        assertTrue(m.live);
        assertEquals(3.25, m.behindLiveEdge, 1e-9);
        assertNull(s.next(7, sample(1), 1_100));
        assertNotNull(s.next(7, sample(1), 1_000 + MediaSampler.MIN_INTERVAL_MS));
        s.reset();
        assertNotNull(s.next(7, sample(1), 1_201));
    }

    @Test
    void refusesSamplesWithoutServerTimeOrPosition() {
        MediaSampler s = new MediaSampler();
        assertNull(s.next(1, sample(Double.NaN), 0), "clock not synced when the sample arrived");
        assertNull(s.next(1, null, 0));
        assertNull(s.next(1, new LocalMedia(Double.NaN, false, 1f, 1, false, 0, false, false, 0, 5), 0));
        assertNull(s.next(1, new LocalMedia(-1, false, 1f, 1, false, 0, false, false, 0, 5), 0));
    }
}
