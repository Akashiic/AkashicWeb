package com.akashiic.web.client.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void writesOrderedObjectsArraysAndNumbers() {
        assertEquals(
            "{\"a\":1,\"b\":\"x\",\"c\":[true,false,null],\"d\":0.5,\"e\":{\"f\":-2}}",
            JsonWriter.object(
                "a",
                1,
                "b",
                "x",
                "c",
                Arrays.asList(true, false, null),
                "d",
                0.5f,
                "e",
                JsonWriter.map("f", -2L)));
        assertEquals("{\"r\":0.95}", JsonWriter.object("r", 0.95f), "floats are written as typed, not widened");
        assertEquals("{\"t\":1.5E20}", JsonWriter.object("t", 1.5e20));
        assertEquals("{\"t\":3}", JsonWriter.object("t", 3.0));
    }

    @Test
    void escapesEverythingJsonAndJavaScriptCareAbout() {
        String nasty = "q\"b\\n\nr\rt\t\u0001\u001f\u2028\u2029ação 😀";
        String json = JsonWriter.write(nasty);
        assertEquals("\"q\\\"b\\\\n\\nr\\rt\\t\\u0001\\u001f\\u2028\\u2029ação 😀\"", json);
        assertEquals("\"\\ud800x\"", JsonWriter.write("\ud800x"), "lone surrogates are escaped, never sent raw");
        assertEquals("\"\\udc00\"", JsonWriter.write("\udc00"));
    }

    @Test
    void rejectsNonFiniteNumbersAndUnknownTypes() {
        assertThrows(IllegalArgumentException.class, () -> JsonWriter.object("v", Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> JsonWriter.object("v", Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> JsonWriter.object("v", new Object()));
        assertThrows(IllegalArgumentException.class, () -> JsonWriter.object("odd"));
    }

    @Test
    void parsesWhatTheWriterWrites() throws Exception {
        String nasty = "q\"b\\n\nr\rt\t\u0001\u2028ação 😀";
        Map<String, Object> o = JsonParser
            .parseObject(JsonWriter.object("s", nasty, "n", -12, "f", 2.5, "l", Arrays.asList(1, "x")));
        assertEquals(nasty, o.get("s"));
        assertEquals(-12L, o.get("n"));
        assertEquals(2.5, o.get("f"));
        assertEquals(Arrays.asList(1L, "x"), o.get("l"));
    }

    @Test
    void parsesTheHostShapes() throws Exception {
        Map<String, Object> o = JsonParser.parseObject(
            " {\"token\" : \"ab\", \"protoMajor\":1, \"x\":1e3, \"y\":-0.5, \"z\":null, \"u\":\"\\u00e7\\/\"} ");
        assertEquals("ab", JsonParser.string(o, "token"));
        assertEquals(1L, JsonParser.integer(o, "protoMajor", -1));
        assertEquals(1000L, JsonParser.integer(o, "x", -1), "integral doubles count as integers");
        assertEquals(-1L, JsonParser.integer(o, "y", -1));
        assertTrue(o.containsKey("z"));
        assertNull(o.get("z"));
        assertEquals("ç/", JsonParser.string(o, "u"));
        assertNull(JsonParser.string(o, "protoMajor"));
        assertEquals(7L, JsonParser.integer(o, "missing", 7));
        List<?> big = (List<?>) JsonParser.parse("[123456789012345678901234, 0, -0]");
        assertEquals(1.2345678901234568e23, big.get(0));
    }

    @Test
    void rejectsMalformedInput() {
        String[] bad = { "", " ", "{", "}", "{\"a\":}", "{\"a\":1,}", "[1,]", "[1 2]", "{\"a\" 1}", "{a:1}", "'a'",
            "01", "1.", ".5", "-", "1e", "+1", "NaN", "Infinity", "tru", "nul", "\"abc", "\"\\x\"", "\"\\u12g4\"",
            "\"a\u0001\"", "{\"a\":1}x", "{\"a\":1,\"a\":2}", "1e999", "//c\n1", };
        for (String s : bad) {
            assertThrows(JsonParser.JsonException.class, () -> JsonParser.parse(s), "should reject: " + s);
        }
        assertThrows(JsonParser.JsonException.class, () -> JsonParser.parseObject("[1]"));
        assertThrows(JsonParser.JsonException.class, () -> JsonParser.parse(null));
    }

    @Test
    void limitsNestingDepth() throws Exception {
        StringBuilder ok = new StringBuilder();
        for (int i = 0; i < JsonParser.MAX_DEPTH; i++) ok.append('[');
        for (int i = 0; i < JsonParser.MAX_DEPTH; i++) ok.append(']');
        JsonParser.parse(ok.toString());
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 10_000; i++) deep.append('[');
        assertThrows(JsonParser.JsonException.class, () -> JsonParser.parse(deep.toString()));
    }
}
