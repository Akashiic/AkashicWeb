package com.akashiic.web.client.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.common.net.ScreenInput;

/** Exact wire text of every game-to-host payload (docs/PROTOCOL.md section 1). */
class HostMessagesTest {

    @Test
    void welcomeCarriesPolicyLocaleAndOptionalUserAgent() {
        SitePolicy p = new SitePolicy(
            SitePolicy.Mode.ALLOWLIST,
            Arrays.asList("YouTube.com", "", "twitch.tv"),
            Collections.singletonList("bad.example"));
        assertEquals(
            "{\"protoMajor\":1,\"policy\":{\"mode\":\"allowlist\",\"allow\":[\"youtube.com\",\"twitch.tv\"],\"block\":[\"bad.example\"]},\"locale\":\"pt-BR\"}",
            HostMessages.welcome(1, p, null, null));
        assertEquals(
            "{\"protoMajor\":1,\"policy\":{\"mode\":\"allowlist\",\"allow\":[],\"block\":[]},\"locale\":\"en-US\",\"userAgent\":\"UA/1\"}",
            HostMessages.welcome(1, null, "en-US", "UA/1"));
        assertEquals(
            "{\"mode\":\"blocklist\",\"allow\":[],\"block\":[\"x.example\"]}",
            HostMessages.policy(SitePolicy.blocklist(Collections.singletonList("x.example"))));
    }

    @Test
    void createClampsSizeFpsAndVolume() {
        assertEquals(
            "{\"width\":1280,\"height\":720,\"fps\":30,\"url\":\"https://www.youtube.com/\",\"session\":\"shared\",\"audio\":\"os\",\"volume\":1}",
            HostMessages.create(
                new CreateSpec(
                    1280,
                    720,
                    30,
                    "https://www.youtube.com/",
                    CreateSpec.Session.SHARED,
                    CreateSpec.Audio.OS,
                    1f)));
        assertEquals(
            "{\"width\":16,\"height\":4096,\"fps\":60,\"url\":\"\",\"session\":\"private\",\"audio\":\"spatial\",\"volume\":0}",
            HostMessages.create(
                new CreateSpec(
                    -5,
                    99_999,
                    500,
                    null,
                    CreateSpec.Session.PRIVATE,
                    CreateSpec.Audio.SPATIAL,
                    Float.NaN)));
    }

    @Test
    void sizesNeverExceedOneFramePayload() {
        int[] s = HostMessages.clampSize(4096, 4096);
        assertEquals(4096, s[0]);
        assertTrue((long) s[0] * s[1] <= FrameCanvas.MAX_PIXELS);
        assertTrue(HostProtocol.FrameHeader.BYTES + (long) s[0] * s[1] * 4 <= HostProtocol.MAX_PAYLOAD);
        assertArrayEquals(new int[] { 1920, 1080 }, HostMessages.clampSize(1920, 1080));
        assertArrayEquals(new int[] { 16, 16 }, HostMessages.clampSize(0, 0));
    }

