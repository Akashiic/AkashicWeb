package com.akashiic.web.server;

import static com.akashiic.web.common.TestScreens.FRIEND;
import static com.akashiic.web.common.TestScreens.OWNER;
import static com.akashiic.web.common.TestScreens.VISITOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.Rights;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.TestScreens;
import com.akashiic.web.common.WebSettings;
import com.akashiic.web.common.net.RightsChange;

class RightsEditorTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Function<String, RightsEditor.Profile> PROFILES = name -> name.equalsIgnoreCase("alice")
        ? new RightsEditor.Profile(ALICE, "Alice")
        : name.equalsIgnoreCase("owner") ? new RightsEditor.Profile(OWNER, "Owner") : null;
    private final WebSettings cfg = WebSettings.defaults();

    private static ScreenState screen() {
        return TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 4, 3, 0);
    }

    @Test
    void ownerManagesFriends() {
        ScreenState s = screen();
        RightsChange c = new RightsChange();
        c.addFriends.add("alice");
        c.removeFriends.add("Friend");
        RightsEditor.Outcome o = RightsEditor.apply(s, c, OWNER, false, cfg, PROFILES);
        assertEquals(ReasonCode.OK, o.reason);
        assertTrue(o.changed);
        assertTrue(s.friends.contains(ALICE));
        assertEquals("Alice", s.friendNames.get(ALICE));
        assertFalse(s.friends.contains(FRIEND));
        assertTrue(o.audit.contains("+friend Alice"));
    }

    @Test
    void allOrNothing() {
        ScreenState s = screen();
        RightsChange c = new RightsChange();
        c.addFriends.add("alice");
        c.addFriends.add("nobody");
        c.otherRights = Rights.CLICK_TYPE;
        RightsEditor.Outcome o = RightsEditor.apply(s, c, OWNER, false, cfg, PROFILES);
        assertEquals(ReasonCode.UNKNOWN_PLAYER, o.reason);
        assertEquals("nobody", o.detail);
        assertFalse(s.friends.contains(ALICE), "nothing applied");
        assertEquals(0, s.otherRights);
    }

    @Test
    void eachPartNeedsItsRight() {
        RightsChange friends = new RightsChange();
        friends.addFriends.add("alice");
        assertEquals(
            ReasonCode.NO_PERMISSION,
            RightsEditor.apply(screen(), friends, FRIEND, false, cfg, PROFILES).reason);
        assertEquals(
            ReasonCode.NO_PERMISSION,
            RightsEditor.apply(screen(), friends, VISITOR, false, cfg, PROFILES).reason);
        RightsChange res = new RightsChange();
        res.resolutionW = 800;
        assertEquals(ReasonCode.NO_PERMISSION, RightsEditor.apply(screen(), res, FRIEND, false, cfg, PROFILES).reason);
        RightsChange lock = new RightsChange();
        lock.locked = 1;
        assertEquals(ReasonCode.NO_PERMISSION, RightsEditor.apply(screen(), lock, OWNER, false, cfg, PROFILES).reason);
        ScreenState s = screen();
        assertEquals(ReasonCode.OK, RightsEditor.apply(s, lock, VISITOR, true, cfg, PROFILES).reason);
        assertTrue(s.locked);
    }

    @Test
    void lockedScreensRefuseTheOwner() {
        ScreenState s = screen();
        s.locked = true;
        RightsChange c = new RightsChange();
        c.otherRights = Rights.CLICK_TYPE;
        assertEquals(ReasonCode.SCREEN_LOCKED, RightsEditor.apply(s, c, OWNER, false, cfg, PROFILES).reason);
    }

    @Test
    void visitorsNeverGetManagementRights() {
        RightsChange c = new RightsChange();
        c.otherRights = Rights.CLICK_TYPE | Rights.MANAGE_RIGHTS;
        assertEquals(ReasonCode.NO_PERMISSION, RightsEditor.apply(screen(), c, OWNER, false, cfg, PROFILES).reason);
        RightsChange ok = new RightsChange();
        ok.friendRights = Rights.ALL;
        assertEquals(ReasonCode.OK, RightsEditor.apply(screen(), ok, OWNER, false, cfg, PROFILES).reason);
    }

    @Test
    void aManagerCannotGrantMoreThanTheyHold() {
        ScreenState s = screen();
        s.friendRights = Rights.MANAGE_RIGHTS | Rights.CLICK_TYPE;
        RightsChange c = new RightsChange();
        c.otherRights = Rights.CHANGE_URL; // the friend does not hold CHANGE_URL
        assertEquals(ReasonCode.NO_PERMISSION, RightsEditor.apply(s, c, FRIEND, false, cfg, PROFILES).reason);
        c.otherRights = Rights.CLICK_TYPE;
        assertEquals(ReasonCode.OK, RightsEditor.apply(s, c, FRIEND, false, cfg, PROFILES).reason);
    }

    @Test
    void rotationToPortraitSwapsThePageSize() {
        ScreenState s = screen();
        RightsChange c = new RightsChange();
        c.rotation = 90;
        RightsEditor.Outcome o = RightsEditor.apply(s, c, OWNER, false, cfg, PROFILES);
        assertTrue(o.geometryChanged);
        assertEquals(90, s.rotation);
        assertEquals(720, s.resolutionW);
        assertEquals(1280, s.resolutionH);
    }

    @Test
    void resolutionLimits() {
        RightsChange c = new RightsChange();
        c.resolutionW = 4000;
        c.resolutionH = 4000;
        RightsEditor.Outcome o = RightsEditor.apply(screen(), c, OWNER, false, cfg, PROFILES);
        assertEquals(ReasonCode.BAD_RESOLUTION, o.reason);
        assertEquals("4000x4000", o.detail);
    }

    @Test
    void friendLimitAndOwnerIsNeverAFriend() {
        WebSettings.Builder b = new WebSettings.Builder();
        b.maxFriends = 1;
        WebSettings one = b.build();
        RightsChange c = new RightsChange();
        c.addFriends.add("alice");
        assertEquals(ReasonCode.TOO_MANY_FRIENDS, RightsEditor.apply(screen(), c, OWNER, false, one, PROFILES).reason);
        RightsChange self = new RightsChange();
        self.addFriends.add("owner");
        ScreenState s = screen();
        RightsEditor.Outcome o = RightsEditor.apply(s, self, OWNER, false, cfg, PROFILES);
        assertEquals(ReasonCode.OK, o.reason);
        assertFalse(s.friends.contains(OWNER));
        assertFalse(o.changed);
    }

    /**
     * Review finding (high): every friend name cost a blocking Mojang lookup on the main thread, before any limit was
     * checked. Names are now resolved only after the rights checks, never for names already on the list, and not at
     * all when a full list only grows.
     */
    @Test
    void namesAreLookedUpOnlyWhenNeeded() {
        int[] calls = { 0 };
        Function<String, RightsEditor.Profile> counting = name -> {
            calls[0]++;
            return PROFILES.apply(name);
        };
        RightsChange four = new RightsChange();
        for (String n : new String[] { "alice", "bob", "carol", "dave" }) four.addFriends.add(n);
        assertEquals(
            ReasonCode.NO_PERMISSION,
            RightsEditor.apply(screen(), four, VISITOR, false, cfg, counting).reason);
        assertEquals(0, calls[0], "no lookup for a player without the right");

        WebSettings.Builder b = new WebSettings.Builder();
        b.maxFriends = 1; // the test screen already has one friend
        RightsEditor.Outcome full = RightsEditor.apply(screen(), four, OWNER, false, b.build(), counting);
        assertEquals(ReasonCode.TOO_MANY_FRIENDS, full.reason);
        assertEquals(0, calls[0], "a full list that only grows is refused before any lookup");

        RightsChange again = new RightsChange();
        again.addFriends.add("FRIEND");
        assertEquals(ReasonCode.OK, RightsEditor.apply(screen(), again, OWNER, false, b.build(), counting).reason);
        assertEquals(0, calls[0], "a name already on the list needs no lookup");

        RightsChange one = new RightsChange();
        one.addFriends.add("alice");
        assertEquals(ReasonCode.OK, RightsEditor.apply(screen(), one, OWNER, false, cfg, counting).reason);
        assertEquals(1, calls[0]);
    }

    /** After maxFriends is lowered below a screen's list, its owner can still shrink the list. */
    @Test
    void removingFriendsIsAllowedOnAnOverfullList() {
        ScreenState s = screen();
        s.friends.add(ALICE);
        s.friendNames.put(ALICE, "Alice");
        WebSettings.Builder b = new WebSettings.Builder();
        b.maxFriends = 0;
        RightsChange c = new RightsChange();
        c.removeFriends.add("alice");
        RightsEditor.Outcome o = RightsEditor.apply(s, c, OWNER, false, b.build(), PROFILES);
        assertEquals(ReasonCode.OK, o.reason);
        assertFalse(s.friends.contains(ALICE));
    }
}
