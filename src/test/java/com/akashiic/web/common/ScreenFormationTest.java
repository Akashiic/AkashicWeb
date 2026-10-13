package com.akashiic.web.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.ScreenFormation.Integrity;
import com.akashiic.web.common.ScreenFormation.Plan;
import com.akashiic.web.common.TestScreens.Grid;

class ScreenFormationTest {

    private static void assertPlan(Plan p, int ox, int oy, int oz, int w, int h) {
        assertEquals(ReasonCode.OK, p.reason);
        assertEquals(ox, p.originX, "originX");
        assertEquals(oy, p.originY, "originY");
        assertEquals(oz, p.originZ, "originZ");
        assertEquals(w, p.width, "width");
        assertEquals(h, p.height, "height");
    }

    /** A 3x2 wall in the XY plane at z=0. */
    private static Grid wall() {
        return new Grid().box(0, 64, 0, 2, 65, 0);
    }

    @Test
    void northFaceOriginIsTheEastEndSeenFromTheNorth() {
        // Looking south at the north face, the viewer's left is east (+X): the origin is the east-bottom block.
        assertPlan(ScreenFormation.plan(wall(), 1, 65, 0, BlockSide.NORTH, 16, 16), 2, 64, 0, 3, 2);
        assertPlan(ScreenFormation.plan(wall(), 0, 64, 0, BlockSide.NORTH, 16, 16), 2, 64, 0, 3, 2);
    }

    @Test
    void southFaceOriginIsTheWestEnd() {
        assertPlan(ScreenFormation.plan(wall(), 2, 65, 0, BlockSide.SOUTH, 16, 16), 0, 64, 0, 3, 2);
    }

    @Test
    void westAndEastWalls() {
        Grid g = new Grid().box(0, 10, 0, 0, 12, 3); // 4 wide (Z), 3 high, at x=0
        assertPlan(ScreenFormation.plan(g, 0, 11, 2, BlockSide.WEST, 16, 16), 0, 10, 0, 4, 3);
        assertPlan(ScreenFormation.plan(g, 0, 11, 2, BlockSide.EAST, 16, 16), 0, 10, 3, 4, 3);
    }

    @Test
    void floorAndCeiling() {
        Grid g = new Grid().box(0, 64, 0, 1, 64, 2); // 2 (X) by 3 (Z)
        // TOP: right +X, up -Z (north) -> origin is west, south.
        assertPlan(ScreenFormation.plan(g, 1, 64, 1, BlockSide.TOP, 16, 16), 0, 64, 2, 2, 3);
        // BOTTOM: right -X, up -Z -> origin is east, south.
        assertPlan(ScreenFormation.plan(g, 0, 64, 0, BlockSide.BOTTOM, 16, 16), 1, 64, 2, 2, 3);
        // The thin sides of a floor are 1 block tall: too small.
        assertEquals(ReasonCode.SCREEN_TOO_SMALL, ScreenFormation.plan(g, 0, 64, 0, BlockSide.NORTH, 16, 16).reason);
    }

    @Test
    void minimumIsTwoByTwo() {
        Grid g = new Grid().box(0, 64, 0, 0, 65, 0); // 1x2
        assertEquals(ReasonCode.SCREEN_TOO_SMALL, ScreenFormation.plan(g, 0, 64, 0, BlockSide.SOUTH, 16, 16).reason);
        assertPlan(
            ScreenFormation.plan(new Grid().box(0, 64, 0, 1, 65, 0), 0, 64, 0, BlockSide.SOUTH, 16, 16),
            0,
            64,
            0,
            2,
            2);
    }

    @Test
    void maximumIsConfigurable() {
        Grid g = new Grid().box(0, 64, 0, 16, 65, 0); // 17x2
        assertEquals(ReasonCode.SCREEN_TOO_BIG, ScreenFormation.plan(g, 0, 64, 0, BlockSide.SOUTH, 16, 16).reason);
        assertEquals(ReasonCode.SCREEN_TOO_BIG, ScreenFormation.plan(g, 16, 64, 0, BlockSide.SOUTH, 16, 16).reason);
        assertPlan(ScreenFormation.plan(g, 5, 64, 0, BlockSide.SOUTH, 32, 32), 0, 64, 0, 17, 2);
    }

