package com.akashiic.web.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.akashiic.web.client.host.InputEvent;
import com.akashiic.web.common.net.ScreenInput;
import com.akashiic.web.common.net.ScreenInput.Kind;

class InputTranslatorTest {

    @Test
    void pointerMapsToPagePixels() {
        InputEvent e = InputTranslator
            .toHost(ScreenInput.pointer(Kind.DOWN, 0.5f, 0.25f, ScreenInput.BUTTON_LEFT, 2, 0), 1280, 720);
        assertEquals(InputEvent.Type.MOUSE_DOWN, e.type);
        assertEquals(640, e.x);
        assertEquals(180, e.y);
        assertEquals("left", e.button);
        assertEquals(2, e.clickCount);
        InputEvent corner = InputTranslator
            .toHost(ScreenInput.pointer(Kind.UP, 1f, 1f, ScreenInput.BUTTON_RIGHT, 1, 0), 1280, 720);
        assertEquals(InputEvent.Type.MOUSE_UP, corner.type);
        assertEquals(1279, corner.x, "the last pixel, not one past it");
        assertEquals(719, corner.y);
        assertEquals("right", corner.button);
        assertEquals(
            InputEvent.Type.MOUSE_MOVE,
            InputTranslator.toHost(ScreenInput.pointer(Kind.MOVE, 0, 0, ScreenInput.BUTTON_MIDDLE, 0, 0), 10, 10).type);
    }

    @Test
    void forgedPointersAreDropped() {
        assertNull(InputTranslator.toHost(ScreenInput.pointer(Kind.DOWN, Float.NaN, 0.5f, 0, 1, 0), 100, 100));
        assertNull(InputTranslator.toHost(ScreenInput.pointer(Kind.DOWN, 3f, 0.5f, 0, 1, 0), 100, 100));
        assertNull(InputTranslator.toHost(ScreenInput.pointer(Kind.DOWN, 0.5f, -1f, 0, 1, 0), 100, 100));
        assertNull(InputTranslator.toHost(ScreenInput.pointer(Kind.DOWN, 0.5f, 0.5f, 7, 1, 0), 100, 100), "button");
        assertNull(InputTranslator.toHost(ScreenInput.pointer(Kind.DOWN, 0.5f, 0.5f, 0, 1, 0), 0, 100), "no page");
        assertNull(InputTranslator.toHost(null, 100, 100));
        assertEquals(
            3,
            InputTranslator.toHost(ScreenInput.pointer(Kind.DOWN, 0.5f, 0.5f, 0, 99, 0), 100, 100).clickCount);
    }

    @Test
    void wheelScalesNotchesToPixelsAndClampsFloods() {
        InputEvent e = InputTranslator.toHost(ScreenInput.wheel(0.5f, 0.5f, 0, 2f, 0), 100, 100);
        assertEquals(InputEvent.Type.WHEEL, e.type);
        assertEquals(200f, e.deltaY, 0f);
        assertEquals(0f, e.deltaX, 0f);
        InputEvent flood = InputTranslator.toHost(ScreenInput.wheel(0.5f, 0.5f, 0, -1e9f, 0), 100, 100);
        assertEquals(-InputTranslator.MAX_NOTCHES * InputTranslator.WHEEL_PIXELS_PER_NOTCH, flood.deltaY, 0f);
        assertNull(InputTranslator.toHost(ScreenInput.wheel(0.5f, 0.5f, 0, Float.NaN, 0), 100, 100));
    }

    @Test
    void keysUseElectronNamesAndUnknownKeysAreDropped() {
        InputEvent down = InputTranslator.toHost(ScreenInput.key(true, "ArrowLeft", ScreenInput.MOD_SHIFT), 100, 100);
        assertEquals(InputEvent.Type.KEY_DOWN, down.type);
        assertEquals("Left", down.keyCode);
        assertEquals(ScreenInput.MOD_SHIFT, down.modifiers);
        assertEquals(InputEvent.Type.KEY_UP, InputTranslator.toHost(ScreenInput.key(false, " ", 0), 1, 1).type);
        assertEquals("Space", InputTranslator.toHost(ScreenInput.key(false, " ", 0), 1, 1).keyCode);
        assertNull(InputTranslator.toHost(ScreenInput.key(true, "c", ScreenInput.MOD_CTRL), 1, 1), "copy");
        assertNull(InputTranslator.toHost(ScreenInput.key(true, "F13", 0), 1, 1));
    }

    @Test
    void textPassesThroughWithinTheCap() {
        assertEquals("ação", InputTranslator.toHost(ScreenInput.chars("ação"), 1, 1).text);
        assertNull(InputTranslator.toHost(ScreenInput.chars(""), 1, 1));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i <= InputTranslator.MAX_TEXT; i++) big.append('x');
        assertNull(InputTranslator.toHost(ScreenInput.chars(big.toString()), 1, 1));
    }
}
