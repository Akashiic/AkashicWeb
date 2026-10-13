package com.akashiic.web.client.screen;

/**
 * The mouse press the in-world pointer turned into a page press. Its release belongs to the same page wherever the
 * crosshair is by then (a drag that leaves the screen still ends), and only a release of a press we took is ours: a
 * release of a press vanilla saw must reach vanilla, or the attack or use key would stay down. Client thread only.
 * Pure; tested in PointerTest.
 */
final class PointerPress {

    private ClientScreen screen;
    private int mouseButton = -1;
    private double u;
    private double v;

    boolean active() {
        return screen != null;
    }

    void begin(ClientScreen s, int mouseButton, double u, double v) {
        this.screen = s;
        this.mouseButton = mouseButton;
        this.u = u;
        this.v = v;
    }

    /** True for the release event of the press we took. */
    boolean isReleaseOf(int button, boolean down) {
        return screen != null && !down && button == mouseButton;
    }

    /** The crosshair moved; true (and the position kept) while it is on the pressed page. */
    boolean follow(ClientScreens.Hit hit) {
        if (screen == null || hit == null || hit.screen != screen) return false;
        u = hit.u;
        v = hit.v;
        return true;
    }

    ClientScreen screen() {
        return screen;
    }

    int mouseButton() {
        return mouseButton;
    }

    double u() {
        return u;
    }

    double v() {
        return v;
    }

    void end() {
        screen = null;
        mouseButton = -1;
    }
}
