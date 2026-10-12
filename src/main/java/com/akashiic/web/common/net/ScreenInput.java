package com.akashiic.web.common.net;

/**
 * One input gesture on a screen, in face coordinates (u, v in 0..1 from the top-left of the page). Carried by
 * C2SPointer / C2SKey and relayed back in S2CInput. The client converts to page pixels. Contract type.
 */
public final class ScreenInput {

    public enum Kind {
        MOVE,
        DOWN,
        UP,
        WHEEL,
        KEY_DOWN,
        KEY_UP,
        CHAR
    }

    public static final int BUTTON_LEFT = 0;
    public static final int BUTTON_RIGHT = 1;
    public static final int BUTTON_MIDDLE = 2;

    public static final int MOD_SHIFT = 1;
    public static final int MOD_CTRL = 2;
    public static final int MOD_ALT = 4;
    public static final int MOD_META = 8;

    public final Kind kind;
    public final float u;
    public final float v;
    public final int button;
    public final int clickCount;
    public final float wheelX;
    public final float wheelY;
    /** DOM key name for KEY_DOWN/KEY_UP (e.g. "Enter", "ArrowLeft", "a"). */
    public final String key;
    /** Composed text for CHAR (may hold accents produced by ABNT2 dead keys). */
    public final String text;

    public final int modifiers;

    private ScreenInput(Kind kind, float u, float v, int button, int clickCount, float wheelX, float wheelY, String key,
        String text, int modifiers) {
        this.kind = kind;
        this.u = u;
        this.v = v;
        this.button = button;
        this.clickCount = clickCount;
        this.wheelX = wheelX;
        this.wheelY = wheelY;
        this.key = key == null ? "" : key;
        this.text = text == null ? "" : text;
        this.modifiers = modifiers;
    }

    public static ScreenInput pointer(Kind kind, float u, float v, int button, int clickCount, int modifiers) {
        if (kind != Kind.MOVE && kind != Kind.DOWN && kind != Kind.UP) throw new IllegalArgumentException(kind.name());
        return new ScreenInput(kind, u, v, button, clickCount, 0, 0, null, null, modifiers);
    }

    public static ScreenInput wheel(float u, float v, float dx, float dy, int modifiers) {
        return new ScreenInput(Kind.WHEEL, u, v, 0, 0, dx, dy, null, null, modifiers);
    }

    public static ScreenInput key(boolean down, String key, int modifiers) {
        return new ScreenInput(down ? Kind.KEY_DOWN : Kind.KEY_UP, 0, 0, 0, 0, 0, 0, key, null, modifiers);
    }

    public static ScreenInput chars(String text) {
        return new ScreenInput(Kind.CHAR, 0, 0, 0, 0, 0, 0, null, text, 0);
    }

    public boolean isPointer() {
        return kind == Kind.MOVE || kind == Kind.DOWN || kind == Kind.UP || kind == Kind.WHEEL;
    }
}
