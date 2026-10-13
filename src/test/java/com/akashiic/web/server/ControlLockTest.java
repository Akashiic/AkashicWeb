package com.akashiic.web.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.WebSettings;

/**
 * When the server lets go of a controller lock (D-016; review finding "no keepalive, the lock lapses after 120 s and
 * media samples do not count").
 */
class ControlLockTest {

    private static final int IDLE = WebSettings.defaults().controllerIdleSeconds;
    private static final double RANGE = 24;

    private static ControlLock.Release check(double distance, boolean mayControl, long now, long keepalive,
        long playing) {
        return ControlLock.check(true, true, distance, RANGE, mayControl, now, keepalive, playing, IDLE);
    }

    @Test
    void theDefaultIdleTimeIsSixtySeconds() {
        assertEquals(60, IDLE);
    }

    @Test
    void aKeepaliveKeepsTheLockAndItsAbsenceReleasesIt() {
        long t0 = 1_000_000;
        assertEquals(ControlLock.Release.NONE, check(5, true, t0 + 59_000, t0, 0));
        assertEquals(ControlLock.Release.IDLE, check(5, true, t0 + 60_001, t0, 0));
        // The controller's client repeats take at most every 5 s while it interacts.
        assertEquals(ControlLock.Release.NONE, check(5, true, t0 + 60_001, t0 + 55_000, 0));
    }

    @Test
    void watchingIsNotIdling() {
        long t0 = 1_000_000;
        long hourLater = t0 + 3_600_000;
        assertEquals(
            ControlLock.Release.NONE,
            check(5, true, hourLater, t0, hourLater - 250),
            "a playing video (or an ad) keeps the lock without any click");
        assertEquals(
            ControlLock.Release.IDLE,
            check(5, true, hourLater, t0, hourLater - 61_000),
            "a minute after the media stopped playing, it goes");
    }

    @Test
    void absenceDistanceAndRightsReleaseAtOnce() {
        long now = 5_000;
        assertEquals(ControlLock.Release.OFFLINE, ControlLock.check(false, false, 0, RANGE, true, now, now, now, IDLE));
        assertEquals(
            ControlLock.Release.DIMENSION,
            ControlLock.check(true, false, 0, RANGE, true, now, now, now, IDLE));
        assertEquals(ControlLock.Release.NONE, check(RANGE + ControlLock.REACH_MARGIN, true, now, now, now));
        assertEquals(
            ControlLock.Release.FAR,
            check(RANGE + ControlLock.REACH_MARGIN + 0.01, true, now, now, now),
            "walked out of reach (with a margin against flapping at the edge)");
        assertEquals(ControlLock.Release.RIGHTS, check(5, false, now, now, now), "friend removed or screen locked");
    }
}