    @Test
    void holesAndLShapesAreRejected() {
        Grid l = wall().add(0, 66, 0); // extra block above the west end: measured 3x3 with two holes on top
        Plan p = ScreenFormation.plan(l, 1, 64, 0, BlockSide.SOUTH, 16, 16);
        assertEquals(ReasonCode.SCREEN_INVALID_SHAPE, p.reason);
        assertEquals(1, p.badX);
        assertEquals(66, p.badY);
        // A hole in the middle of a wall.
        Grid hole = new Grid().box(0, 64, 0, 2, 66, 0)
            .remove(1, 65, 0);
        Plan h = ScreenFormation.plan(hole, 0, 64, 0, BlockSide.SOUTH, 16, 16);
        assertEquals(ReasonCode.SCREEN_INVALID_SHAPE, h.reason);
        assertEquals(1, h.badX);
        assertEquals(65, h.badY);
    }

    @Test
    void blocksInFrontOrBehindAreRejected() {
        Plan behind = ScreenFormation.plan(wall().add(1, 64, 1), 1, 64, 0, BlockSide.NORTH, 16, 16);
        assertEquals(ReasonCode.SCREEN_INVALID_SHAPE, behind.reason);
        assertEquals(1, behind.badZ);
        Plan front = ScreenFormation.plan(wall().add(1, 65, -1), 1, 64, 0, BlockSide.NORTH, 16, 16);
        assertEquals(ReasonCode.SCREEN_INVALID_SHAPE, front.reason);
        assertEquals(-1, front.badZ);
    }

    @Test
    void unloadedChunksAbortInsteadOfGuessing() {
        Grid g = wall().unload(3, 64, 0);
        assertEquals(ReasonCode.CHUNK_NOT_LOADED, ScreenFormation.plan(g, 1, 64, 0, BlockSide.SOUTH, 16, 16).reason);
        Grid back = wall().unload(1, 64, 1);
        assertEquals(ReasonCode.CHUNK_NOT_LOADED, ScreenFormation.plan(back, 1, 64, 0, BlockSide.SOUTH, 16, 16).reason);
    }

    @Test
    void clickedBlockMustBeAScreenBlock() {
        assertEquals(
            ReasonCode.SCREEN_INVALID_SHAPE,
            ScreenFormation.plan(wall(), 9, 9, 9, BlockSide.SOUTH, 16, 16).reason);
    }

    @Test
    void existingScreenSurvivesNeighbourPlacement() {
        Grid g = wall();
        assertEquals(Integrity.INTACT, ScreenFormation.intact(g, 0, 64, 0, BlockSide.SOUTH, 3, 2));
        // A screen block placed beside, in front of or behind it: the screen stays (WD destroyed it).
        g.add(3, 64, 0)
            .add(1, 64, 1)
            .add(1, 65, -1);
        assertEquals(Integrity.INTACT, ScreenFormation.intact(g, 0, 64, 0, BlockSide.SOUTH, 3, 2));
    }

    @Test
    void removedOrUnloadedBlocks() {
        assertEquals(
            Integrity.BROKEN,
            ScreenFormation.intact(wall().remove(2, 65, 0), 0, 64, 0, BlockSide.SOUTH, 3, 2));
        assertEquals(
            Integrity.UNKNOWN,
            ScreenFormation.intact(wall().unload(2, 65, 0), 0, 64, 0, BlockSide.SOUTH, 3, 2));
    }

    @Test
    void containsAndOverlaps() {
        assertTrue(ScreenFormation.contains(2, 64, 0, BlockSide.NORTH, 3, 2, 0, 65, 0));
        assertFalse(ScreenFormation.contains(2, 64, 0, BlockSide.NORTH, 3, 2, 3, 65, 0));
        assertFalse(ScreenFormation.contains(2, 64, 0, BlockSide.NORTH, 3, 2, 1, 64, 1)); // other plane
        assertTrue(ScreenFormation.overlaps(0, 64, 0, 3, 2, 1, 65, 0, 2, 2, BlockSide.SOUTH));
        assertFalse(ScreenFormation.overlaps(0, 64, 0, 3, 2, 3, 64, 0, 2, 2, BlockSide.SOUTH));
        assertFalse(ScreenFormation.overlaps(0, 64, 0, 3, 2, 0, 64, 1, 3, 2, BlockSide.SOUTH));
    }
}
