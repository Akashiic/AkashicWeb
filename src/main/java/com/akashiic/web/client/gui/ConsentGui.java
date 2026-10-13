package com.akashiic.web.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

/**
 * The install prompt's view: a plain vanilla GuiScreen that draws {@link ConsentController#view()} and forwards button
 * presses. Deliberately minimal: D-017 moves every GUI to ModularUI2 in wave 3, which replaces this class only. The
 * view is rebuilt when the installer's state changes while the prompt is open (progress, failure). Esc closes it
 * without deciding anything. Client thread only.
 */
public final class ConsentGui extends GuiScreen {

    private static final int LINE_HEIGHT = 11;
    private static final int BUTTON_WIDTH = 110;

    private final ConsentController controller;
    private final List<ConsentController.Button> shown = new ArrayList<>();
    private ConsentController.View view;

    public ConsentGui(ConsentController controller) {
        this.controller = controller;
    }

    @Override
    public void initGui() {
        view = controller.view();
        buttonList.clear();
        shown.clear();
        shown.addAll(view.buttons);
        int n = shown.size();
        int total = n * BUTTON_WIDTH + (n - 1) * 8;
        int x = (width - total) / 2;
        int y = Math.min(height - 28, height / 2 + 60);
        for (int i = 0; i < n; i++) {
            ConsentController.Button b = shown.get(i);
            buttonList.add(new GuiButton(i, x + i * (BUTTON_WIDTH + 8), y, BUTTON_WIDTH, 20, I18n.format(b.langKey)));
        }
    }

    @Override
    public void updateScreen() {
        ConsentController.View next = controller.view();
        if (!next.buttons.equals(shown)) {
            initGui(); // the state changed: other buttons
        } else {
            view = next; // same buttons, new text (progress)
        }
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id < 0 || b.id >= shown.size()) return;
        if (controller.press(shown.get(b.id))) mc.displayGuiScreen(null);
        else initGui();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int wrap = Math.min(360, width - 40);
        int y = Math.max(10, height / 2 - 80);
        drawCenteredString(fontRendererObj, I18n.format(view.titleKey), width / 2, y, 0xFFFFFF);
        y += LINE_HEIGHT * 2;
        for (ConsentController.Line line : view.lines) {
            for (Object part : fontRendererObj.listFormattedStringToWidth(I18n.format(line.key, line.args), wrap)) {
                drawCenteredString(fontRendererObj, String.valueOf(part), width / 2, y, 0xD0D0D0);
                y += LINE_HEIGHT;
            }
            y += 3;
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
