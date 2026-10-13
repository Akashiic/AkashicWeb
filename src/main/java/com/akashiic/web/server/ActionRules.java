package com.akashiic.web.server;

import java.util.UUID;

import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.Rights;
import com.akashiic.web.common.RightsPolicy;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.WebSettings;

/**
 * The order in which {@link ScreenActions} validates a client action, as pure functions over the player's position,
 * rights and claim, so the order itself is tested. The first refusal wins:
 * <ul>
 * <li>URL and navigation: reach, then rights, then the epoch. The stale-epoch answer tells the sender the current
 * URL, so it must only reach a player who could have changed it (review finding: any player in the dimension could
 * read any loaded screen's URL with a wrong epoch).</li>
 * <li>Taking control (D-016): rights and lock first ({@link RightsPolicy#canTakeControl}), then position, look and
 * line of sight ({@link ViewCheck#canSee}).</li>
 * </ul>
 */
public final class ActionRules {

    private ActionRules() {}

    /** CHANGE_URL, or the controller's own navigation (clicking a link is navigating) with CLICK_TYPE. */
    public static ReasonCode urlRight(ScreenState s, UUID player, boolean admin) {
        ReasonCode r = RightsPolicy.denial(s, player, admin, Rights.CHANGE_URL);
        if (r == ReasonCode.OK) return r;
        if (player != null && player.equals(s.controller) && RightsPolicy.can(s, player, admin, Rights.CLICK_TYPE)) {
            return ReasonCode.OK;
        }
        return r;
    }

    /** C2SSetUrl and C2SNav: TOO_FAR, a rights refusal, STALE_EPOCH, or OK. */
    public static ReasonCode urlChange(ScreenState s, UUID player, boolean admin, double[] eye, int claimedEpoch,
        double range) {
        ReasonCode r = ViewCheck.near(s, eye[0], eye[1], eye[2], range);
        if (r != ReasonCode.OK) return r;
        r = urlRight(s, player, admin);
        if (r != ReasonCode.OK) return r;
        return claimedEpoch == s.epoch ? ReasonCode.OK : ReasonCode.STALE_EPOCH;
    }

    /** C2SControl take, first take and keepalive alike. */
    public static ReasonCode takeControl(ScreenState s, UUID player, boolean admin, double[] eye, double[] look,
        WebSettings cfg, ViewCheck.Sight sight) {
        ReasonCode r = RightsPolicy.canTakeControl(s, player, admin);
        if (r != ReasonCode.OK) return r;
        return ViewCheck.canSee(s, eye, look, cfg.interactRange, cfg.controlMaxAngleDeg, sight);
    }
}
