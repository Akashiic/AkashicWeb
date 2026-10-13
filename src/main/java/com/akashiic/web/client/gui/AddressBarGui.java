package com.akashiic.web.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;

import com.akashiic.web.client.screen.ClientScreen;
import com.akashiic.web.client.screen.ClientScreens;
import com.akashiic.web.client.screen.LocalRights;
import com.akashiic.web.common.Rights;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenMode;
import com.akashiic.web.common.ScreenState;

/**
 * Address bar for one screen (sneak + right-click): URL field with Go, back/forward/reload/home, the screen keyboard
 * and the controller lock. A vanilla GuiScreen (no ModularUI dependency for 10-25, ADR-009). Everything goes to the
 * server (C2SSetUrl, C2SNav, C2SControl), which validates rights, the site policy and the epoch; buttons are only
 * disabled as a hint from {@code LocalRights}.
 */
public final class AddressBarGui extends GuiScreen {

    private static final int ID_GO = 0;
    private static final int ID_BACK = 1;
    private static final int ID_FORWARD = 2;
    private static final int ID_RELOAD = 3;
    private static final int ID_HOME = 4;
    private static final int ID_KEYBOARD = 5;
    private static final int ID_CONTROL = 6;
    private static final int ID_CLOSE = 7;

    private final ClientScreens screens;
    private final ScreenKey key;
    private GuiTextField urlField;
    private GuiButton go;
    private GuiButton back;
    private GuiButton forward;
    private GuiButton reload;
    private GuiButton home;
    private GuiButton keyboard;
    private GuiButton control;
    private String error;

    public AddressBarGui(ClientScreens screens, ScreenKey key) {
        this.screens = screens;
        this.key = key;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        int w = Math.min(380, width - 20);
        int x0 = (width - w) / 2;
        int y0 = height / 2 - 30;
        String previous = urlField == null ? null : urlField.getText();
        urlField = new GuiTextField(fontRendererObj, x0, y0, w - 46, 20);
        urlField.setMaxStringLength(UrlInput.MAX_LENGTH);
        ClientScreen s = screens.get(key);
        urlField.setText(previous != null ? previous : s == null ? "" : s.state().url);
        urlField.setFocused(true);
        go = add(new GuiButton(ID_GO, x0 + w - 42, y0, 42, 20, I18n.format(LangKeys.GUI_GO)));
        int quarter = (w - 12) / 4;
        int y1 = y0 + 26;
        back = add(new GuiButton(ID_BACK, x0, y1, quarter, 20, I18n.format(LangKeys.GUI_BACK)));
        forward = add(
            new GuiButton(ID_FORWARD, x0 + (quarter + 4), y1, quarter, 20, I18n.format(LangKeys.GUI_FORWARD)));
        reload = add(
            new GuiButton(ID_RELOAD, x0 + 2 * (quarter + 4), y1, quarter, 20, I18n.format(LangKeys.GUI_RELOAD)));
        home = add(new GuiButton(ID_HOME, x0 + 3 * (quarter + 4), y1, quarter, 20, I18n.format(LangKeys.GUI_HOME)));
        int third = (w - 8) / 3;
        int y2 = y1 + 24;
        keyboard = add(new GuiButton(ID_KEYBOARD, x0, y2, third, 20, I18n.format(LangKeys.GUI_KEYBOARD)));
        control = add(new GuiButton(ID_CONTROL, x0 + third + 4, y2, third, 20, ""));
        add(new GuiButton(ID_CLOSE, x0 + 2 * (third + 4), y2, third, 20, I18n.format(LangKeys.GUI_CLOSE)));
        refreshButtons();
    }

    private GuiButton add(GuiButton b) {
        buttonList.add(b);
        return b;
    }

    private void refreshButtons() {
        ClientScreen s = screens.get(key);
        if (s == null) return;
        boolean url = screens.can(s, Rights.CHANGE_URL);
        boolean shared = s.state().mode != ScreenMode.PRIVATE;
        go.enabled = url;
        back.enabled = url && s.page().canGoBack;
        forward.enabled = url && s.page().canGoForward;
        reload.enabled = url;
        home.enabled = url;
        keyboard.enabled = screens.can(s, Rights.CLICK_TYPE);
        boolean mine = screens.isController(s);
        control.displayString = I18n.format(mine ? LangKeys.GUI_RELEASE_CONTROL : LangKeys.GUI_TAKE_CONTROL);
        control.enabled = shared && (mine || screens.can(s, Rights.CLICK_TYPE));
    }

    @Override
    public void updateScreen() {
        ClientScreen s = screens.get(key);
        if (s == null) {
            mc.displayGuiScreen(null); // the screen was broken or unloaded while the GUI was open
            return;
        }
        urlField.updateCursorCounter();
        refreshButtons();
    }

    @Override
    protected void keyTyped(char c, int code) {
        if (code == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        if (code == Keyboard.KEY_RETURN || code == Keyboard.KEY_NUMPADENTER) {
            submit();
            return;
        }
        if (urlField.textboxKeyTyped(c, code)) error = null;
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        urlField.mouseClicked(x, y, button);
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        ClientScreen s = screens.get(key);
        if (s == null || !b.enabled) return;
        switch (b.id) {
            case ID_GO:
                submit();
                break;
            case ID_BACK:
                screens.nav(s, "back");
                break;
            case ID_FORWARD:
                screens.nav(s, "forward");
                break;
            case ID_RELOAD:
                screens.nav(s, "reload");
                break;
            case ID_HOME:
                screens.nav(s, "home");
                break;
            case ID_KEYBOARD:
                mc.displayGuiScreen(new KeyboardGui(screens, key));
                break;
            case ID_CONTROL:
                screens.control(s, !screens.isController(s));
                break;
            case ID_CLOSE:
            default:
                mc.displayGuiScreen(null);
        }
    }

    private void submit() {
        ClientScreen s = screens.get(key);
        if (s == null) return;
        if (!screens.can(s, Rights.CHANGE_URL)) {
            error = I18n.format(LangKeys.GUI_READ_ONLY);
            return;
        }
        String url = UrlInput.normalize(urlField.getText());
        if (url == null) {
            error = I18n.format(LangKeys.GUI_INVALID_URL);
            return;
        }
        screens.setUrl(s, url);
        mc.displayGuiScreen(null);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        ClientScreen s = screens.get(key);
        int top = urlField.yPosition;
        String title = s == null || s.page().title.isEmpty() ? I18n.format(LangKeys.GUI_ADDRESS_TITLE)
            : fontRendererObj.trimStringToWidth(ClientNotices.sanitize(s.page().title), width - 20);
        drawCenteredString(fontRendererObj, title, width / 2, top - 32, 0xFFFFFF);
        if (s != null) drawCenteredString(fontRendererObj, controllerLine(s.state()), width / 2, top - 18, 0xA0A0A0);
        urlField.drawTextBox();
        if (error != null) drawCenteredString(fontRendererObj, error, width / 2, top + 76, 0xFF6060);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private String controllerLine(ScreenState st) {
        return controllerLine(screens, st);
    }

    /** "Controlled by: <name>", with the server's controllerName (D-016); "you" by the S2CHello UUID. */
    static String controllerLine(ClientScreens screens, ScreenState st) {
        String who;
        if (st.controller == null) who = I18n.format(LangKeys.GUI_NOBODY);
        else if (LocalRights.isController(st, screens.player())) who = I18n.format(LangKeys.GUI_YOU);
        else who = LocalRights.controllerDisplayName(st);
        return I18n.format(LangKeys.GUI_CONTROLLER, who);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
