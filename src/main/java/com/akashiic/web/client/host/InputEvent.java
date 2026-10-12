package com.akashiic.web.client.host;

/** INPUT message in page pixels (docs/PROTOCOL.md). Immutable. Contract type. */
public final class InputEvent {

    public enum Type {

        MOUSE_MOVE("mouseMove"),
        MOUSE_DOWN("mouseDown"),
        MOUSE_UP("mouseUp"),
        WHEEL("wheel"),
        KEY_DOWN("keyDown"),
        KEY_UP("keyUp"),
        CHAR("char");

        /** Wire name in the INPUT JSON. */
        public final String wire;

        Type(String wire) {
            this.wire = wire;
        }
    }

    public final Type type;
    public final int x;
    public final int y;
    /** left, middle or right. */
    public final String button;

    public final int clickCount;
    public final float deltaX;
    public final float deltaY;
    /** Electron accelerator-style key name for KEY_DOWN/KEY_UP. */
    public final String keyCode;
    /** Bitmask of ScreenInput.MOD_* flags. */
    public final int modifiers;

    public final String text;

    public InputEvent(Type type, int x, int y, String button, int clickCount, float deltaX, float deltaY,
        String keyCode, int modifiers, String text) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.button = button == null ? "left" : button;
        this.clickCount = clickCount;
        this.deltaX = deltaX;
        this.deltaY = deltaY;
        this.keyCode = keyCode == null ? "" : keyCode;
        this.modifiers = modifiers;
        this.text = text == null ? "" : text;
    }
}
