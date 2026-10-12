package com.akashiic.web.client.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerClockTest {

    @Test
    void pingsFastUntilFourSamplesThenSlow() {
        ServerClock c = new ServerClock(42);
        long n1 = c.pollPing(1_000);
        assertNotEquals(0, n1);
        assertEquals(0, c.pollPing(1_100));
        long n2 = c.pollPing(1_250);
        assertNotEquals(0, n2);
        assertNotEquals(n1, n2);
        // Feed four exchanges: the schedule switches to every 5 s.
        long t = 1_250;
        assertTrue(c.onPong(n2, 1_250, 101_260, 1_270));
        for (int i = 0; i < 3; i++) {
            t += ServerClock.FAST_INTERVAL_MS;
            long n = c.pollPing(t);
            assertNotEquals(0, n);
            assertTrue(c.onPong(n, t, t + 100_010, t + 20));
        }
        assertEquals(4, c.samples());
        assertEquals(0, c.pollPing(t + ServerClock.FAST_INTERVAL_MS));
        assertNotEquals(0, c.pollPing(t + ServerClock.SLOW_INTERVAL_MS));
    }

    @Test
    void estimatesServerTimeFromPongs() {
        ServerClock c = new ServerClock(1);
        assertTrue(Double.isNaN(c.serverNowMs(0)));
        long t = 0;
        for (int i = 0; i < 3; i++) {
            long n = c.pollPing(t);
            // Server is 100 000 ms ahead, 10 ms each way.
            assertTrue(c.onPong(n, t, t + 10 + 100_000, t + 20));
            t += ServerClock.FAST_INTERVAL_MS;
        }
        assertTrue(c.ready());
        assertEquals(100_000, c.offsetMs(), 0.001);
        assertEquals(105_000, c.serverNowMs(5_000), 0.001);
        assertEquals(20, c.bestRttMs(), 0.001);
    }

    @Test
    void rejectsUnsolicitedAndForgedPongs() {
        ServerClock c = new ServerClock(7);
        long n = c.pollPing(500);
        assertFalse(c.onPong(n + 1, 500, 1_000, 520), "unknown nonce");
        assertFalse(c.onPong(n, 499, 1_000, 520), "echoed send time differs");
        assertFalse(c.onPong(n, 500, 1_000, 520), "consumed by the failed attempt above");
        long m = c.pollPing(800);
        assertFalse(c.onPong(m, 800, 1_000, 700), "arrival before send");
        assertEquals(0, c.samples());
    }

    @Test
    void resetForgetsEverything() {
        ServerClock c = new ServerClock(3);
        long t = 0;
        for (int i = 0; i < 3; i++) {
            long n = c.pollPing(t);
            c.onPong(n, t, t + 5, t + 10);
            t += ServerClock.FAST_INTERVAL_MS;
        }
        assertTrue(c.ready());
        c.reset();
        assertFalse(c.ready());
        assertNotEquals(0, c.pollPing(t), "pings again immediately after reset");
    }
}
