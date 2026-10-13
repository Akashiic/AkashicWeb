package com.akashiic.web.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.lwjgl.input.Keyboard;

import com.akashiic.web.client.gui.KeyboardRouter.Action;
import com.akashiic.web.common.net.ScreenInput;
import com.akashiic.web.common.net.ScreenInput.Kind;

class KeyboardRouterTest {

    private final KeyboardRouter r = new KeyboardRouter();

    private KeyboardRouter.Result down(int key, char ch) {
        return r.route(key, ch, true, false, false, false, false);
    }

    private KeyboardRouter.Result up(int key, char ch) {
        return r.route(key, ch, false, false, false, false, false);
    }

    private static String describe(List<ScreenInput> in) {
        StringBuilder b = new StringBuilder();
        for (ScreenInput i : in) {
            if (b.length() > 0) b.append(' ');
            b.append(i.kind)
                .append(':')
                .append(i.kind == Kind.CHAR ? i.text : i.key);
        }
        return b.toString();
    }

    @Test
    void lettersTypeTextOnlyOnPress() {
        KeyboardRouter.Result d = down(Keyboard.KEY_A, 'a');
        assertEquals(Action.SEND, d.action);
        assertEquals("CHAR:a", describe(d.inputs));
        assertEquals(Action.NONE, up(Keyboard.KEY_A, 'a').action);
        assertEquals("CHAR:A", describe(r.route(Keyboard.KEY_A, 'A', true, true, false, false, false).inputs));
    }

    @Test
    void enterAndSpaceSendTheKeyAndTheirText() {
        assertEquals("KEY_DOWN:Enter CHAR:\r", describe(down(Keyboard.KEY_RETURN, '\r').inputs));
        assertEquals("KEY_UP:Enter", describe(up(Keyboard.KEY_RETURN, '\r').inputs));
        assertEquals("KEY_DOWN:  CHAR: ", describe(down(Keyboard.KEY_SPACE, ' ').inputs));
        assertEquals("KEY_DOWN:Backspace", describe(down(Keyboard.KEY_BACK, '\b').inputs));
        assertEquals("KEY_DOWN:ArrowLeft", describe(down(Keyboard.KEY_LEFT, '\0').inputs));
        assertEquals("KEY_DOWN:Tab", describe(down(Keyboard.KEY_TAB, '\t').inputs));
    }

    @Test
    void namedKeysCarryModifiers() {
        KeyboardRouter.Result d = r.route(Keyboard.KEY_LEFT, '\0', true, true, true, false, false);
        assertEquals(ScreenInput.MOD_SHIFT | ScreenInput.MOD_CTRL, d.inputs.get(0).modifiers);
        KeyboardRouter.Result ctrlEnter = r.route(Keyboard.KEY_RETURN, '\n', true, false, true, false, false);
        assertEquals("KEY_DOWN:Enter", describe(ctrlEnter.inputs), "Ctrl+Enter is a shortcut, not a newline");
    }

    @Test
    void abnt2DeadKeyComposesOneCharacter() {
        // ´ is a dead key on ABNT2 (LWJGL reports its key with no character), then 'a' arrives as 'á'.
        assertEquals(Action.NONE, down(Keyboard.KEY_LBRACKET, '\0').action);
        assertEquals("CHAR:á", describe(down(Keyboard.KEY_A, 'á').inputs));
        assertEquals("CHAR:ç", describe(down(Keyboard.KEY_SEMICOLON, 'ç').inputs));
        assertEquals("CHAR:~", describe(down(Keyboard.KEY_APOSTROPHE, '~').inputs), "dead key + space");
    }

    @Test
    void altGrTypesInsteadOfTriggeringShortcuts() {
        // AltGr+Q on ABNT2 is '/': Windows reports Ctrl and Alt down.
        KeyboardRouter.Result slash = r.route(Keyboard.KEY_Q, '/', true, false, true, true, false);
        assertEquals("CHAR:/", describe(slash.inputs));
        KeyboardRouter.Result altGrV = r.route(Keyboard.KEY_V, 'v', true, false, true, true, false);
        assertEquals(Action.SEND, altGrV.action, "AltGr+V is not paste");
    }

    @Test
    void clipboardShortcuts() {
        assertEquals(Action.PASTE, r.route(Keyboard.KEY_V, '\u0016', true, false, true, false, false).action);
        assertEquals(Action.NONE, r.route(Keyboard.KEY_V, '\u0016', false, false, true, false, false).action);
        assertEquals(Action.NONE, r.route(Keyboard.KEY_C, '\u0003', true, false, true, false, false).action);
        assertEquals(Action.NONE, r.route(Keyboard.KEY_X, '\u0018', true, false, true, false, false).action);
        KeyboardRouter.Result all = r.route(Keyboard.KEY_A, '\u0001', true, false, true, false, false);
        assertEquals("KEY_DOWN:a", describe(all.inputs));
        assertEquals(ScreenInput.MOD_CTRL, all.inputs.get(0).modifiers);
        assertEquals("KEY_UP:z", describe(r.route(Keyboard.KEY_Z, '\0', false, false, true, false, false).inputs));
        assertEquals(Action.NONE, r.route(Keyboard.KEY_S, '\u0013', true, false, true, false, false).action);
    }

    @Test
    void escapeClosesAndControlCharactersAreDropped() {
        assertEquals(Action.CLOSE, down(Keyboard.KEY_ESCAPE, '\u001b').action);
        assertEquals(Action.NONE, up(Keyboard.KEY_ESCAPE, '\u001b').action);
        assertEquals(Action.NONE, down(Keyboard.KEY_NONE, '\u0007').action);
    }

    @Test
    void surrogatePairsArriveAsOneString() {
        assertEquals(Action.NONE, down(Keyboard.KEY_NONE, '\uD83D').action);
        assertEquals("CHAR:😀", describe(down(Keyboard.KEY_NONE, '\uDE00').inputs));
        assertEquals(Action.NONE, down(Keyboard.KEY_NONE, '\uDE00').action, "lone low surrogate");
    }

    @Test
    void pasteIsSanitizedAndCapped() {
        assertEquals("linha 1\nlinha 2\tção", KeyboardRouter.paste("linha 1\r\nlinha 2\tção\u0000").text);
        assertNull(KeyboardRouter.paste(""));
        assertNull(KeyboardRouter.paste("\u0001\u0002"));
        assertNull(KeyboardRouter.paste(null));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2_000; i++) big.append('x');
        assertEquals(KeyboardRouter.MAX_PASTE_CHARS, KeyboardRouter.paste(big.toString()).text.length());
        StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < 300; i++) emoji.append("😀");
        String capped = KeyboardRouter.paste(emoji.toString()).text;
        assertTrue(capped.length() <= KeyboardRouter.MAX_PASTE_CHARS);
        assertTrue(Character.isLowSurrogate(capped.charAt(capped.length() - 1)), "pair not split");
        assertEquals("ab", KeyboardRouter.paste("a\uD83Db").text, "lone high surrogate dropped, next char kept");
        assertEquals(Kind.CHAR, KeyboardRouter.paste("x").kind);
    }
}
