package com.akashiic.web.client.screen;

import java.util.UUID;

import com.akashiic.web.common.Rights;
import com.akashiic.web.common.ScreenState;

/**
 * The client's guess at what a player may do on a screen, for UX only (which buttons are enabled, whether a click is
 * intercepted). The server decides and audits every action (ADR-010); a wrong guess here costs a rejected packet, never
 * a security hole. Pure; tested in LocalRightsTest.
 */
public final class LocalRights {

    private LocalRights() {}

    public static int rightsFor(ScreenState s, UUID player) {
        if (s == null || player == null) return 0;
        if (player.equals(s.owner)) return Rights.ALL;
        if (s.locked) return 0; // an admin lock freezes everyone but the owner (UX guess; the server decides)
        if (s.friends.contains(player)) return s.friendRights;
        return s.otherRights;
    }

    public static boolean can(ScreenState s, UUID player, int right) {
        return Rights.has(rightsFor(s, player), right);
    }

    public static boolean isController(ScreenState s, UUID player) {
        return s != null && player != null && player.equals(s.controller);
    }
}
