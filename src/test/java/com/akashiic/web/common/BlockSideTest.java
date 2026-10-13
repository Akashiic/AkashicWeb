package com.akashiic.web.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlockSideTest {

    @Test
    void ordinalsAreForgeSides() {
        assertEquals(0, BlockSide.BOTTOM.side());
        assertEquals(1, BlockSide.TOP.side());
        assertEquals(2, BlockSide.NORTH.side());
        assertEquals(3, BlockSide.SOUTH.side());
        assertEquals(4, BlockSide.WEST.side());
        assertEquals(5, BlockSide.EAST.side());
    }

    /** right = (-forward) x up on every face, so no face shows a mirrored page (WD's BOTTOM did). */
    @Test
    void everyFaceIsRightHanded() {
        for (BlockSide s : BlockSide.values()) {
            int dx = -s.fwdX, dy = -s.fwdY, dz = -s.fwdZ;
            int cx = dy * s.upZ - dz * s.upY;
            int cy = dz * s.upX - dx * s.upZ;
            int cz = dx * s.upY - dy * s.upX;
            assertEquals(s.rightX, cx, s + " x");
            assertEquals(s.rightY, cy, s + " y");
            assertEquals(s.rightZ, cz, s + " z");
        }
    }

    @Test
    void forwardPointsOutOfTheNamedFace() {
        assertEquals(-1, BlockSide.NORTH.fwdZ);
        assertEquals(1, BlockSide.SOUTH.fwdZ);
        assertEquals(-1, BlockSide.WEST.fwdX);
        assertEquals(1, BlockSide.EAST.fwdX);
        assertEquals(1, BlockSide.TOP.fwdY);
        assertEquals(-1, BlockSide.BOTTOM.fwdY);
    }

    @Test
    void oppositePairs() {
        assertEquals(BlockSide.TOP, BlockSide.BOTTOM.opposite());
        assertEquals(BlockSide.BOTTOM, BlockSide.TOP.opposite());
        assertEquals(BlockSide.SOUTH, BlockSide.NORTH.opposite());
        assertEquals(BlockSide.WEST, BlockSide.EAST.opposite());
        for (BlockSide s : BlockSide.values()) assertEquals(
            s,
            s.opposite()
                .opposite());
    }

    @Test
    void parsingIsLenientButBounded() {
        assertEquals(BlockSide.NORTH, BlockSide.parse("north"));
        assertEquals(BlockSide.TOP, BlockSide.parse("UP"));
        assertEquals(BlockSide.BOTTOM, BlockSide.parse("down"));
        assertEquals(BlockSide.EAST, BlockSide.parse("5"));
        assertNull(BlockSide.parse("6"));
        assertNull(BlockSide.parse("sideways"));
        assertNull(BlockSide.fromSide(-1));
        assertNull(BlockSide.fromSide(6));
    }

    @Test
    void horizontalFaces() {
        assertTrue(BlockSide.TOP.horizontal());
        assertTrue(BlockSide.BOTTOM.horizontal());
        assertFalse(BlockSide.NORTH.horizontal());
    }
}
