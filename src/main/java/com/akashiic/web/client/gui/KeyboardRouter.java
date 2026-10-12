package com.akashiic.web.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.akashiic.web.common.net.ScreenInput;

/**
 * Turns LWJGL 2 keyboard events from the keyboard GUI into screen input (C2SKey payloads):
 * <ul>
 * <li>named keys: key-down/key-up with modifier flags, plus the text Enter/Space type;</li>
 * <li>typed characters: text only, as the OS composed it (ABNT2 dead key + letter arrives as one "á"; the dead key
 * itself has no character and sends nothing);</li>
 * <li>Ctrl+V: paste from the clipboard as text; Ctrl+A/Z/Y relayed; Ctrl+C and Ctrl+X never relayed (a shared screen
 * must not copy into anyone's clipboard);</li>
 * <li>AltGr (Ctrl+Alt on Windows) is never a shortcut, so "/" and "?" on ABNT2 type normally;</li>
 * <li>Escape closes the GUI.</li>
 * </ul>
 * One instance per GUI (keeps a pending high surrogate). Pure; tested in KeyboardRouterTest.
 */
public final class KeyboardRouter {

    /** Clipboard text is capped; the server applies its own length cap too. */
    public static final int MAX_PASTE_CHARS = 512;

    public enum Action {
        NONE,
        SEND,
        PASTE,
        CLOSE
    }

    /** Result of one event. */
    public static final class Result {

        static final Result NONE = new Result(Action.NONE, Collections.<ScreenInput>emptyList());
        static final Result PASTE = new Result(Action.PASTE, Collections.<ScreenInput>emptyList());
        static final Result CLOSE = new Result(Action.CLOSE, Collections.<ScreenInput>emptyList());

        public final Action action;
        public final List<ScreenInput> inputs;

        Result(Action action, List<ScreenInput> inputs) {
            this.action = action;
            this.inputs = inputs;
        }

        static Result send(ScreenInput... inputs) {
            List<ScreenInput> l = new ArrayList<>(inputs.length);
            Collections.addAll(l, inputs);
            return new Result(Action.SEND, l);
        }
    }

    private char pendingHigh;

    public Result route(int key, char ch, boolean down, boolean shift, boolean ctrl, boolean alt, boolean meta) {
        if (key == KeyMapping.KEY_ESCAPE) return down ? Result.CLOSE : Result.NONE;
        int mods = (shift ? ScreenInput.MOD_SHIFT : 0) | (ctrl ? ScreenInput.MOD_CTRL : 0)
            | (alt ? ScreenInput.MOD_ALT : 0)
            | (meta ? ScreenInput.MOD_META : 0);
        boolean shortcut = (ctrl && !alt) || meta;

        String named = KeyMapping.domName(key);
        if (named != null) {
            if (!down) return Result.send(ScreenInput.key(false, named, mods));
            String text = KeyMapping.textOf(named);
            if (text != null && !shortcut)
                return Result.send(ScreenInput.key(true, named, mods), ScreenInput.chars(text));
            return Result.send(ScreenInput.key(true, named, mods));
        }
        if (shortcut) {
            if (key == KeyMapping.KEY_V) return down ? Result.PASTE : Result.NONE;
            String letter = KeyMapping.shortcutLetter(key);
            return letter == null ? Result.NONE : Result.send(ScreenInput.key(down, letter, mods));
        }
        if (!down) return Result.NONE;
        return typed(ch);
    }

    private Result typed(char ch) {
        if (Character.isHighSurrogate(ch)) {
            pendingHigh = ch;
            return Result.NONE;
        }
        if (Character.isLowSurrogate(ch)) {
            char high = pendingHigh;
            pendingHigh = 0;
            return high == 0 ? Result.NONE : Result.send(ScreenInput.chars(new String(new char[] { high, ch })));
        }
        pendingHigh = 0;
        if (!KeyMapping.isTypable(ch)) return Result.NONE;
        return Result.send(ScreenInput.chars(String.valueOf(ch)));
    }

    /**
     * Clipboard text as one CHAR input: line breaks normalised to "\n", other controls dropped, capped at
     * {@link #MAX_PASTE_CHARS} without splitting a surrogate pair. Null when nothing typable is left.
     */
    public static ScreenInput paste(String clipboard) {
        if (clipboard == null || clipboard.isEmpty()) return null;
        String text = clipboard.replace("\r\n", "\n")
            .replace('\r', '\n');
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_PASTE_CHARS));
        for (int i = 0; i < text.length() && out.length() < MAX_PASTE_CHARS; i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                boolean pair = i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1));
                if (pair) {
                    if (out.length() + 2 <= MAX_PASTE_CHARS) out.append(c)
                        .append(text.charAt(i + 1));
                    i++;
                }
                continue; // a lone high surrogate is dropped
            }
            if (c == '\n' || c == '\t' || KeyMapping.isTypable(c)) out.append(c);
        }
        return out.length() == 0 ? null : ScreenInput.chars(out.toString());
    }
}
