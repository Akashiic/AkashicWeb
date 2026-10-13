package com.akashiic.web.client.host;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal JSON serializer for the game-to-host payloads (docs/PROTOCOL.md section 1). We write our own instead of
 * using Forge's Gson 2.2.4 so the output is byte-for-byte predictable in tests and the host never sees NaN, Infinity
 * or unescaped control characters, which Gson would emit or reject differently across versions.
 *
 * <p>
 * Supported values: {@link Map} (keys written with {@code String.valueOf}), {@link Iterable}, {@link CharSequence},
 * {@link Number}, {@link Boolean} and null. Non-finite numbers are rejected: callers sanitise first.
 */
public final class JsonWriter {

    private JsonWriter() {}

    /** Builds an object from alternating keys and values, preserving order: {@code object("a", 1, "b", "x")}. */
    public static String object(Object... keysAndValues) {
        return write(map(keysAndValues));
    }

    /** Ordered map from alternating keys and values, for nesting objects inside {@link #object}. */
    public static Map<String, Object> map(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) throw new IllegalArgumentException("odd number of key/value arguments");
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) m.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        return m;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(64);
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof CharSequence) {
            appendString(sb, value.toString());
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("non-finite number");
            if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(value instanceof Float ? Float.toString((Float) value) : Double.toString(d));
        } else if (value instanceof Number) {
            sb.append(((Number) value).longValue());
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                appendString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                append(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<?>) value) {
                if (!first) sb.append(',');
                first = false;
                append(sb, o);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException(
                "unsupported JSON value " + value.getClass()
                    .getName());
        }
    }

    /**
     * Quotes a string. Control characters, U+2028/U+2029 (line terminators for JavaScript) and lone surrogates are
     * escaped, so the payload is valid JSON and valid UTF-8 whatever the page title or URL contains.
     */
    static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029 || isLoneSurrogate(s, i)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static boolean isLoneSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) return i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1));
        if (Character.isLowSurrogate(c)) return i == 0 || !Character.isHighSurrogate(s.charAt(i - 1));
        return false;
    }
}
