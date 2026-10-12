package com.akashiic.web.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.net.MediaSample;

class MediaClockTest {

    private static MediaSample sample(int epoch, double t, float rate, boolean paused, long stampMs) {
        return new MediaSample(epoch, t, rate, paused, false, false, 0, stampMs);
    }

    @Test
    void extrapolatesFromTheClientStampNotTheReceiveTime() {
        MediaClock c = new MediaClock(3);
        assertTrue(Double.isNaN(c.positionAt(0)), "unknown before the first sample");
        // Sampled at server time 10 000 (client's estimate), handled 40 ms later on the tick.
        assertEquals(MediaClock.Result.APPLIED, c.apply(sample(3, 60.0, 1f, false, 10_000), 3, 10_040));
        assertEquals(60.04, c.positionAt(10_040), 1e-9);
        assertEquals(62.0, c.positionAt(12_000), 1e-9);
        assertEquals(10_000, c.anchorMs());
    }

    @Test
    void rateAndPause() {
        MediaClock c = new MediaClock(0);
        c.apply(sample(0, 10.0, 1.05f, false, 1000), 0, 1000);
        assertEquals(10.0 + 1.05 * 2, c.positionAt(3000), 1e-6);
        c.apply(sample(0, 30.0, 1f, true, 2000), 0, 2000);
        assertEquals(30.0, c.positionAt(99_000), 1e-9);
        assertTrue(c.paused());
    }

    @Test
    void staleEpochAndInvalidValuesAreRefused() {
        MediaClock c = new MediaClock(5);
        assertEquals(MediaClock.Result.STALE_EPOCH, c.apply(sample(4, 1, 1f, false, 0), 5, 0));
        assertEquals(MediaClock.Result.INVALID, c.apply(sample(5, -1, 1f, false, 0), 5, 0));
        assertEquals(MediaClock.Result.INVALID, c.apply(sample(5, 1, 99f, false, 0), 5, 0));
        assertEquals(MediaClock.Result.INVALID, c.apply(new MediaSample(5, 1, 1f, false, false, true, -3, 0), 5, 0));
        assertFalse(c.valid());
    }

    @Test
    void adsDoNotMoveTheClock() {
        MediaClock c = new MediaClock(0);
        c.apply(sample(0, 100, 1f, false, 1000), 0, 1000);
        MediaSample ad = new MediaSample(0, 5, 1f, false, true, false, 0, 2000);
        assertEquals(MediaClock.Result.IGNORED_AD, c.apply(ad, 0, 2000));
        assertEquals(101, c.positionAt(2000), 1e-9);
    }

    @Test
    void implausibleStampsAreReplacedByReceiveTime() {
        MediaClock c = new MediaClock(0);
        c.apply(sample(0, 10, 1f, false, 50_000), 0, 1000); // far in the future: broken clock sync
        assertEquals(1000, c.anchorMs());
        c.apply(sample(0, 20, 1f, false, 1), 0, 20_000); // far in the past
        assertEquals(20_000, c.anchorMs());
        assertEquals(2, c.restamped());
        c.apply(sample(0, 21, 1f, false, 20_200), 0, 20_100); // 100 ms ahead: within tolerance, kept
        assertEquals(20_200, c.anchorMs());
    }

    @Test
    void reorderedSamplesAreIgnored() {
        MediaClock c = new MediaClock(0);
        c.apply(sample(0, 10, 1f, false, 5000), 0, 5000);
        assertEquals(MediaClock.Result.IGNORED_OLD, c.apply(sample(0, 9.5, 1f, false, 4500), 0, 5010));
        assertEquals(10, c.positionAt(5000), 1e-9);
    }

    @Test
    void newEpochResets() {
        MediaClock c = new MediaClock(0);
        c.apply(sample(0, 10, 1f, false, 5000), 0, 5000);
        c.reset(1);
        assertFalse(c.valid());
        assertEquals(1, c.epoch());
        // A sample for the new page re-anchors even if its stamp is older than the old anchor.
        assertEquals(MediaClock.Result.APPLIED, c.apply(sample(1, 0, 1f, false, 4000), 1, 4100));
        assertEquals(1, c.positionAt(5000), 1e-9);
    }

    @Test
    void clockNeverGoesNegative() {
        MediaClock c = new MediaClock(0);
        c.apply(sample(0, 0.5, 1f, false, 1000), 0, 1000);
        assertEquals(0, c.positionAt(0), 1e-9);
    }
}
