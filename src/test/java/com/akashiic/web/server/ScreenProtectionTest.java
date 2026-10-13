package com.akashiic.web.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.world.ChunkPosition;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.TestScreens;

/** Review finding (low): explosions, the wither and the dragon broke any formed screen. */
class ScreenProtectionTest {

    @Test
    void anExplosionGoesOnAroundTheShieldedBlocks() {
        List<ChunkPosition> affected = new ArrayList<>(
            Arrays.asList(new ChunkPosition(0, 64, 0), new ChunkPosition(1, 64, 0), new ChunkPosition(5, 64, 0)));
        // A screen over x 0..3 at y 64, z 0.
        int spared = ScreenProtection.spare(affected, (x, y, z) -> x >= 0 && x <= 3 && y == 64 && z == 0);
        assertEquals(2, spared);
        assertEquals(1, affected.size());
        assertEquals(5, affected.get(0).chunkPosX, "the dirt next to it still goes");
    }

    @Test
    void onlyScreensWithAnOwnerAreProtected() {
        ScreenState owned = TestScreens.state(BlockSide.NORTH, 0, 64, 0, 2, 2, 0);
        ScreenState ownerless = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 2, 2, 0);
        ownerless.owner = null;
        assertTrue(ScreenProtection.owned(Collections.singletonList(owned)));
        assertTrue(ScreenProtection.owned(Arrays.asList(ownerless, owned)), "a double-sided block with one owner");
        assertFalse(ScreenProtection.owned(Collections.singletonList(ownerless)));
        assertFalse(ScreenProtection.owned(Collections.<ScreenState>emptyList()), "an unformed screen block");
    }

    @Test
    void nothingIsDecidedForClientWorldsOrWithoutAWorld() {
        assertFalse(ScreenProtection.shielded(null, 0, 64, 0));
        assertEquals(0, ScreenProtection.spare(null, new ArrayList<ChunkPosition>()));
    }
}
