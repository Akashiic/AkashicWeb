package com.akashiic.web.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScreenAudioTest {

    private static final int DIM = 8; // range 32

    @Test
    void rangeGrowsWithTheScreenWithinBounds() {
        assertEquals(20, ScreenAudio.range(2), 1e-9);
        assertEquals(32, ScreenAudio.range(8), 1e-9);
        assertEquals(64, ScreenAudio.range(40), 1e-9);
        assertEquals(18, ScreenAudio.range(0), 1e-9);
    }

    @Test
    void startsMutedThenFadesInFromSilence() {
        ScreenAudio a = new ScreenAudio();
        ScreenAudio.Command first = a.update(1, DIM, 1f, 1f, false, 0.05, 0);
        assertNotNull(first);
        assertFalse(first.muted);
        assertTrue(first.volume < 0.5f, "fades in instead of jumping to full volume");
        float last = first.volume;
        long t = 0;
        for (int i = 0; i < 40; i++) {
            t += 50;
            ScreenAudio.Command c = a.update(1, DIM, 1f, 1f, false, 0.05, t);
            if (c != null) {
                assertTrue(c.volume >= last, "rising");
                last = c.volume;
            }
        }
        assertEquals(1f, last, 0, "settles exactly on the target");
        assertNull(a.update(1, DIM, 1f, 1f, false, 0.05, t + 50), "nothing to send once settled");
    }

    @Test
    void hardMuteIsImmediateAndNotRepeated() {
        ScreenAudio a = new ScreenAudio();
        long t = 0;
        for (int i = 0; i < 30; i++) a.update(1, DIM, 1f, 1f, false, 0.05, t += 50);
        ScreenAudio.Command mute = a.update(1, DIM, 1f, 1f, true, 0.05, t + 1);
        assertNotNull(mute, "no rate limit on muting");
        assertTrue(mute.muted);
        assertEquals(0f, mute.volume, 0);
        assertNull(a.update(1, DIM, 1f, 1f, true, 0.05, t + 2));
        ScreenAudio.Command back = a.update(1, DIM, 1f, 1f, false, 0.05, t + 3);
        assertNotNull(back, "un-mute is sent at once");
        assertTrue(back.volume < 0.5f, "and fades in again");
    }

    @Test
    void outOfRangeOrSilentLevelsMute() {
        ScreenAudio a = new ScreenAudio();
        assertTrue(a.update(40, DIM, 1f, 1f, false, 0.05, 0).muted, "beyond the range");
        ScreenAudio b = new ScreenAudio();
        assertTrue(b.update(1, DIM, 0f, 1f, false, 0.05, 0).muted, "master at 0");
        ScreenAudio c = new ScreenAudio();
        assertTrue(c.update(1, DIM, 1f, 0f, false, 0.05, 0).muted, "records at 0");
    }

    @Test
    void volumeUpdatesAreRateLimitedToAboutTenHertz() {
        ScreenAudio a = new ScreenAudio();
        long t = 0;
        for (int i = 0; i < 60; i++) a.update(1, DIM, 1f, 1f, false, 0.05, t += 50);
        int sent = 0;
        // Walk away steadily: the target changes every tick.
        for (int i = 0; i < 20; i++) {
            t += 10;
            if (a.update(2 + i, DIM, 1f, 1f, false, 0.01, t) != null) sent++;
        }
        assertTrue(sent <= 2, "at most one update per 100 ms, got " + sent);
    }

    @Test
    void invalidateResendsAfterAHostRestart() {
        ScreenAudio a = new ScreenAudio();
        assertTrue(a.update(40, DIM, 1f, 1f, false, 0.05, 0).muted);
        assertNull(a.update(40, DIM, 1f, 1f, false, 0.05, 50));
        a.invalidate();
        assertNotNull(a.update(40, DIM, 1f, 1f, false, 0.05, 100));
    }
}