    @Test
    void inputMessagesFollowTheProtocolShapes() {
        assertEquals("{\"type\":\"mouseMove\",\"x\":3,\"y\":4}", HostMessages.input(ev(InputEvent.Type.MOUSE_MOVE)));
        assertEquals(
            "{\"type\":\"mouseDown\",\"x\":3,\"y\":4,\"button\":\"left\",\"clickCount\":1}",
            HostMessages.input(ev(InputEvent.Type.MOUSE_DOWN)));
        assertEquals(
            "{\"type\":\"mouseUp\",\"x\":3,\"y\":4,\"button\":\"right\",\"clickCount\":3}",
            HostMessages.input(new InputEvent(InputEvent.Type.MOUSE_UP, 3, 4, "right", 7, 0, 0, null, 0, null)));
        assertEquals(
            "{\"type\":\"mouseDown\",\"x\":0,\"y\":0,\"button\":\"left\",\"clickCount\":1}",
            HostMessages.input(new InputEvent(InputEvent.Type.MOUSE_DOWN, 0, 0, "evil\"", 1, 0, 0, null, 0, null)));
        assertEquals(
            "{\"type\":\"wheel\",\"x\":3,\"y\":4,\"deltaX\":0,\"deltaY\":-120}",
            HostMessages.input(new InputEvent(InputEvent.Type.WHEEL, 3, 4, null, 0, Float.NaN, -120f, null, 0, null)));
        assertEquals(
            "{\"type\":\"keyDown\",\"keyCode\":\"Enter\",\"modifiers\":[\"shift\",\"control\",\"alt\",\"meta\"]}",
            HostMessages.input(
                new InputEvent(
                    InputEvent.Type.KEY_DOWN,
                    0,
                    0,
                    null,
                    0,
                    0,
                    0,
                    "Enter",
                    ScreenInput.MOD_SHIFT | ScreenInput.MOD_CTRL | ScreenInput.MOD_ALT | ScreenInput.MOD_META,
                    null)));
        assertEquals(
            "{\"type\":\"keyUp\",\"keyCode\":\"a\",\"modifiers\":[]}",
            HostMessages.input(new InputEvent(InputEvent.Type.KEY_UP, 0, 0, null, 0, 0, 0, "a", 0, null)));
        assertEquals(
            "{\"type\":\"char\",\"text\":\"ação ç $%&\"}",
            HostMessages.input(new InputEvent(InputEvent.Type.CHAR, 0, 0, null, 0, 0, 0, null, 0, "ação ç $%&")));
        char[] lots = new char[1000];
        Arrays.fill(lots, 'x');
        String capped = HostMessages
            .input(new InputEvent(InputEvent.Type.CHAR, 0, 0, null, 0, 0, 0, null, 0, new String(lots)));
        assertEquals("{\"type\":\"char\",\"text\":\"\"}".length() + HostMessages.MAX_CHAR_TEXT, capped.length());
    }

    @Test
    void navigationAudioRenderResizeSyncAndPing() {
        assertEquals(
            "{\"url\":\"https://a.example/?q=\\\"x\\\"\"}",
            HostMessages.navigate("https://a.example/?q=\"x\""));
        assertEquals("{\"action\":\"back\"}", HostMessages.action("back"));
        assertTrue(
            HostMessages.isAction("forward") && HostMessages.isAction("reload") && HostMessages.isAction("stop"));
        assertFalse(HostMessages.isAction("devtools"));
        assertFalse(HostMessages.isAction(null));
        assertEquals("{\"muted\":true,\"volume\":1}", HostMessages.audio(true, 7f));
        assertEquals("{\"muted\":false,\"volume\":0}", HostMessages.audio(false, -1f));
        assertEquals("{\"fps\":1,\"painting\":false}", HostMessages.render(0, false));
        assertEquals("{\"width\":960,\"height\":540}", HostMessages.resize(960, 540));
        assertEquals(
            "{\"targetMediaTime\":12.25,\"rate\":1.05,\"paused\":false,\"seek\":true}",
            HostMessages.sync(new SyncCommand(12.25, 1.05f, false, true)));
        assertEquals(
            "{\"targetMediaTime\":0,\"rate\":1,\"paused\":true,\"seek\":false}",
            HostMessages.sync(new SyncCommand(0, Float.NaN, true, false)));
        assertEquals(
            "{\"targetMediaTime\":3,\"rate\":2,\"paused\":false,\"seek\":false}",
            HostMessages.sync(new SyncCommand(3, 9f, false, false)));
        assertNull(HostMessages.sync(new SyncCommand(Double.NaN, 1f, false, false)));
        assertNull(HostMessages.sync(new SyncCommand(-1, 1f, false, false)));
        assertEquals("{\"nonce\":42}", HostMessages.ping(42));
    }

    @Test
    void onlyHttpUrlsAndAboutBlankLeaveTheJvm() {
        assertTrue(HostMessages.isNavigable("https://www.youtube.com/watch?v=x"));
        assertTrue(HostMessages.isNavigable("HTTP://example.com"));
        assertTrue(HostMessages.isNavigable("about:blank"));
        String[] refused = { null, "", "https://", "http://", "file:///C:/Windows", "javascript:alert(1)",
            "chrome://settings", "devtools://devtools", "data:text/html,x", "https://a.example/ b",
            "https://a.example/\n", "about:config", "ftp://x" };
        for (String u : refused) assertFalse(HostMessages.isNavigable(u), String.valueOf(u));
        char[] longUrl = new char[HostMessages.MAX_URL];
        Arrays.fill(longUrl, 'a');
        String tooLong = "https://" + new String(longUrl);
        assertFalse(HostMessages.isNavigable(tooLong));
    }

    private static InputEvent ev(InputEvent.Type type) {
        return new InputEvent(type, 3, 4, null, 1, 0, 0, null, 0, null);
    }
}
