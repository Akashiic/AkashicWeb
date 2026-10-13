package com.akashiic.web.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.HitMath;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.TestScreens;

/** Server-side reach and ray checks for pointer packets, on every face. */
class PointerCheckTest {

    @Test
    void lookingAtTheScreenFromTheFront() {
        for (BlockSide side : BlockSide.values()) {
            ScreenState s = TestScreens.state(side, 0, 64, 0, 4, 3, 90);
            double[] c = HitMath.worldPoint(s, 0.5, 0.5);
            double ex = c[0] + side.fwdX * 4, ey = c[1] + side.fwdY * 4, ez = c[2] + side.fwdZ * 4;
            double[] look = { -side.fwdX, -side.fwdY, -side.fwdZ };
            assertEquals(ReasonCode.OK, PointerCheck.check(s, ex, ey, ez, look, 0.5f, 0.5f, 24, 75), side.name());
            assertEquals(ReasonCode.OK, PointerCheck.check(s, ex, ey, ez, look, 0f, 1f, 24, 75), side + " corner");
            // Behind the screen.
            double bx = c[0] - side.fwdX * 4, by = c[1] - side.fwdY * 4, bz = c[2] - side.fwdZ * 4;
            double[] back = { side.fwdX, side.fwdY, side.fwdZ };
            assertEquals(
                ReasonCode.OUT_OF_VIEW,
                PointerCheck.check(s, bx, by, bz, back, 0.5f, 0.5f, 24, 75),
                side + " behind");
            // Turned away.
            assertEquals(
                ReasonCode.OUT_OF_VIEW,
                PointerCheck.check(s, ex, ey, ez, back, 0.5f, 0.5f, 24, 75),
                side + " away");
            // Out of reach.
            assertEquals(ReasonCode.TOO_FAR, PointerCheck.check(s, ex, ey, ez, look, 0.5f, 0.5f, 3, 75), side + " far");
        }
    }

    @Test
    void positionsOutsideThePageAreMalformed() {
        ScreenState s = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 4, 3, 0);
        double[] look = { 0, 0, -1 };
        assertEquals(ReasonCode.BAD_PACKET, PointerCheck.check(s, 2, 65, 5, look, 1.5, 0.5, 24, 75));
        assertEquals(ReasonCode.BAD_PACKET, PointerCheck.check(s, 2, 65, 5, look, Double.NaN, 0.5, 24, 75));
    }

    @Test
    void cursorAwayFromTheCrosshairIsAcceptedWithinTheCone() {
        // GUI control: the view is fixed on the centre, the cursor is at a corner about 49 degrees off.
        ScreenState s = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 8, 5, 0);
        double[] c = HitMath.worldPoint(s, 0.5, 0.5);
        double[] look = { 0, 0, -1 };
        assertEquals(ReasonCode.OK, PointerCheck.check(s, c[0], c[1], c[2] + 4, look, 0.0, 0.0, 24, 75));
        assertEquals(ReasonCode.OUT_OF_VIEW, PointerCheck.check(s, c[0], c[1], c[2] + 4, look, 0.0, 0.0, 24, 30));
    }

    @Test
    void nearCheckForActionsWithoutPosition() {
        ScreenState s = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 4, 3, 0);
        assertEquals(ReasonCode.OK, PointerCheck.near(s, 2, 65, 10, 24, true));
        assertEquals(ReasonCode.TOO_FAR, PointerCheck.near(s, 2, 65, 40, 24, true));
        assertEquals(ReasonCode.OUT_OF_VIEW, PointerCheck.near(s, 2, 65, -3, 24, true));
        assertEquals(ReasonCode.OK, PointerCheck.near(s, 2, 65, -3, 24, false));
    }
}
