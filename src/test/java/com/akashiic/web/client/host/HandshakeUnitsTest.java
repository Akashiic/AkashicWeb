package com.akashiic.web.client.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Token, HELLO parsing, child environment and command line: the pieces of the launch handshake. */
class HandshakeUnitsTest {

    @Test
    void tokensAre128BitLowerHexAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String t = HostToken.generate();
            assertEquals(32, t.length());
            assertTrue(t.matches("[0-9a-f]{32}"), t);
            assertTrue(seen.add(t), "duplicate token");
        }
    }

    @Test
    void tokenComparison() {
        String t = HostToken.generate();
        assertTrue(HostToken.matches(t, new String(t.toCharArray())));
        assertFalse(HostToken.matches(t, t.substring(1)));
        assertFalse(HostToken.matches(t, t + "0"));
        assertFalse(HostToken.matches(t, ""));
        assertFalse(HostToken.matches(t, null));
        assertFalse(HostToken.matches(null, t));
        assertFalse(HostToken.matches("", ""), "an empty expected token never matches");
        char[] c = t.toCharArray();
        c[31] = c[31] == 'a' ? 'b' : 'a';
        assertFalse(HostToken.matches(t, new String(c)));
        // The candidate is indexed modulo its length; a repeating prefix must still fail on the length.
        assertFalse(HostToken.matches("abab", "ab"));
    }

    @Test
    void helloIsParsedAndSanitised() throws Exception {
        String[] token = new String[1];
        HelloInfo h = HelloInfo.parse(
            "{\"token\":\"abc\",\"protoMajor\":1,\"protoMinor\":2,\"hostVersion\":\"0.1.0\",\"engine\":\"electron\","
                + "\"engineVersion\":\"44.7.0\",\"chromeVersion\":\"152.0.7977.130\",\"platform\":\"win32-x64\\n\\u0007\","
                + "\"pid\":4242,\"extra\":{\"future\":true}}",
            token);
        assertEquals("abc", token[0]);
        assertEquals(1, h.protoMajor);
        assertEquals(2, h.protoMinor);
        assertEquals("44.7.0", h.engineVersion);
        assertEquals("win32-x64??", h.platform);
        assertEquals(4242, h.pid);
        assertFalse(
            h.toString()
                .contains("abc"),
            "the token never reaches toString");
        HelloInfo minimal = HelloInfo.parse("{\"token\":\"t\",\"protoMajor\":1}", token);
        assertEquals("?", minimal.hostVersion);
        assertEquals(-1, minimal.pid);
        String longVersion = new String(new char[500]).replace('\0', 'v');
        assertEquals(
            HelloInfo.MAX_FIELD,
            HelloInfo
                .parse("{\"token\":\"t\",\"protoMajor\":1,\"hostVersion\":\"" + longVersion + "\"}", token).hostVersion
                    .length());
    }

    @Test
    void malformedHellosAreProtocolErrors() {
        String[] token = new String[1];
        String[] bad = { "", "[]", "{\"protoMajor\":1}", "{\"token\":5,\"protoMajor\":1}", "{\"token\":\"t\"}",
            "{\"token\":\"t\",\"protoMajor\":\"1\"}", "{\"token\":\"t\",\"protoMajor\":-1}",
            "{\"token\":\"t\",\"protoMajor\":1.5}", "{\"token\":\"t\",\"protoMajor\":70000}", "{\"token\":\"t\"" };
        for (String s : bad) {
            assertThrows(HostProtocol.ProtocolException.class, () -> HelloInfo.parse(s, token), s);
        }
    }

    @Test
    void childEnvironmentDropsNodeAndElectronVariablesCaseInsensitively() {
        Map<String, String> parent = new HashMap<>();
        parent.put("PATH", "/usr/bin");
        parent.put("SystemRoot", "C:\\Windows");
        parent.put("NODE_OPTIONS", "--inspect");
        parent.put("node_path", "x");
        parent.put("Node_Debug", "x");
        parent.put("ELECTRON_RUN_AS_NODE", "1");
        parent.put("electron_enable_logging", "1");
        parent.put("ELECTRON", "kept: no underscore");
        parent.put("NODEJS_HOME", "kept: not NODE_");
        parent.put("AKW_TOKEN", "parent's");
        parent.put("akw_token", "parent's");
        Map<String, String> env = ChildEnvironment.build(parent, "tok");
        Map<String, String> expected = new HashMap<>();
        expected.put("PATH", "/usr/bin");
        expected.put("SystemRoot", "C:\\Windows");
        expected.put("ELECTRON", "kept: no underscore");
        expected.put("NODEJS_HOME", "kept: not NODE_");
        expected.put("AKW_TOKEN", "tok");
        assertEquals(expected, env);
    }

    @Test
    void commandLineCarriesThePortPidAndPathsButNeverTheToken() {
        File user = new File("/tmp/akw user/ação");
        File log = new File("/tmp/akw logs/host.log");
        List<String> args = HostCommandLine
            .build(Arrays.asList("/opt/AkashicWebHost", "--fixed"), 51234, 777, user, log);
        assertEquals(
            Arrays.asList(
                "/opt/AkashicWebHost",
                "--fixed",
                "--akw-port=51234",
                "--akw-parent-pid=777",
                "--akw-user-data=" + user.getAbsolutePath(),
                "--akw-log=" + log.getAbsolutePath()),
            args);
        assertEquals(
            "--akw-parent-pid=0",
            HostCommandLine.build(Arrays.asList("x"), 1, -5, user, log)
                .get(2));
    }

    @Test
    void parentPidComesFromTheRuntimeName() {
        assertEquals(12345, HostCommandLine.parsePid("12345@host"));
        assertEquals(7, HostCommandLine.parsePid("7"));
        assertEquals(0, HostCommandLine.parsePid("@host"));
        assertEquals(0, HostCommandLine.parsePid("abc@host"));
        assertEquals(0, HostCommandLine.parsePid(null));
        assertEquals(0, HostCommandLine.parsePid("1234567890123456789012@h"));
        assertTrue(HostCommandLine.currentPid() > 0);
        assertNotEquals(0, HostCommandLine.currentPid());
    }
}
