package com.akashiic.web.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.IllegalFormatException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shipped lang files, formatted the way Minecraft 1.7.10 formats them, for tests that must prove a text never
 * shows "Format error" or a raw key:
 * <ul>
 * <li>{@code I18n.format} (placeholders, GUIs): {@code String.format(text, args)}; an IllegalFormatException turns the
 * text into "Format error: ..." ({@code net.minecraft.client.resources.Locale.formatMessage}).</li>
 * <li>{@code ChatComponentTranslation} (chat notices): only {@code %s} and {@code %%}; a missing argument or another
 * conversion makes it fall back to the vanilla translator, which prints the raw key.</li>
 * </ul>
 */
public final class LangText {

    private static final Pattern CHAT_FORMAT = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");

    public static final Map<String, String> EN = load("en_US.lang");
    public static final Map<String, String> PT = load("pt_BR.lang");

    private LangText() {}

    private static Map<String, String> load(String name) {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = LangText.class.getResourceAsStream("/assets/akashicweb/lang/" + name)) {
            assertNotNull(in, name);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null;) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(name, e);
        }
        return out;
    }

    /** I18n.format in both languages; fails the test on a missing key or a format error. */
    public static void assertFormats(String key, Object... args) {
        for (Map<String, String> lang : java.util.Arrays.asList(EN, PT)) {
            String text = lang.get(key);
            assertNotNull(text, "missing lang key " + key);
            try {
                String.format(text, args);
            } catch (IllegalFormatException e) {
                fail("Minecraft would show 'Format error' for " + key + " with " + args.length + " argument(s): " + e);
            }
        }
    }

    /** ChatComponentTranslation in both languages: only %s, and no more of them than arguments. */
    public static void assertChatFormats(String key, Object... args) {
        for (Map<String, String> lang : java.util.Arrays.asList(EN, PT)) {
            String text = lang.get(key);
            assertNotNull(text, "missing lang key " + key);
            Matcher m = CHAT_FORMAT.matcher(text);
            int used = 0;
            while (m.find()) {
                String conv = m.group(2);
                if ("%".equals(conv)) continue;
                assertTrue("s".equals(conv), key + ": chat supports only %s, found %" + conv);
                used++;
            }
            assertTrue(used <= args.length, key + " needs " + used + " argument(s), got " + args.length);
        }
    }
}
