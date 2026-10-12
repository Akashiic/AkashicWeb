package com.akashiic.web.common;

/**
 * Cleans text coming from players or NBT before it is stored, synced or drawn.
 * <p>
 * Copied from AkashicFM (same owner) {@code com.akashiic.fm.common.TextSanitizer}; {@link #cleanInput} is new: text
 * typed into a web page keeps the section sign (an ABNT2 key) because it never reaches Minecraft's chat renderer.
 */
public final class TextSanitizer {

    private TextSanitizer() {}

    /**
     * Removes control characters and the section sign (Minecraft formatting codes would allow invisible or obfuscated
     * text on screen) and cuts at the maximum length without splitting surrogate pairs.
     */
    public static String clean(String raw, int maxLength) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), maxLength));
        for (int i = 0; i < raw.length() && sb.length() < maxLength; i++) {
            char c = raw.charAt(i);
            if (c == '§') {
                // The whole formatting code goes (like vanilla): "§kX" becomes "X", not "kX".
                if (i + 1 < raw.length() && isFormatCode(raw.charAt(i + 1))) i++;
                continue;
            }
            if (Character.isISOControl(c) || isInvisibleFormat(c)) continue;
            i = appendChecked(raw, i, c, sb, maxLength);
        }
        return sb.toString()
            .trim();
    }

    /**
     * Text typed into a page (C2SKey CHAR): drops control and invisible format characters (bidi overrides, zero-width
     * characters), keeps everything else including the section sign, never splits a surrogate pair.
     */
    public static String cleanInput(String raw, int maxLength) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), maxLength));
        for (int i = 0; i < raw.length() && sb.length() < maxLength; i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c) || isInvisibleFormat(c)) continue;
            i = appendChecked(raw, i, c, sb, maxLength);
        }
        return sb.toString();
    }

    /** Appends c (or the surrogate pair starting at i) and returns the index of the last consumed char. */
    private static int appendChecked(String raw, int i, char c, StringBuilder sb, int maxLength) {
        if (Character.isHighSurrogate(c)) {
            if (i + 1 < raw.length() && Character.isLowSurrogate(raw.charAt(i + 1))) {
                if (sb.length() + 2 <= maxLength) sb.append(c)
                    .append(raw.charAt(i + 1));
                return i + 1; // a pair that does not fit is dropped whole
            }
            return i; // lone high surrogate
        }
        if (!Character.isLowSurrogate(c)) sb.append(c); // a lone low surrogate is dropped
        return i;
    }

    /** Letters valid after the section sign (colours 0-9a-f, styles k-o, reset r). */
    static boolean isFormatCode(char c) {
        char l = Character.toLowerCase(c);
        return (l >= '0' && l <= '9') || (l >= 'a' && l <= 'f') || (l >= 'k' && l <= 'o') || l == 'r';
    }

    /**
     * Invisible formatting characters (category Cf: zero-width, direction override U+202E and so on), which would make
     * misleading text on screen or URLs that look equal and are not. Surrogates are not in this category.
     */
    static boolean isInvisibleFormat(char c) {
        return Character.getType(c) == Character.FORMAT;
    }

    /** URL: no spaces, no control or invisible formatting, bounded length. Real validation is UrlPolicy's. */
    public static String cleanUrl(String raw, int maxLength) {
        if (raw == null) return "";
        String s = raw.trim();
        StringBuilder sb = new StringBuilder(Math.min(s.length(), maxLength));
        for (int i = 0; i < s.length() && sb.length() < maxLength; i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c) || isInvisibleFormat(c)) continue;
            sb.append(c);
        }
        return sb.toString();
    }
}
