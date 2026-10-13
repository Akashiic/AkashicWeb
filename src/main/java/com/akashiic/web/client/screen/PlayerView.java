package com.akashiic.web.client.screen;

import java.util.UUID;

/**
 * Snapshot of the local player taken once per client tick by the Minecraft adapter, so {@link ClientScreens} never
 * touches Minecraft objects and can be tested headless. Immutable.
 */
public final class PlayerView {

    public final int dimension;
    public final double eyeX;
    public final double eyeY;
    public final double eyeZ;
    public final double lookX;
    public final double lookY;
    public final double lookZ;
    /** The local player's UUID as Minecraft knows it (see the M5 report about offline-mode proxies). */
    public final UUID player;
    /** A pause/options/main menu is open: hard mute (ADR-007). */
    public final boolean menuOpen;

    public final float masterLevel;
    public final float recordsLevel;

    public PlayerView(int dimension, double eyeX, double eyeY, double eyeZ, double lookX, double lookY, double lookZ,
        UUID player, boolean menuOpen, float masterLevel, float recordsLevel) {
        this.dimension = dimension;
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.lookX = lookX;
        this.lookY = lookY;
        this.lookZ = lookZ;
        this.player = player;
        this.menuOpen = menuOpen;
        this.masterLevel = masterLevel;
        this.recordsLevel = recordsLevel;
    }
}
