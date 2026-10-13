package com.akashiic.web.common;

import java.util.UUID;

/**
 * Who may do what on a screen (ADR-010, ADR-013). Pure: the caller decides whether the player is an admin (op,
 * Bukkit permission or config list) and has already dropped FakePlayers.
 * <ul>
 * <li>Admins hold every right, on locked screens too, and may pre-empt any controller.</li>
 * <li>A locked screen is admin-only: owner, friends and visitors get no rights at all.</li>
 * <li>Owner: every right. Friends: {@link ScreenState#friendRights}. Visitors: {@link ScreenState#otherRights}
 * (default {@link Rights#DEFAULT_OTHERS} = 0, so visitors watch only).</li>
 * </ul>
 * Rights bits derived from Web Displays' ScreenRights (montoyo, public domain).
 */
public final class RightsPolicy {

    private RightsPolicy() {}

    public enum Role {
        ADMIN,
        OWNER,
        FRIEND,
        VISITOR
    }

    public static Role role(ScreenState s, UUID player, boolean admin) {
        if (admin) return Role.ADMIN;
        if (player == null) return Role.VISITOR;
        if (player.equals(s.owner)) return Role.OWNER;
        if (s.friends.contains(player)) return Role.FRIEND;
        return Role.VISITOR;
    }

    /** Effective rights bits of a player on a screen. */
    public static int rights(ScreenState s, UUID player, boolean admin) {
        Role role = role(s, player, admin);
        if (role == Role.ADMIN) return Rights.ALL;
        if (s.locked) return 0;
        switch (role) {
            case OWNER:
                return Rights.ALL;
            case FRIEND:
                return s.friendRights & Rights.ALL;
            default:
                return s.otherRights & Rights.ALL;
        }
    }

    public static boolean can(ScreenState s, UUID player, boolean admin, int right) {
        return Rights.has(rights(s, player, admin), right);
    }

    /** The reason to show when a right is missing: a locked screen says so, everything else is NO_PERMISSION. */
    public static ReasonCode denial(ScreenState s, UUID player, boolean admin, int right) {
        if (can(s, player, admin, right)) return ReasonCode.OK;
        return s.locked && !admin ? ReasonCode.SCREEN_LOCKED : ReasonCode.NO_PERMISSION;
    }

    /**
     * Whether a player may take the controller lock now (ADR-012: one controller at a time). Needs CLICK_TYPE. A free
     * lock (or one the player already holds) is granted; a held lock is pre-empted only by the owner or an admin.
     * PRIVATE screens have no controller.
     */
    public static ReasonCode canTakeControl(ScreenState s, UUID player, boolean admin) {
        if (s.mode == ScreenMode.PRIVATE) return ReasonCode.WRONG_MODE;
        ReasonCode r = denial(s, player, admin, Rights.CLICK_TYPE);
        if (r != ReasonCode.OK) return r;
        if (s.controller == null || s.controller.equals(player)) return ReasonCode.OK;
        Role role = role(s, player, admin);
        return role == Role.ADMIN || role == Role.OWNER ? ReasonCode.OK : ReasonCode.CONTROLLER_BUSY;
    }

    /**
     * Whether a pointer/key packet from this player is accepted. On SHARED and CAST screens only the controller's
     * input counts; on PRIVATE screens anyone with CLICK_TYPE drives their own browser.
     */
    public static ReasonCode canInput(ScreenState s, UUID player, boolean admin) {
        ReasonCode r = denial(s, player, admin, Rights.CLICK_TYPE);
        if (r != ReasonCode.OK) return r;
        if (s.mode == ScreenMode.PRIVATE) return ReasonCode.OK;
        return player != null && player.equals(s.controller) ? ReasonCode.OK : ReasonCode.NOT_CONTROLLER;
    }

    /**
     * Rights bits a manager may hand out. Only rights the manager holds can be granted, and MANAGE_RIGHTS is never
     * given to visitors (that would let any passer-by rewrite the screen's rights).
     */
    public static int grantable(int requested, int managerRights, boolean forVisitors) {
        int bits = requested & Rights.ALL & managerRights;
        if (forVisitors) bits &= ~(Rights.MANAGE_RIGHTS | Rights.MANAGE_FRIENDS);
        return bits;
    }
}
