package com.akashiic.web.server;

import static com.akashiic.web.common.TestScreens.FRIEND;
import static com.akashiic.web.common.TestScreens.OWNER;
import static com.akashiic.web.common.TestScreens.VISITOR;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.Rights;
import com.akashiic.web.common.ScreenMode;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.TestScreens;
import com.akashiic.web.common.WebSettings;

/** Validation order of client actions (review findings: epoch before reach, and the controller lock gaps). */
class ActionRulesTest {

    private static final ViewCheck.Sight CLEAR = (ax, ay, az, bx, by, bz) -> true;
    private static final ViewCheck.Sight WALL = (ax, ay, az, bx, by, bz) -> false;
    private static final WebSettings CFG = WebSettings.defaults();
    /** SOUTH face over x 0..4, y 64..67 at z = 1 (see ViewCheckTest). */
    private static final double[] FRONT = { 2, 65.5, 6 };
    private static final double[] FAR = { 2, 65.5, 200 };
    private static final double[] LOOK = { 0, 0, -1 };

    private static ScreenState screen() {
        ScreenState s = TestScreens.state(BlockSide.SOUTH, 0, 64, 0, 4, 3, 0);
        s.epoch = 7;
        return s;
    }

    @Test
    void aStaleEpochIsOnlyAnsweredToPlayersWhoCouldChangeTheUrl() {
        ScreenState s = screen();
        // The stale-epoch answer carries the current URL: a far player or one without rights must not get it.
        assertEquals(ReasonCode.TOO_FAR, ActionRules.urlChange(s, OWNER, false, FAR, 6, CFG.interactRange));
        assertEquals(ReasonCode.NO_PERMISSION, ActionRules.urlChange(s, VISITOR, false, FRONT, 6, CFG.interactRange));
        s.locked = true;
        assertEquals(ReasonCode.SCREEN_LOCKED, ActionRules.urlChange(s, OWNER, false, FRONT, 6, CFG.interactRange));
        s.locked = false;
        assertEquals(ReasonCode.STALE_EPOCH, ActionRules.urlChange(s, OWNER, false, FRONT, 6, CFG.interactRange));
        assertEquals(ReasonCode.OK, ActionRules.urlChange(s, OWNER, false, FRONT, 7, CFG.interactRange));
    }

    @Test
    void theControllerNavigatesWithClickRightsAlone() {
        ScreenState s = screen();
        s.friendRights = Rights.CLICK_TYPE;
        assertEquals(ReasonCode.NO_PERMISSION, ActionRules.urlRight(s, FRIEND, false));
        s.controller = FRIEND;
        assertEquals(ReasonCode.OK, ActionRules.urlRight(s, FRIEND, false), "clicking a link is navigating");
    }

    @Test
    void takingControlNeedsRightsThenSight() {
        ScreenState s = screen();
        assertEquals(ReasonCode.OK, ActionRules.takeControl(s, FRIEND, false, FRONT, LOOK, CFG, CLEAR));
        assertEquals(
            ReasonCode.NO_PERMISSION,
            ActionRules.takeControl(s, VISITOR, false, FRONT, LOOK, CFG, CLEAR),
            "visitors only watch by default");
        assertEquals(
            ReasonCode.OUT_OF_VIEW,
            ActionRules.takeControl(s, FRIEND, false, FRONT, LOOK, CFG, WALL),
            "through a wall");
        assertEquals(
            ReasonCode.OUT_OF_VIEW,
            ActionRules
                .takeControl(s, FRIEND, false, new double[] { 2, 65.5, -4 }, new double[] { 0, 0, 1 }, CFG, CLEAR),
            "from behind the wall that carries the screen");
        assertEquals(ReasonCode.TOO_FAR, ActionRules.takeControl(s, FRIEND, false, FAR, LOOK, CFG, CLEAR));
        assertEquals(
            ReasonCode.CONTROLLER_BUSY,
            ActionRules.takeControl(screenHeldBy(), FRIEND, false, FRONT, LOOK, CFG, CLEAR));
        assertEquals(
            ReasonCode.OK,
            ActionRules.takeControl(screenHeldBy(), OWNER, false, FRONT, LOOK, CFG, CLEAR),
            "the owner may pre-empt");
        ScreenState locked = screen();
        locked.locked = true;
        assertEquals(ReasonCode.SCREEN_LOCKED, ActionRules.takeControl(locked, OWNER, false, FRONT, LOOK, CFG, CLEAR));
        assertEquals(ReasonCode.OK, ActionRules.takeControl(locked, VISITOR, true, FRONT, LOOK, CFG, CLEAR), "admin");
        ScreenState own = screen();
        own.mode = ScreenMode.PRIVATE;
        assertEquals(ReasonCode.WRONG_MODE, ActionRules.takeControl(own, OWNER, false, FRONT, LOOK, CFG, CLEAR));
    }

    /** A screen whose lock is held by someone other than FRIEND. */
    private static ScreenState screenHeldBy() {
        ScreenState s = screen();
        s.controller = java.util.UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        s.friends.add(s.controller);
        return s;
    }
}
