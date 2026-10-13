package com.akashiic.web.client.screen;

import java.util.UUID;

import com.akashiic.web.common.Rights;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.TextSanitizer;

/**
 * The client's guess at what a player may do on a screen, for UX only (which buttons are enabled, whether a click is
 * intercepted). The server decides and audits every action (ADR-010); a wrong guess here costs a rejected packet, never
 * a security hole. The player is always the server-side UUID from S2CHello ({@code ClientScreens.player()}): on
 * offline-mode or proxied servers the session UUID differs, and the 2026-10-13 dev-client run showed an owner with no
 * rights on their own screen because of it. Pure; tested in LocalRightsTest.
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

    /**
     * A printable name for the screen's controller: {@code ScreenState.controllerName} (sent by the server since
     * D-016), else the owner's name when the owner controls, else a UUID prefix that still tells players apart; ""
     * when nobody controls. Cleaned of formatting codes: it is drawn in GUIs.
     */
    public static String controllerDisplayName(ScreenState s) {
        if (s == null || s.controller == null) return "";
        String name = TextSanitizer.clean(s.controllerName, NAME_MAX);
        if (!name.isEmpty()) return name;
        if (s.controller.equals(s.owner)) {
            name = TextSanitizer.clean(s.ownerName, NAME_MAX);
            if (!name.isEmpty()) return name;
        }
        return s.controller.toString()
            .substring(0, 8);
    }

    private static final int NAME_MAX = 32;
}
