package com.akashiic.web.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.lwjgl.input.Keyboard;

class KeyMappingTest {

    @Test
    void copiedKeyCodesMatchLwjgl2() {
        assertEquals(Keyboard.KEY_NONE, KeyMapping.KEY_NONE);
        assertEquals(Keyboard.KEY_ESCAPE, KeyMapping.KEY_ESCAPE);
        assertEquals(Keyboard.KEY_BACK, KeyMapping.KEY_BACK);
        assertEquals(Keyboard.KEY_TAB, KeyMapping.KEY_TAB);
        assertEquals(Keyboard.KEY_Y, KeyMapping.KEY_Y);
        assertEquals(Keyboard.KEY_RETURN, KeyMapping.KEY_RETURN);
        assertEquals(Keyboard.KEY_A, KeyMapping.KEY_A);
        assertEquals(Keyboard.KEY_Z, KeyMapping.KEY_Z);
        assertEquals(Keyboard.KEY_X, KeyMapping.KEY_X);
        assertEquals(Keyboard.KEY_C, KeyMapping.KEY_C);
        assertEquals(Keyboard.KEY_V, KeyMapping.KEY_V);
        assertEquals(Keyboard.KEY_SPACE, KeyMapping.KEY_SPACE);
        assertEquals(Keyboard.KEY_F1, KeyMapping.KEY_F1);
        assertEquals(Keyboard.KEY_F10, KeyMapping.KEY_F10);
        assertEquals(Keyboard.KEY_F11, KeyMapping.KEY_F11);
        assertEquals(Keyboard.KEY_F12, KeyMapping.KEY_F12);
        assertEquals(Keyboard.KEY_NUMPADENTER, KeyMapping.KEY_NUMPADENTER);
        assertEquals(Keyboard.KEY_HOME, KeyMapping.KEY_HOME);
        assertEquals(Keyboard.KEY_UP, KeyMapping.KEY_UP);
        assertEquals(Keyboard.KEY_PRIOR, KeyMapping.KEY_PRIOR);
        assertEquals(Keyboard.KEY_LEFT, KeyMapping.KEY_LEFT);
        assertEquals(Keyboard.KEY_RIGHT, KeyMapping.KEY_RIGHT);
        assertEquals(Keyboard.KEY_END, KeyMapping.KEY_END);
        assertEquals(Keyboard.KEY_DOWN, KeyMapping.KEY_DOWN);
        assertEquals(Keyboard.KEY_NEXT, KeyMapping.KEY_NEXT);
        assertEquals(Keyboard.KEY_INSERT, KeyMapping.KEY_INSERT);
        assertEquals(Keyboard.KEY_DELETE, KeyMapping.KEY_DELETE);
    }

    @Test
    void namedKeysMapToDomAndElectronNames() {
        assertEquals("Enter", KeyMapping.domName(Keyboard.KEY_RETURN));
        assertEquals("Enter", KeyMapping.domName(Keyboard.KEY_NUMPADENTER));
        assertEquals("Backspace", KeyMapping.domName(Keyboard.KEY_BACK));
        assertEquals("ArrowLeft", KeyMapping.domName(Keyboard.KEY_LEFT));
        assertEquals("PageDown", KeyMapping.domName(Keyboard.KEY_NEXT));
        assertEquals(" ", KeyMapping.domName(Keyboard.KEY_SPACE));
        assertEquals("F5", KeyMapping.domName(Keyboard.KEY_F5));
        assertEquals("F12", KeyMapping.domName(Keyboard.KEY_F12));
        assertEquals("Left", KeyMapping.electronKeyCode("ArrowLeft"));
        assertEquals("Down", KeyMapping.electronKeyCode("ArrowDown"));
        assertEquals("Space", KeyMapping.electronKeyCode(" "));
        assertEquals("Enter", KeyMapping.electronKeyCode("Enter"));
        assertEquals("F5", KeyMapping.electronKeyCode("F5"));
        assertEquals("a", KeyMapping.electronKeyCode("a"));
        assertEquals("a", KeyMapping.electronKeyCode("A"));
    }

    @Test
    void characterKeysAndModifiersAreNotNamed() {
        assertNull(KeyMapping.domName(Keyboard.KEY_A), "letters travel as text");
        assertNull(KeyMapping.domName(Keyboard.KEY_1));
        assertNull(KeyMapping.domName(Keyboard.KEY_LSHIFT), "modifiers travel as flags only");
        assertNull(KeyMapping.domName(Keyboard.KEY_LCONTROL));
        assertNull(KeyMapping.domName(Keyboard.KEY_RMENU), "AltGr must never become an Alt key event");
        assertNull(KeyMapping.domName(Keyboard.KEY_LMETA));
        assertNull(KeyMapping.domName(Keyboard.KEY_NONE));
    }

    @Test
    void unknownWireNamesAreRefused() {
        assertNull(KeyMapping.electronKeyCode("Control"));
        assertNull(KeyMapping.electronKeyCode("c"), "copy is never relayed");
        assertNull(KeyMapping.electronKeyCode("x"), "cut is never relayed");
        assertNull(KeyMapping.electronKeyCode("enter"));
        assertNull(KeyMapping.electronKeyCode("<script>"));
        assertNull(KeyMapping.electronKeyCode(""));
        assertNull(KeyMapping.electronKeyCode(null));
    }

    @Test
    void namedKeysThatTypeText() {
        assertEquals("\r", KeyMapping.textOf("Enter"));
        assertEquals(" ", KeyMapping.textOf(" "));
        assertNull(KeyMapping.textOf("Tab"));
        assertNull(KeyMapping.textOf("Backspace"));
    }

    @Test
    void typableCharacters() {
        assertTrue(KeyMapping.isTypable('a'));
        assertTrue(KeyMapping.isTypable('ç'));
        assertTrue(KeyMapping.isTypable('ã'));
        assertTrue(KeyMapping.isTypable('€'));
        assertTrue(KeyMapping.isTypable('/'));
        assertFalse(KeyMapping.isTypable('\0'));
        assertFalse(KeyMapping.isTypable('\r'));
        assertFalse(KeyMapping.isTypable('\b'));
        assertFalse(KeyMapping.isTypable('\u007f'));
        assertFalse(KeyMapping.isTypable('\u0085'));
        assertFalse(KeyMapping.isTypable('\uD83D'), "lone surrogate");
        assertFalse(KeyMapping.isTypable('￿'));
    }
}
