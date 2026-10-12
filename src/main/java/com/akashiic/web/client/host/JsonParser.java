package com.akashiic.web.client.host;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small strict JSON parser for host-to-game control payloads (HELLO, PONG, BYE). Strict on purpose: the input comes
 * from another process, so trailing garbage, comments, NaN, duplicate keys or nesting deeper than {@link #MAX_DEPTH}
 * are rejected instead of being guessed at. Values map to {@link Map} (insertion order), {@link List},
 * {@link String}, {@link Long} (integral numbers that fit), {@link Double}, {@link Boolean} and null.
 */
public final class JsonParser {

    public static final int MAX_DEPTH = 32;

    /** Malformed JSON. An {@link IOException} so protocol code can propagate it with the socket errors. */
    public static final class JsonException extends IOException {

        private static final long serialVersionUID = 1L;

        JsonException(String message, int position) {
            super(message + " at " + position);
        }
    }

    private final String s;
    private int pos;

    private JsonParser(String s) {
        this.s = s;
    }

    public static Object parse(String json) throws JsonException {
        if (json == null) throw new JsonException("null input", 0);
        JsonParser p = new JsonParser(json);
        p.skipWhitespace();
        Object v = p.value(0);
        p.skipWhitespace();
        if (p.pos != json.length()) throw new JsonException("trailing characters", p.pos);
        return v;
    }

    /** Parses a document that must be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) throws JsonException {
        Object v = parse(json);
        if (!(v instanceof Map)) throw new JsonException("not an object", 0);
        return (Map<String, Object>) v;
    }

    /** String field, or null when absent or not a string. */
    public static String string(Map<String, Object> o, String key) {
        Object v = o.get(key);
        return v instanceof String ? (String) v : null;
    }

    /** Integral field, or {@code fallback} when absent, not a number or not integral. */
    public static long integer(Map<String, Object> o, String key, long fallback) {
        Object v = o.get(key);
        if (v instanceof Long) return (Long) v;
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d) && Math.abs(d) < 9.0e15) return (long) d;
        }
        return fallback;
    }

    private Object value(int depth) throws JsonException {
        if (depth > MAX_DEPTH) throw new JsonException("nesting too deep", pos);
        if (pos >= s.length()) throw new JsonException("unexpected end", pos);
        char c = s.charAt(pos);
        switch (c) {
            case '{':
                return object(depth + 1);
            case '[':
                return array(depth + 1);
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw new JsonException("unexpected character '" + c + "'", pos);
        }
    }

    private Map<String, Object> object(int depth) throws JsonException {
        Map<String, Object> m = new LinkedHashMap<>();
        pos++; // {
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return m;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') throw new JsonException("expected key", pos);
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            Object v = value(depth);
            if (m.containsKey(key)) throw new JsonException("duplicate key \"" + key + "\"", pos);
            m.put(key, v);
            skipWhitespace();
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw new JsonException("expected , or }", pos - 1);
        }
    }

    private List<Object> array(int depth) throws JsonException {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(value(depth));
            skipWhitespace();
            char c = next();
            if (c == ']') return list;
            if (c != ',') throw new JsonException("expected , or ]", pos - 1);
        }
    }

    private String string() throws JsonException {
        pos++; // opening quote
        StringBuilder sb = null;
        int start = pos;
        while (true) {
            if (pos >= s.length()) throw new JsonException("unterminated string", start);
            char c = s.charAt(pos);
            if (c == '"') {
                String out = sb == null ? s.substring(start, pos)
                    : sb.append(s, start, pos)
                        .toString();
                pos++;
                return out;
            }
            if (c < 0x20) throw new JsonException("control character in string", pos);
            if (c == '\\') {
                if (sb == null) sb = new StringBuilder();
                sb.append(s, start, pos);
                pos++;
                sb.append(escape());
                start = pos;
            } else {
                pos++;
            }
        }
    }

    private char escape() throws JsonException {
        char e = next();
        switch (e) {
            case '"':
                return '"';
            case '\\':
                return '\\';
            case '/':
                return '/';
            case 'b':
                return '\b';
            case 'f':
                return '\f';
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 't':
                return '\t';
            case 'u':
                if (pos + 4 > s.length()) throw new JsonException("short \\u escape", pos);
                int code = 0;
                for (int i = 0; i < 4; i++) {
                    int d = Character.digit(s.charAt(pos + i), 16);
                    if (d < 0) throw new JsonException("bad \\u escape", pos);
                    code = code * 16 + d;
                }
                pos += 4;
                return (char) code;
            default:
                throw new JsonException("bad escape \\" + e, pos - 1);
        }
    }

    private Object number() throws JsonException {
        int start = pos;
        if (peek() == '-') pos++;
        if (peek() == '0') {
            pos++;
        } else if (isDigit(peek())) {
            while (isDigit(peek())) pos++;
        } else {
            throw new JsonException("bad number", start);
        }
        boolean integral = true;
        if (peek() == '.') {
            integral = false;
            pos++;
            if (!isDigit(peek())) throw new JsonException("bad fraction", pos);
            while (isDigit(peek())) pos++;
        }
        if (peek() == 'e' || peek() == 'E') {
            integral = false;
            pos++;
            if (peek() == '+' || peek() == '-') pos++;
            if (!isDigit(peek())) throw new JsonException("bad exponent", pos);
            while (isDigit(peek())) pos++;
        }
        String text = s.substring(start, pos);
        if (integral && text.length() <= 18) return Long.parseLong(text);
        double d = Double.parseDouble(text);
        if (Double.isInfinite(d)) throw new JsonException("number out of range", start);
        return d;
    }

    private Object literal(String word, Object value) throws JsonException {
        if (!s.startsWith(word, pos)) throw new JsonException("bad literal", pos);
        pos += word.length();
        return value;
    }

    private void skipWhitespace() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            pos++;
        }
    }

    private char peek() {
        return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private char next() throws JsonException {
        if (pos >= s.length()) throw new JsonException("unexpected end", pos);
        return s.charAt(pos++);
    }

    private void expect(char c) throws JsonException {
        if (next() != c) throw new JsonException("expected '" + c + "'", pos - 1);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
