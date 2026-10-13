package com.akashiic.web.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.TestScreens;
import com.akashiic.web.common.WebSettings;

/** Screen formation rules that do not need a world. */
class ScreenLifecycleTest {

    private static final UUID FORMER = new UUID(5, 5);
    private static final WebSettings CFG = WebSettings.defaults();

    /** The client treats epochs per key as monotonic (ClientScreens.isOlder): a newer screen must never go below. */
    private static boolean newer(int epoch, int than) {
        return epoch - than > 0;
    }

    /**
     * Review finding: merging a 2x2 at origin K (epoch 30) with a larger 2x3 to its right (epoch 2) gave origin K and
     * epoch 3, so clients that held K at epoch 30 ignored every URL of the merged screen.
     */
    @Test
    void aMergedScreenNeverGoesBelowAnEpochItsKeyHad() {
        ScreenIndex index = new ScreenIndex();
        ScreenState small = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 2, 2, 0);
        small.epoch = 30;
        ScreenState big = TestScreens.state(BlockSide.SOUTH, 2, 64, 0, 2, 3, 0);
        big.epoch = 2;
        big.url = "https://www.youtube.com/watch?v=big";
        ScreenKey k = small.key;
        ScreenState merged = ScreenLifecycle
            .newScreen(k, 4, 3, FORMER, "Former", 0f, CFG, Arrays.asList(small, big), index);
        assertTrue(newer(merged.epoch, 30), "epoch " + merged.epoch);
        assertEquals("https://www.youtube.com/watch?v=big", merged.url, "the largest screen's page is kept");
        assertEquals(TestScreens.OWNER, merged.owner, "an admin merge keeps the owner");
    }

    @Test
    void aScreenDissolvedAndFormedAgainStartsAboveTheOldOne() {
        ScreenIndex index = new ScreenIndex();
        index.noteEpoch(41); // the old screen at this key reached epoch 41 before it was dissolved
        ScreenKey k = new ScreenKey(0, 0, 64, 0, BlockSide.SOUTH.side());
        ScreenState fresh = ScreenLifecycle
            .newScreen(k, 2, 2, FORMER, "Former", 0f, CFG, Collections.<ScreenState>emptyList(), index);
        assertEquals(42, fresh.epoch);
        assertEquals(FORMER, fresh.owner);
        ScreenState again = ScreenLifecycle
            .newScreen(k, 2, 2, FORMER, "Former", 0f, CFG, Collections.<ScreenState>emptyList(), index);
        assertTrue(newer(again.epoch, fresh.epoch), "two formations in one tick still grow");
    }

    @Test
    void theHighWaterMarkFollowsUrlChangesSurvivesSavesAndWraps() {
        ScreenIndex index = new ScreenIndex();
        assertTrue(index.noteEpoch(7));
        assertFalse(index.noteEpoch(5), "never backwards");
        ScreenIndexData data = new ScreenIndexData(ScreenIndexData.NAME);
        data.index.noteEpoch(1234);
        NBTTagCompound tag = new NBTTagCompound();
        data.writeToNBT(tag);
        ScreenIndexData back = new ScreenIndexData(ScreenIndexData.NAME);
        back.readFromNBT(tag);
        assertEquals(1234, back.index.epochHighWater());
        ScreenIndex wrap = new ScreenIndex();
        wrap.noteEpoch(Integer.MAX_VALUE);
        int next = wrap.nextEpoch(Collections.<ScreenState>emptyList());
        assertTrue(newer(next, Integer.MAX_VALUE), "wrap-aware, as on the client");
    }
}
