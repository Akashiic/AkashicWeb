package com.akashiic.web.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Both lang files carry the same keys, with the same placeholders, and every reason code has a text. */
class LangFilesTest {

    private static Map<String, String> load(String name) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = LangFilesTest.class.getResourceAsStream("/assets/akashicweb/lang/" + name)) {
            assertNotNull(in, name);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null;) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                assertTrue(eq > 0, name + ": " + line);
                String key = line.substring(0, eq);
                assertTrue(out.put(key, line.substring(eq + 1)) == null, name + ": duplicate " + key);
            }
        }
        return out;
    }

    private static int placeholders(String text) {
        int n = 0;
        for (int i = text.indexOf('%'); i >= 0; i = text.indexOf('%', i + 2)) {
            if (i + 1 < text.length() && text.charAt(i + 1) != '%') n++;
        }
        return n;
    }

    @Test
    void englishAndPortugueseMatch() throws Exception {
        Map<String, String> en = load("en_US.lang"), pt = load("pt_BR.lang");
        assertEquals(en.keySet(), pt.keySet());
        for (Map.Entry<String, String> e : en.entrySet()) {
            assertEquals(placeholders(e.getValue()), placeholders(pt.get(e.getKey())), e.getKey());
        }
        for (ReasonCode code : ReasonCode.values()) {
            assertTrue(en.containsKey(code.langKey()), code.name());
            assertTrue(placeholders(en.get(code.langKey())) <= 1, "server sends one detail argument: " + code);
        }
    }
}
