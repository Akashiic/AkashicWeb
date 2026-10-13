package com.akashiic.web.client.screen;

import com.akashiic.web.client.gui.KeyMapping;
import com.akashiic.web.client.host.InputEvent;
import com.akashiic.web.common.net.ScreenInput;

/**
 * Server-relayed input (S2CInput, face coordinates and DOM key names) to the host's INPUT message (page pixels and
 * Electron key codes). Applied only on the controller's own client: the server relays raw input to nobody else
 * (ADR-012 reviewer amendment). The relay is untrusted data: anything out of range or unknown is dropped. Pure; tested
 * in InputTranslatorTest.
 */
public final class InputTranslator {

    /**
     * Pixels per wheel notch. ScreenInput.wheelY counts notches with LWJGL's sign (positive = wheel pushed away =
     * scroll
     * up); Electron's mouseWheel uses the same sign, so the value passes through scaled.
     */
    public static final float WHEEL_PIXELS_PER_NOTCH = 100f;
    /** A flood of notches in one event is not a real wheel. */
    static final float MAX_NOTCHES = 20f;

    static final int MAX_TEXT = 512;

    private InputTranslator() {}

    /** Null when the input cannot be applied (bad coordinates, unknown key, empty text). */
    public static InputEvent toHost(ScreenInput in, int pageW, int pageH) {
        if (in == null || pageW <= 0 || pageH <= 0) return null;
        switch (in.kind) {
            case MOVE:
            case DOWN:
            case UP:
                return pointer(in, pageW, pageH);
            case WHEEL: {
                int[] p = pixel(in.u, in.v, pageW, pageH);
                if (p == null || !finite(in.wheelX) || !finite(in.wheelY)) return null;
                return new InputEvent(
                    InputEvent.Type.WHEEL,
                    p[0],
                    p[1],
                    null,
                    0,
                    clampNotches(in.wheelX) * WHEEL_PIXELS_PER_NOTCH,
                    clampNotches(in.wheelY) * WHEEL_PIXELS_PER_NOTCH,
                    null,
                    in.modifiers,
                    null);
            }
            case KEY_DOWN:
            case KEY_UP: {
                String code = KeyMapping.electronKeyCode(in.key);
                if (code == null) return null;
                InputEvent.Type t = in.kind == ScreenInput.Kind.KEY_DOWN ? InputEvent.Type.KEY_DOWN
                    : InputEvent.Type.KEY_UP;
                return new InputEvent(t, 0, 0, null, 0, 0, 0, code, in.modifiers, null);
            }
            case CHAR: {
                if (in.text.isEmpty() || in.text.length() > MAX_TEXT) return null;
                return new InputEvent(InputEvent.Type.CHAR, 0, 0, null, 0, 0, 0, null, 0, in.text);
            }
            default:
                return null;
        }
    }

    private static InputEvent pointer(ScreenInput in, int pageW, int pageH) {
        int[] p = pixel(in.u, in.v, pageW, pageH);
        String button = buttonName(in.button);
        if (p == null || button == null) return null;
        InputEvent.Type t = in.kind == ScreenInput.Kind.MOVE ? InputEvent.Type.MOUSE_MOVE
            : in.kind == ScreenInput.Kind.DOWN ? InputEvent.Type.MOUSE_DOWN : InputEvent.Type.MOUSE_UP;
        int clicks = Math.max(1, Math.min(3, in.clickCount));
        return new InputEvent(t, p[0], p[1], button, clicks, 0, 0, null, in.modifiers, null);
    }

    /** Face (u, v) in 0..1 to a pixel inside the page; null for NaN or far outside (a forged relay). */
    static int[] pixel(float u, float v, int pageW, int pageH) {
        if (!finite(u) || !finite(v) || u < -0.01f || u > 1.01f || v < -0.01f || v > 1.01f) return null;
        int x = (int) Math.floor(u * pageW);
        int y = (int) Math.floor(v * pageH);
        return new int[] { Math.max(0, Math.min(pageW - 1, x)), Math.max(0, Math.min(pageH - 1, y)) };
    }

    static String buttonName(int button) {
        switch (button) {
            case ScreenInput.BUTTON_LEFT:
                return "left";
            case ScreenInput.BUTTON_RIGHT:
                return "right";
            case ScreenInput.BUTTON_MIDDLE:
                return "middle";
            default:
                return null;
        }
    }

    private static float clampNotches(float n) {
        return Math.max(-MAX_NOTCHES, Math.min(MAX_NOTCHES, n));
    }

    private static boolean finite(float f) {
        return !Float.isNaN(f) && !Float.isInfinite(f);
    }
}
