package com.akashiic.web.client.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClockMathTest {

    @Test
    void offsetAndRttOfASymmetricExchange() {
        // Client 0, server is 1000 µs ahead, 200 µs each way, server holds 50 µs.
        long t0 = 0, t1 = 1200, t2 = 1250, t3 = 450;
        assertEquals(1000, ClockMath.offset(t0, t1, t2, t3));
        assertEquals(400, ClockMath.rtt(t0, t1, t2, t3));
    }

    @Test
    void estimatorUsesMedianOfLowestRttExchanges() {
        ClockMath.Estimator e = new ClockMath.Estimator(16, 3);
        e.add(5_000, 100_000); // congested exchange: large error, must be ignored
        e.add(1_000, 2_000);
        e.add(1_100, 2_100);
        e.add(900, 1_900);
        e.add(-40_000, 90_000); // another bad one
        assertEquals(1_000, e.offsetMicros());
        assertEquals(1_900, e.bestRttMicros());
        assertEquals(5, e.samples());
    }

    @Test
    void negativeRttIsRejectedAndWindowWraps() {
        ClockMath.Estimator e = new ClockMath.Estimator(2, 2);
        e.add(1, -1);
        assertEquals(0, e.samples());
        assertEquals(-1, e.bestRttMicros());
        e.add(10, 5);
        e.add(20, 5);
        e.add(30, 5); // overwrites the oldest
        assertEquals(2, e.samples());
        assertEquals(25, e.offsetMicros());
        assertTrue(e.ready(2));
        e.clear();
        assertFalse(e.ready(1));
        assertEquals(0, e.offsetMicros());
    }
}
