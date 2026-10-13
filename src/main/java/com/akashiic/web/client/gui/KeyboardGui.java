package com.akashiic.web.client.gui;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.akashiic.web.client.screen.ClientScreen;
import com.akashiic.web.client.screen.ClientScreens;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.net.ScreenInput;

/**
 * Typing on a screen: while open, every LWJGL 2 keyboard event (downs, ups and repeats) goes through
 * {@link KeyboardRouter} straight to this client's own browser (D-016: keystrokes never leave the client); Esc closes.
 * The first key on a free screen takes it. While someone else controls the screen the keys are dropped and the bar
 * says who controls it. No darkened background, so the player watches the page while typing. The mouse wheel scrolls
 * the page. Vanilla's own key hooks (F2 screenshot, F11 fullscreen) still run, as in
 * {@link GuiScreen#handleKeyboardInput()}. A vanilla GuiScreen until the wave-3 ModularUI2 GUIs (D-017).
 */
public final class KeyboardGui extends GuiScreen {

    /** Typing stops when the player walks away from the screen. */
    static final double MAX_DISTANCE = 24;

    private final ClientScreens screens;
    private final ScreenKey key;
    private final KeyboardRouter router = new KeyboardRouter();

    public KeyboardGui(ClientScreens screens, ScreenKey key) {
        this.screens = screens;
        this.key = key;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
    }

    @Override
    public void handleKeyboardInput() {
        ClientScreen s = screens.get(key);
        if (s == null) {
            mc.displayGuiScreen(null);
            return;
        }
        boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
        boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
        boolean alt = Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
        boolean meta = Keyboard.isKeyDown(Keyboard.KEY_LMETA) || Keyboard.isKeyDown(Keyboard.KEY_RMETA);
        KeyboardRouter.Result r = router.route(
            Keyboard.getEventKey(),
            Keyboard.getEventCharacter(),
            Keyboard.getEventKeyState(),
            shift,
            ctrl,
            alt,
            meta);
        switch (r.action) {
            case CLOSE:
                mc.displayGuiScreen(null);
                return;
            case PASTE:
                for (ScreenInput text : KeyboardRouter.paste(getClipboardString())) screens.type(s, text);
                break;
            case SEND:
                for (ScreenInput in : r.inputs) screens.type(s, in);
                break;
            case NONE:
            default:
                break;
        }
        mc.func_152348_aa();
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        ClientScreen s = screens.get(key);
        // The cursor is free in a GUI, so there is no aim: scroll at the page centre.
        if (wheel != 0 && s != null) screens.wheel(s, 0.5, 0.5, wheel / 120f, 0);
    }

    @Override
    public void updateScreen() {
        ClientScreen s = screens.get(key);
        if (s == null || s.distance() > MAX_DISTANCE) mc.displayGuiScreen(null);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        ClientScreen s = screens.get(key);
        int barTop = height - 28;
        drawRect(0, barTop, width, height, 0xA0000000);
        drawCenteredString(fontRendererObj, I18n.format(LangKeys.GUI_KEYBOARD_HINT), width / 2, barTop + 5, 0xFFFFFF);
        if (s != null && !screens.drivesInput(s) && s.state().controller != null) {
            // Someone else holds the lock: our keys stay on this client and are dropped (D-016). Say who.
            drawCenteredString(
                fontRendererObj,
                AddressBarGui.controllerLine(screens, s.state()),
                width / 2,
                barTop + 16,
                0xFFB060);
        } else if (s != null) {
            ScreenState st = s.state();
            drawCenteredString(
                fontRendererObj,
                fontRendererObj.trimStringToWidth(ClientNotices.sanitize(st.url), width - 20),
                width / 2,
                barTop + 16,
                0x909090);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** Typing is over: the lock goes back after a short grace unless the player keeps using the screen in the world. */
    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        screens.leaveSoon(screens.get(key));
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
