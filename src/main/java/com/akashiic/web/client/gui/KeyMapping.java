package com.akashiic.web.client.gui;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Explicit key tables for screen typing. Only NAMED keys (Enter, arrows, F-keys…) travel as key events; everything a
 * player types travels as text, so ABNT2 dead keys, AltGr and any layout work without a layout table (the OS composes
 * "á", "ç", "/" and hands LWJGL the final character).
 * <ul>
 * <li>{@link #domName}: LWJGL 2 key code to a DOM {@code KeyboardEvent.key} name (wire form in C2SKey).</li>
 * <li>{@link #electronKeyCode}: DOM name to the Electron accelerator name the host's sendInputEvent expects.</li>
 * </ul>
 * Modifier keys (Shift, Control, Alt, Meta) are deliberately absent: they only travel as modifier flags. A standalone
 * Alt from AltGr would fire page shortcuts on every "/" typed on ABNT2. Pure; tested in KeyMappingTest.
 */
public final class KeyMapping {

    // LWJGL 2 org.lwjgl.input.Keyboard codes (asserted equal in KeyMappingTest; copied so this class stays pure).
    public static final int KEY_ESCAPE = 1;
    public static final int KEY_BACK = 14;
    public static final int KEY_TAB = 15;
    public static final int KEY_Y = 21;
    public static final int KEY_RETURN = 28;
    public static final int KEY_A = 30;
    public static final int KEY_Z = 44;
    public static final int KEY_X = 45;
    public static final int KEY_C = 46;
    public static final int KEY_V = 47;
    public static final int KEY_SPACE = 57;
    public static final int KEY_F1 = 59;
    public static final int KEY_F10 = 68;
    public static final int KEY_F11 = 87;
    public static final int KEY_F12 = 88;
    public static final int KEY_NUMPADENTER = 156;
    public static final int KEY_HOME = 199;
    public static final int KEY_UP = 200;
    public static final int KEY_PRIOR = 201;
    public static final int KEY_LEFT = 203;
    public static final int KEY_RIGHT = 205;
    public static final int KEY_END = 207;
    public static final int KEY_DOWN = 208;
    public static final int KEY_NEXT = 209;
    public static final int KEY_INSERT = 210;
    public static final int KEY_DELETE = 211;

    private static final Map<Integer, String> LWJGL_TO_DOM = new HashMap<>();
    private static final Map<String, String> DOM_TO_ELECTRON = new HashMap<>();
    /** Named keys that also produce text, sent as a CHAR right after the key-down (Chromium inserts on the char). */
    private static final Map<String, String> TEXT_OF = new HashMap<>();
    /** Ctrl shortcuts relayed as key events. Copy and cut are never relayed; paste is turned into text. */
    private static final Map<Integer, String> SHORTCUT_LETTERS = new HashMap<>();

    static {
        named(KEY_RETURN, "Enter", "Enter");
        named(KEY_NUMPADENTER, "Enter", "Enter");
        named(KEY_BACK, "Backspace", "Backspace");
        named(KEY_TAB, "Tab", "Tab");
        named(KEY_ESCAPE, "Escape", "Escape");
        named(KEY_SPACE, " ", "Space");
        named(KEY_DELETE, "Delete", "Delete");
        named(KEY_INSERT, "Insert", "Insert");
        named(KEY_HOME, "Home", "Home");
        named(KEY_END, "End", "End");
        named(KEY_PRIOR, "PageUp", "PageUp");
        named(KEY_NEXT, "PageDown", "PageDown");
        named(KEY_LEFT, "ArrowLeft", "Left");
        named(KEY_RIGHT, "ArrowRight", "Right");
        named(KEY_UP, "ArrowUp", "Up");
        named(KEY_DOWN, "ArrowDown", "Down");
        for (int i = 0; i < 10; i++) named(KEY_F1 + i, "F" + (i + 1), "F" + (i + 1));
        named(KEY_F11, "F11", "F11");
        named(KEY_F12, "F12", "F12");
        TEXT_OF.put("Enter", "\r");
        TEXT_OF.put(" ", " ");
        SHORTCUT_LETTERS.put(KEY_A, "a"); // select all
        SHORTCUT_LETTERS.put(KEY_Z, "z"); // undo
        SHORTCUT_LETTERS.put(KEY_Y, "y"); // redo
        for (String letter : SHORTCUT_LETTERS.values()) DOM_TO_ELECTRON.put(letter, letter);
    }

    private KeyMapping() {}

    private static void named(int lwjgl, String dom, String electron) {
        LWJGL_TO_DOM.put(lwjgl, dom);
        DOM_TO_ELECTRON.put(dom, electron);
    }

    /** DOM key name of a named key, or null for character keys and modifiers. */
    public static String domName(int lwjglKey) {
        return LWJGL_TO_DOM.get(lwjglKey);
    }

    /** Electron accelerator key code for a DOM key name from the wire; null when we never send that key. */
    public static String electronKeyCode(String domName) {
        if (domName == null) return null;
        // Wire input is validated: only names from our own tables pass (letters case-insensitively).
        String e = DOM_TO_ELECTRON.get(domName);
        if (e == null && domName.length() == 1) e = DOM_TO_ELECTRON.get(domName.toLowerCase(Locale.ROOT));
        return e;
    }

    /** Text a named key types (Enter, Space), or null. */
    public static String textOf(String domName) {
        return domName == null ? null : TEXT_OF.get(domName);
    }

    /** Letter of a relayed Ctrl shortcut (select all, undo, redo), or null. */
    public static String shortcutLetter(int lwjglKey) {
        return SHORTCUT_LETTERS.get(lwjglKey);
    }

    /** A character a page may receive as text: no C0/C1 controls, no lone surrogates, no noncharacters. */
    public static boolean isTypable(char c) {
        if (c == 0 || Character.isISOControl(c)) return false;
        if (Character.isSurrogate(c)) return false;
        return c != '￾' && c != '￿';
    }
}
