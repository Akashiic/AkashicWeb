package com.akashiic.web.client.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.ReasonCode;

/** Backoff schedule, crash-loop window and failure-to-reason mapping. */
class RelaunchPolicyTest {

    @Test
    void backoffDoublesFromOneSecondAndCapsAtSixty() {
        Backoff b = new Backoff();
        long[] expected = { 1000, 2000, 4000, 8000, 16000, 32000, 60000, 60000, 60000 };
        for (long e : expected) assertEquals(e, b.next());
        b.reset();
        assertEquals(1000, b.next());
        assertEquals(2000, b.next());
    }

    @Test
    void crashLoopTripsOnTheFifthExitWithinTenMinutes() {
        CrashLoopGuard g = new CrashLoopGuard();
        long t = 0;
        for (int i = 1; i <= 4; i++) assertFalse(g.recordExit(t += 60_000), "exit " + i);
        assertTrue(g.recordExit(t += 60_000), "fifth exit within 5 minutes");
        g.clear();
        assertEquals(0, g.recentExits(t));
    }

    @Test
    void exitsOutsideTheWindowDoNotCount() {
        CrashLoopGuard g = new CrashLoopGuard();
        long t = 0;
        // One exit every 2.5 minutes: never five inside any 10-minute window.
        for (int i = 0; i < 50; i++) assertFalse(g.recordExit(t += 150_000), "exit " + i);
        assertEquals(4, g.recentExits(t));
        assertEquals(0, g.recentExits(t + CrashLoopGuard.DEFAULT_WINDOW_MILLIS));
        // Exactly ten minutes apart: the first one has left the window.
        CrashLoopGuard edge = new CrashLoopGuard(2, 1000);
        assertFalse(edge.recordExit(0));
        assertFalse(edge.recordExit(1000));
        assertTrue(edge.recordExit(1999));
        assertThrows(IllegalArgumentException.class, () -> new CrashLoopGuard(0, 1));
    }

    @Test
    void failuresMapToReasonCodes() {
        assertEquals(ReasonCode.HOST_HANDSHAKE, HostFailure.BAD_TOKEN.reason());
        assertEquals(ReasonCode.HOST_HANDSHAKE, HostFailure.CONNECT_TIMEOUT.reason());
        assertEquals(ReasonCode.PROTOCOL_MISMATCH, HostFailure.PROTOCOL_MAJOR.reason());
        assertEquals(ReasonCode.HOST_EXITED, HostFailure.CONNECTION_LOST.reason());
        assertEquals(ReasonCode.HOST_CRASH_LOOP, HostFailure.CRASH_LOOP.reason());
        assertTrue(HostFailure.PROTOCOL_MAJOR.disables());
        assertFalse(HostFailure.BAD_TOKEN.disables());
        assertFalse(HostFailure.CONNECTION_LOST.disables());
        for (HostFailure f : HostFailure.values()) assertTrue(f.reason() != null, f.name());
    }
}
