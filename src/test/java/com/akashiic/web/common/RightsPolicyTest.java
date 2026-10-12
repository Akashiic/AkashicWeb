package com.akashiic.web.common;

import static com.akashiic.web.common.TestScreens.FRIEND;
import static com.akashiic.web.common.TestScreens.OWNER;
import static com.akashiic.web.common.TestScreens.VISITOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RightsPolicyTest {

    private static ScreenState screen() {
        return TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 4, 3, 0);
    }

    @Test
    void defaultsLetVisitorsOnlyWatch() {
        assertEquals(0, Rights.DEFAULT_OTHERS);
        ScreenState s = screen();
        assertEquals(Rights.ALL, RightsPolicy.rights(s, OWNER, false));
        assertEquals(Rights.CHANGE_URL | Rights.CLICK_TYPE, RightsPolicy.rights(s, FRIEND, false));
        assertEquals(0, RightsPolicy.rights(s, VISITOR, false));
        assertEquals(0, RightsPolicy.rights(s, null, false));
        assertEquals(Rights.ALL, RightsPolicy.rights(s, VISITOR, true));
    }

    @Test
    void fullMatrixUnlockedAndLocked() {
        int[] all = { Rights.CHANGE_URL, Rights.CLICK_TYPE, Rights.MANAGE_FRIENDS, Rights.MANAGE_RIGHTS,
            Rights.CHANGE_RESOLUTION };
        ScreenState s = screen();
        s.friendRights = Rights.CHANGE_URL | Rights.MANAGE_FRIENDS;
        s.otherRights = Rights.CLICK_TYPE;
        for (int right : all) {
            assertTrue(RightsPolicy.can(s, OWNER, false, right));
            assertTrue(RightsPolicy.can(s, VISITOR, true, right));
            assertEquals((s.friendRights & right) != 0, RightsPolicy.can(s, FRIEND, false, right), "friend " + right);
            assertEquals((s.otherRights & right) != 0, RightsPolicy.can(s, VISITOR, false, right), "visitor " + right);
        }
        s.locked = true;
        for (int right : all) {
            assertFalse(RightsPolicy.can(s, OWNER, false, right), "locked owner");
            assertFalse(RightsPolicy.can(s, FRIEND, false, right), "locked friend");
            assertFalse(RightsPolicy.can(s, VISITOR, false, right), "locked visitor");
            assertTrue(RightsPolicy.can(s, VISITOR, true, right), "locked admin");
        }
    }

    @Test
    void denialSaysWhy() {
        ScreenState s = screen();
        assertEquals(ReasonCode.NO_PERMISSION, RightsPolicy.denial(s, VISITOR, false, Rights.CHANGE_URL));
        assertEquals(ReasonCode.OK, RightsPolicy.denial(s, FRIEND, false, Rights.CHANGE_URL));
        s.locked = true;
        assertEquals(ReasonCode.SCREEN_LOCKED, RightsPolicy.denial(s, OWNER, false, Rights.CHANGE_URL));
        assertEquals(ReasonCode.OK, RightsPolicy.denial(s, OWNER, true, Rights.CHANGE_URL));
    }

    @Test
    void controllerLock() {
        ScreenState s = screen();
        assertEquals(ReasonCode.NO_PERMISSION, RightsPolicy.canTakeControl(s, VISITOR, false));
        assertEquals(ReasonCode.OK, RightsPolicy.canTakeControl(s, FRIEND, false));
        s.controller = OWNER;
        assertEquals(ReasonCode.CONTROLLER_BUSY, RightsPolicy.canTakeControl(s, FRIEND, false));
        s.controller = FRIEND;
        assertEquals(ReasonCode.OK, RightsPolicy.canTakeControl(s, FRIEND, false)); // already holds it
        assertEquals(ReasonCode.OK, RightsPolicy.canTakeControl(s, OWNER, false)); // owner pre-empts
        assertEquals(ReasonCode.OK, RightsPolicy.canTakeControl(s, VISITOR, true)); // admin pre-empts
        s.mode = ScreenMode.PRIVATE;
        assertEquals(ReasonCode.WRONG_MODE, RightsPolicy.canTakeControl(s, FRIEND, false));
    }

    @Test
    void onlyTheControllersInputCountsOnSharedScreens() {
        ScreenState s = screen();
        s.controller = FRIEND;
        assertEquals(ReasonCode.OK, RightsPolicy.canInput(s, FRIEND, false));
        assertEquals(ReasonCode.NOT_CONTROLLER, RightsPolicy.canInput(s, OWNER, false));
        assertEquals(ReasonCode.NO_PERMISSION, RightsPolicy.canInput(s, VISITOR, false));
        s.mode = ScreenMode.CAST;
        assertEquals(ReasonCode.NOT_CONTROLLER, RightsPolicy.canInput(s, OWNER, false));
        s.mode = ScreenMode.PRIVATE;
        assertEquals(ReasonCode.OK, RightsPolicy.canInput(s, OWNER, false));
        s.locked = true;
        assertEquals(ReasonCode.SCREEN_LOCKED, RightsPolicy.canInput(s, FRIEND, false));
    }

    @Test
    void grantingIsBoundedByWhatTheManagerHolds() {
        assertEquals(Rights.CLICK_TYPE, RightsPolicy.grantable(Rights.CLICK_TYPE, Rights.ALL, true));
        assertEquals(0, RightsPolicy.grantable(Rights.MANAGE_RIGHTS, Rights.ALL, true)); // never for visitors
        assertEquals(Rights.MANAGE_RIGHTS, RightsPolicy.grantable(Rights.MANAGE_RIGHTS, Rights.ALL, false));
        assertEquals(Rights.CHANGE_URL, RightsPolicy.grantable(Rights.ALL, Rights.CHANGE_URL, false));
        assertEquals(0, RightsPolicy.grantable(0xFF00, Rights.ALL, false)); // unknown bits dropped
    }

    @Test
    void roles() {
        ScreenState s = screen();
        assertEquals(RightsPolicy.Role.OWNER, RightsPolicy.role(s, OWNER, false));
        assertEquals(RightsPolicy.Role.FRIEND, RightsPolicy.role(s, FRIEND, false));
        assertEquals(RightsPolicy.Role.VISITOR, RightsPolicy.role(s, VISITOR, false));
        assertEquals(RightsPolicy.Role.ADMIN, RightsPolicy.role(s, VISITOR, true));
    }
}
