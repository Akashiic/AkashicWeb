package com.akashiic.web.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;

/**
 * Chat lines for the player ("[AkashicWeb] <text>"). Arguments come from the server (S2CNotice) or from web pages
 * (blocked URL), so they are stripped of formatting codes and control characters and capped before display.
 * Client thread only.
 */
public final class ClientNotices {

    static final int MAX_ARG = 128;

    private ClientNotices() {}

    public static void show(String langKey, Object... args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.ingameGUI == null) return;
        Object[] safe = new Object[args == null ? 0 : args.length];
        for (int i = 0; i < safe.length; i++) safe[i] = sanitize(String.valueOf(args[i]));
        ChatComponentTranslation line = new ChatComponentTranslation(LangKeys.CHAT_PREFIX);
        line.getChatStyle()
            .setColor(EnumChatFormatting.AQUA);
        ChatComponentTranslation body = new ChatComponentTranslation(langKey, safe);
        body.getChatStyle()
            .setColor(EnumChatFormatting.GRAY);
        line.appendText(" ")
            .appendSibling(body);
        mc.ingameGUI.getChatGUI()
            .printChatMessage(line);
    }

    /** Removes the section sign (formatting codes) and control characters; caps the length. Pure. */
    public static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(Math.min(s.length(), MAX_ARG));
        for (int i = 0; i < s.length() && b.length() < MAX_ARG; i++) {
            char c = s.charAt(i);
            if (c == '§' || Character.isISOControl(c)) continue;
            b.append(c);
        }
        return b.toString();
    }
}
