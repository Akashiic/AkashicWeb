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
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.ScreenFormation;
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

    /**
     * Review finding: the back face of another player's free-standing screen could be formed into a second screen,
     * and the original owner could then no longer break, move or re-form their own blocks.
     */
    @Test
    void theBackOfSomeoneElsesScreenCannotBeFormed() {
        // A 2x2 SOUTH screen on blocks x 0..1, y 64..65, z 0, owned by OWNER.
        ScreenState front = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 2, 2, 0);
        // The same blocks seen from the north: NORTH's right is -X, so its origin is x = 1.
        int[] back = ScreenFormation.blockBox(1, 64, 0, BlockSide.NORTH, 2, 2);
        int north = BlockSide.NORTH.side();
        ScreenLifecycle.Overlap visitor = ScreenLifecycle
            .overlap(0, north, back, Arrays.asList(front), NO_INDEX, TestScreens.VISITOR, false);
        assertEquals(ReasonCode.SCREEN_OVERLAP, visitor.reason);
        assertEquals("Owner", visitor.ownerName);
        ScreenLifecycle.Overlap own = ScreenLifecycle
            .overlap(0, north, back, Arrays.asList(front), NO_INDEX, TestScreens.OWNER, false);
        assertEquals(ReasonCode.OK, own.reason, "the owner may make it double-sided");
        assertTrue(own.sameSide.isEmpty(), "the front face stays a screen of its own");
        assertEquals(
            ReasonCode.OK,
            ScreenLifecycle.overlap(0, north, back, Arrays.asList(front), NO_INDEX, TestScreens.VISITOR, true).reason,
            "admins may");
        int[] behindTheWall = ScreenFormation.blockBox(1, 64, -1, BlockSide.NORTH, 2, 2);
        assertEquals(
            ReasonCode.OK,
            ScreenLifecycle
                .overlap(0, north, behindTheWall, Arrays.asList(front), NO_INDEX, TestScreens.VISITOR, false).reason,
            "a parallel wall of one's own blocks is fine");
    }

    @Test
    void sameSideScreensAreMergedAndUnloadedOnesStillCount() {
        ScreenState mine = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 2, 2, 0);
        int[] bigger = ScreenFormation.blockBox(0, 64, 0, BlockSide.SOUTH, 4, 3);
        ScreenLifecycle.Overlap merge = ScreenLifecycle
            .overlap(0, BlockSide.SOUTH.side(), bigger, Arrays.asList(mine), NO_INDEX, TestScreens.OWNER, false);
        assertEquals(Arrays.asList(mine), merge.sameSide);
        // A perpendicular screen whose origin chunk is unloaded is only in the index.
        ScreenState other = TestScreens.state(BlockSide.EAST, 3, 64, 0, 2, 2, 0);
        other.owner = TestScreens.FRIEND;
        other.ownerName = "Friend";
        ScreenLifecycle.Overlap refused = ScreenLifecycle.overlap(
            0,
            BlockSide.SOUTH.side(),
            bigger,
            Collections.<ScreenState>emptyList(),
            Arrays.asList(ScreenIndex.Entry.of(other)),
            TestScreens.OWNER,
            false);
        assertEquals(ReasonCode.SCREEN_OVERLAP, refused.reason);
        assertEquals("Friend", refused.ownerName);
    }

    private static final java.util.List<ScreenIndex.Entry> NO_INDEX = Collections.emptyList();

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
