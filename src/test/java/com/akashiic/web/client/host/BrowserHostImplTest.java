package com.akashiic.web.client.host;

import static com.akashiic.web.client.host.FakeHostFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.SitePolicy;

@Timeout(120)
class BrowserHostImplTest {

    @TempDir
    File dir;

    /**
     * Every public site allowed, as the server's default blocklist policy is. Without a policy the client-side URL
     * check denies everything (the server has not spoken yet), so tests that load pages set one first, like M5 does.
     */
    static final SitePolicy OPEN = SitePolicy.blocklist(Collections.<String>emptyList());

    /** Records every listener callback. */
    static final class Events implements HostListener {

        final List<String> states = new CopyOnWriteArrayList<>();
        final List<String> media = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicReference<short[]> pcm = new AtomicReference<>();
        volatile long pcmPts;

        @Override
        public void onState(int screenId, String json) {
            states.add(screenId + " " + json);
        }

        @Override
        public void onMedia(int screenId, String json) {
            media.add(screenId + " " + json);
        }

        @Override
        public void onEvent(int screenId, String json) {
            events.add(screenId + " " + json);
        }

        @Override
        public void onPcm(int screenId, long ptsMicros, int sampleRate, int channels, short[] interleaved, int frames) {
            pcmPts = ptsMicros;
            pcm.set(interleaved);
        }
    }

    @Test
    void screensCreatedBeforeReadyAreReplayedAndTheirFramesArrive() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "ok");
        BrowserHostImpl h = new BrowserHostImpl(
            f.config()
                .build(),
            f.clock,
            ProcessControl.forThisPlatform());
        Events ev = new Events();
        h.addListener(ev);
        h.addListener(new HostListener() {

            @Override
            public void onState(int screenId, String json) {
                throw new IllegalStateException("a broken listener must not affect the others");
            }
        });
        h.policy(OPEN);
        int id = h.createScreen(
            new CreateSpec(64, 32, 30, "https://example.com/a", CreateSpec.Session.SHARED, CreateSpec.Audio.OS, 0.5f));
        assertTrue(id > 0);
        FrameRing ring = h.frames(id);
        assertNotNull(ring);
        h.input(id, new InputEvent(InputEvent.Type.CHAR, 0, 0, null, 0, 0, 0, null, 0, "dropped before READY"));
        try {
            h.start();
            await("READY", 60_000, () -> h.status().kind == HostStatus.Kind.READY);
            await("first frame", 20_000, ((FrameRing3) ring)::hasNew);
            FrameSlot slot = ring.takeLatest();
            assertEquals(64, slot.width);
            assertEquals(32, slot.height);
            assertEquals(0, slot.damageX);
            assertEquals(64, slot.damageW);
            assertPixel(slot, id, 10, 7);
            assertPixel(slot, id, 63, 31);
            ring.release(slot);

            await("STATE", 10_000, () -> !ev.states.isEmpty());
            assertEquals(id + " {\"url\":\"fake\",\"title\":\"Fake ação\",\"loading\":false}", ev.states.get(0));
            await("PCM", 10_000, () -> ev.pcm.get() != null);
            assertEquals(123456L, ev.pcmPts);
            assertTrue(Arrays.equals(new short[] { 1, -1, 300, -300 }, ev.pcm.get()));

            List<String> host = only(f);
            assertTrue(
                host.get(0)
                    .startsWith("recv 32 0 0 "),
                host.toString());
            assertEquals(
                "recv 33 " + id
                    + " 0 {\"width\":64,\"height\":32,\"fps\":30,\"url\":\"https://example.com/a\",\"session\":\"shared\",\"audio\":\"os\",\"volume\":0.5}",
                host.get(1));
            assertTrue(
                host.stream()
                    .noneMatch(l -> l.startsWith("recv 36 ")),
                "input before READY must be dropped");
        } finally {
            h.shutdown();
        }
    }

    @Test
    void hostRestartReplaysCreateWithTheLatestUrlSizeAudioAndRender() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "ok");
        BrowserHostImpl h = new BrowserHostImpl(
            f.config()
                .build(),
            f.clock,
            ProcessControl.forThisPlatform());
        try {
            h.policy(OPEN);
            h.start();
            await("READY", 60_000, () -> h.status().kind == HostStatus.Kind.READY);
            int id = h.createScreen(
                new CreateSpec(
                    64,
                    32,
                    30,
                    "https://example.com/a",
                    CreateSpec.Session.SHARED,
                    CreateSpec.Audio.OS,
                    1f));
            FrameRing ring = h.frames(id);
            h.navigate(id, "https://example.com/b");
            h.audio(id, true, 0.25f);
            h.render(id, 5, true);
            h.resize(id, 48, 24);
            h.action(id, "stop"); // the fake host "crashes" on stop
            await(
                "relaunch",
                60_000,
                () -> h.supervisor()
                    .launches() == 2 && h.status().kind == HostStatus.Kind.READY);
            assertEquals(Arrays.asList(1000L), f.clock.sleeps());
            await("replayed RENDER", 20_000, () -> {
                List<List<String>> runs = runs(f);
                return runs.size() == 2 && runs.get(1)
                    .stream()
                    .anyMatch(l -> l.startsWith("recv 39 "));
            });
            List<String> second = new ArrayList<>();
            for (String l : runs(f).get(1)) if (l.startsWith("recv ")) second.add(l);
            assertTrue(
                second.get(0)
                    .startsWith("recv 32 0 0 "),
                second.toString());
            assertEquals(
                "recv 33 " + id
                    + " 0 {\"width\":48,\"height\":24,\"fps\":5,\"url\":\"https://example.com/b\",\"session\":\"shared\",\"audio\":\"os\",\"volume\":0.25}",
                second.get(1));
            assertEquals("recv 38 " + id + " 1 {\"muted\":true,\"volume\":0.25}", second.get(2));
            assertEquals("recv 39 " + id + " 2 {\"fps\":5,\"painting\":true}", second.get(3));
            assertSame(ring, h.frames(id), "the ring survives host restarts");
            await("frame from the new host", 20_000, ((FrameRing3) ring)::hasNew);
            FrameSlot slot = ring.takeLatest();
            assertEquals(48, slot.width);
            ring.release(slot);
            assertTrue(
                h.diagnostics()
                    .stream()
                    .anyMatch(d -> d.startsWith("screen " + id + ": 48x24")),
                h.diagnostics()
                    .toString());
        } finally {
            h.shutdown();
        }
    }

    @Test
    void destroyedScreensStopReceivingAndUnknownIdsAreIgnored() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "ok");
        BrowserHostImpl h = new BrowserHostImpl(
            f.config()
                .build(),
            f.clock,
            ProcessControl.forThisPlatform());
        Events ev = new Events();
        h.addListener(ev);
        h.policy(OPEN);
        try {
            h.start();
            await("READY", 60_000, () -> h.status().kind == HostStatus.Kind.READY);
            int id = h.createScreen(
                new CreateSpec(
                    32,
                    32,
                    30,
                    "https://example.com/",
                    CreateSpec.Session.PRIVATE,
                    CreateSpec.Audio.SPATIAL,
                    1f));
            FrameRing3 ring = (FrameRing3) h.frames(id);
            await("the first frame", 20_000, () -> ring.counters()[0] == 1);
            await("the PCM after the first frame and STATE", 20_000, () -> ev.pcm.get() != null);
            h.destroyScreen(id);
            assertNull(h.frames(id));
            // Review finding (medium): the fake host answers DESTROY with a late FRAME, then a STATE. Once the STATE
            // is in, the FRAME was read before it, and it must not have reached the destroyed screen's ring. W3-0:
            // the late STATE itself is dropped too (counted, never delivered).
            await("the late STATE, dropped", 20_000, () -> h.droppedForUnknownScreens() >= 1);
            assertEquals(1, ring.counters()[0], "a FRAME for a destroyed screen is discarded");
            assertTrue(
                ev.states.stream()
                    .noneMatch(s -> s.endsWith("{\"late\":true}")),
                ev.states.toString());
            assertTrue(
                h.diagnostics()
                    .contains("dropped for unknown screens: state 1, media 0, event 0, pcm 0"),
                h.diagnostics()
                    .toString());
            h.navigate(999, "https://example.com/");
            h.audio(999, false, 1f);
            h.input(999, new InputEvent(InputEvent.Type.MOUSE_MOVE, 1, 1, null, 0, 0, 0, null, 0, null));
            await(
                "DESTROY at the host",
                20_000,
                () -> only(f).stream()
                    .anyMatch(l -> l.startsWith("recv 34 " + id + " ")));
            assertTrue(
                only(f).stream()
                    .noneMatch(l -> l.startsWith("recv 35 999 ")));
            assertTrue(
                only(f).stream()
                    .anyMatch(l -> l.contains("\"session\":\"private\",\"audio\":\"spatial\"")));
        } finally {
            h.shutdown();
        }
    }

    /**
     * W3-0: STATE, MEDIA, EVENT and PCM of a screen the game never created are dropped and counted; a host-level
     * EVENT on screen 0 (policy-error) still reaches the listeners, a STATE on screen 0 does not.
     */
    @Test
    void messagesForUnknownScreensAreDroppedButHostLevelEventsPass() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "strays");
        BrowserHostImpl h = new BrowserHostImpl(
            f.config()
                .build(),
            f.clock,
            ProcessControl.forThisPlatform());
        Events ev = new Events();
        h.addListener(ev);
        try {
            h.start();
            await("the host-level EVENT", 60_000, () -> !ev.events.isEmpty());
            assertEquals(
                Collections.singletonList(
                    "0 {\"kind\":\"policy-error\",\"detail\":{\"error\":\"x\",\"applied\":\"deny-all\"}}"),
                ev.events);
            assertTrue(ev.states.isEmpty(), ev.states.toString());
            assertTrue(ev.media.isEmpty(), ev.media.toString());
            assertNull(ev.pcm.get());
            assertEquals(5, h.droppedForUnknownScreens());
            assertTrue(
                h.diagnostics()
                    .contains("dropped for unknown screens: state 2, media 1, event 1, pcm 1"),
                h.diagnostics()
                    .toString());
        } finally {
            h.shutdown();
        }
    }

    @Test
    void nonHttpUrlsAreRefusedLocallyWithABlockedUrlEvent() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "ok");
        BrowserHostImpl h = new BrowserHostImpl(
            f.config()
                .build(),
            f.clock,
            ProcessControl.forThisPlatform());
        Events ev = new Events();
        h.addListener(ev);
        h.policy(OPEN);
        int id = h.createScreen(
            new CreateSpec(64, 32, 30, "file:///etc/passwd", CreateSpec.Session.SHARED, CreateSpec.Audio.OS, 1f));
        h.navigate(id, "javascript:alert(1)");
        h.navigate(id, "https://ok.example/ with space");
        assertEquals(3, ev.events.size());
        // The detail carries the ReasonCode next to the host-style reason (task: refuse with the matching code).
        assertEquals(
            id + " {\"kind\":\"blocked-url\",\"detail\":{\"reason\":\"scheme\",\"code\":\"URL_INVALID\",\"host\":\"\",\"via\":\"client\"}}",
            ev.events.get(0));
        assertEquals(
            id + " {\"kind\":\"blocked-url\",\"detail\":{\"reason\":\"malformed\",\"code\":\"URL_INVALID\",\"host\":\"\",\"via\":\"client\"}}",
            ev.events.get(2));
        h.shutdown();
        assertEquals(
            0,
            h.supervisor()
                .launches());
    }

    /**
     * Task item 3 (CLAUDE.md: site policy and internal-address block on both server and client): URLs are checked
     * with the server's UrlGate before they leave the JVM. Refused ones never reach the host; the listeners get a
     * local blocked-url EVENT with URL_INTERNAL, URL_BLOCKED or URL_INVALID; until a policy arrives everything is
     * denied.
     */
    @Test
    void urlsAreCheckedAgainstTheSitePolicyAndTheInternalBlockBeforeLeavingTheJvm() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "ok");
        BrowserHostImpl h = new BrowserHostImpl(
            f.config()
                .build(),
            f.clock,
            ProcessControl.forThisPlatform());
        Events ev = new Events();
        h.addListener(ev);
        try {
            h.start();
            await("READY", 60_000, () -> h.status().kind == HostStatus.Kind.READY);
            // No policy from the server yet: deny-all, so the screen starts blank.
            int id = h.createScreen(
                new CreateSpec(32, 16, 30, "https://example.com/", CreateSpec.Session.SHARED, CreateSpec.Audio.OS, 0f));
            assertEquals(
                id + " {\"kind\":\"blocked-url\",\"detail\":{\"reason\":\"not-allowed\",\"code\":\"URL_BLOCKED\",\"host\":\"example.com\",\"via\":\"client\"}}",
                ev.events.get(0));

            h.policy(SitePolicy.blocklist(Collections.singletonList("bad.example")));
            h.navigate(id, "https://example.com/ok");
            h.navigate(id, "http://127.0.0.1:8080/admin");
            h.navigate(id, "http://localhost/");
            h.navigate(id, "http://[::1]/");
            h.navigate(id, "http://2130706433/"); // 127.0.0.1 as one decimal number (Chromium accepts it)
            h.navigate(id, "https://www.bad.example/");
            h.navigate(id, "http://allowed.example\\@evil.example/");
            h.navigate(id, "https://user@example.com/");
            h.navigate(id, "https://example.com/" + (char) 0x200B); // invisible: normalisation drops it
            h.navigate(id, "https://example.com/last");
            List<String> codes = new ArrayList<>();
            for (String e : ev.events.subList(1, ev.events.size())) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"code\":\"([A-Z_]+)\"")
                    .matcher(e);
                codes.add(m.find() ? m.group(1) : e);
            }
            assertEquals(
                Arrays.asList(
                    "URL_INTERNAL",
                    "URL_INTERNAL",
                    "URL_INTERNAL",
                    "URL_INTERNAL",
                    "URL_BLOCKED",
                    "URL_INVALID",
                    "URL_INVALID",
                    "URL_INVALID"),
                codes);
            assertTrue(
                ev.events.get(1)
                    .contains("\"reason\":\"internal\",\"code\":\"URL_INTERNAL\",\"host\":\"127.0.0.1\""),
                ev.events.get(1));
            assertTrue(
                ev.events.get(5)
                    .contains("\"reason\":\"blocked\""),
                ev.events.get(5));

            await(
                "the last allowed NAVIGATE at the host",
                20_000,
                () -> only(f).stream()
                    .anyMatch(l -> l.contains("https://example.com/last")));
            List<String> sent = new ArrayList<>();
            for (String l : only(f)) if (l.startsWith("recv 33 ") || l.startsWith("recv 35 ")) sent.add(l);
            assertEquals(3, sent.size(), sent.toString());
            assertTrue(
                sent.get(0)
                    .contains("\"url\":\"about:blank\""),
                sent.get(0));
            assertTrue(
                sent.get(1)
                    .endsWith("{\"url\":\"https://example.com/ok\"}"),
                sent.get(1));
            assertTrue(
                sent.get(2)
                    .endsWith("{\"url\":\"https://example.com/last\"}"),
                sent.get(2));
        } finally {
            h.shutdown();
        }
    }

    /** A replay sends the URL again, so it is checked again, against the policy in force at the reconnect. */
    @Test
    void replayRechecksUrlsAgainstTheCurrentPolicy() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "ok");
        BrowserHostImpl h = new BrowserHostImpl(
            f.config()
                .build(),
            f.clock,
            ProcessControl.forThisPlatform());
        Events ev = new Events();
        h.addListener(ev);
        try {
            h.policy(OPEN);
            h.start();
            await("READY", 60_000, () -> h.status().kind == HostStatus.Kind.READY);
            int id = h.createScreen(
                new CreateSpec(
                    32,
                    16,
                    30,
                    "https://example.com/a",
                    CreateSpec.Session.SHARED,
                    CreateSpec.Audio.OS,
                    0f));
            assertTrue(ev.events.isEmpty(), ev.events.toString());
            h.policy(
                new SitePolicy(
                    SitePolicy.Mode.ALLOWLIST,
                    Collections.singletonList("other.example"),
                    Collections.<String>emptyList()));
            h.action(id, "stop"); // the fake host "crashes"; the replay meets the new policy
            await(
                "relaunch",
                60_000,
                () -> h.supervisor()
                    .launches() == 2 && h.status().kind == HostStatus.Kind.READY);
            await("the replay's CREATE", 20_000, () -> {
                List<List<String>> runs = runs(f);
                return runs.size() == 2 && runs.get(1)
                    .stream()
                    .anyMatch(l -> l.startsWith("recv 33 "));
            });
            String create = null;
            for (String l : runs(f).get(1)) if (l.startsWith("recv 33 ")) create = l;
            assertTrue(create.contains("\"url\":\"about:blank\""), create);
            await("the replay's refusal", 10_000, () -> !ev.events.isEmpty());
            assertTrue(
                ev.events.get(0)
                    .contains("\"code\":\"URL_BLOCKED\",\"host\":\"example.com\""),
                ev.events.get(0));
        } finally {
            h.shutdown();
        }
    }

    /**
     * Review findings (high, medium): listeners were isolated against RuntimeException only, so an Error from game
     * code (NoClassDefFoundError, AssertionError, StackOverflowError) escaped into the reader or the supervisor
     * thread. Each listener is now isolated against any Throwable: the others get every callback and nothing stops.
     */
    @Test
    void listenerErrorsAreIsolatedFromTheOthersAndFromTheThreads() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "ok");
        BrowserHostImpl h = new BrowserHostImpl(
            f.config()
                .build(),
            f.clock,
            ProcessControl.forThisPlatform());
        h.addListener(new HostListener() {

            @Override
            public void onStatus(HostStatus status) {
                throw new StackOverflowError("simulated");
            }

            @Override
            public void onState(int screenId, String json) {
                throw new NoClassDefFoundError("simulated");
            }

            @Override
            public void onEvent(int screenId, String json) {
                throw new AssertionError("simulated");
            }

            @Override
            public void onPcm(int screenId, long ptsMicros, int sampleRate, int channels, short[] interleaved,
                int frames) {
                throw new NoClassDefFoundError("simulated");
            }
        });
        Events ev = new Events();
        h.addListener(ev);
        h.addListener(f.statuses);
        try {
            h.start();
            await("READY", 60_000, () -> h.status().kind == HostStatus.Kind.READY);
            int id = h.createScreen(
                new CreateSpec(32, 16, 30, "about:blank", CreateSpec.Session.SHARED, CreateSpec.Audio.OS, 0f));
            await("STATE to the healthy listener", 20_000, () -> !ev.states.isEmpty());
            await("PCM to the healthy listener", 20_000, () -> ev.pcm.get() != null);
            h.navigate(id, "javascript:alert(1)"); // local blocked-url EVENT, delivered on this thread
            assertEquals(1, ev.events.size());
            assertEquals(HostStatus.Kind.READY, h.status().kind);
            assertEquals(
                1,
                h.supervisor()
                    .launches(),
                "the connection survived every listener Error");
            h.action(id, "stop"); // the fake host "crashes": the supervisor still relaunches
            await(
                "relaunch",
                60_000,
                () -> h.supervisor()
                    .launches() == 2 && h.status().kind == HostStatus.Kind.READY);
            assertTrue(f.statuses.saw(HostStatus.Kind.DOWN, ReasonCode.HOST_EXITED));
        } finally {
            h.shutdown();
        }
    }

    /**
     * Review finding (medium), end to end: a page too large to hold fails only its own screen, with a local
     * load-error EVENT naming frame-too-large; the connection stays up and the next frame of a valid size is shown.
     */
    @Test
    void aPageTooLargeFailsOnlyItsScreen() throws Exception {
        FakeHostFixture f = new FakeHostFixture(dir, "oversize");
        BrowserHostImpl h = new BrowserHostImpl(
            f.config()
                .build(),
            f.clock,
            ProcessControl.forThisPlatform());
        Events ev = new Events();
        h.addListener(ev);
        try {
            h.start();
            await("READY", 60_000, () -> h.status().kind == HostStatus.Kind.READY);
            int id = h.createScreen(
                new CreateSpec(64, 32, 30, "about:blank", CreateSpec.Session.SHARED, CreateSpec.Audio.OS, 0f));
            FrameRing3 ring = (FrameRing3) h.frames(id);
            await("the valid frame after the oversized one", 20_000, ring::hasNew);
            assertEquals(1, ev.events.size(), ev.events.toString());
            assertEquals(
                id + " {\"kind\":\"load-error\",\"detail\":{\"reason\":\"frame-too-large\",\"code\":\"TEXTURE_TOO_LARGE\","
                    + "\"width\":4097,\"height\":16,\"maxSide\":4096,\"maxPixels\":4194301}}",
                ev.events.get(0));
            FrameSlot slot = ring.takeLatest();
            assertEquals(64, slot.width);
            ring.release(slot);
            assertEquals(HostStatus.Kind.READY, h.status().kind);
            assertEquals(
                1,
                h.supervisor()
                    .launches(),
                "the connection survived the oversized page");
            assertTrue(
                h.diagnostics()
                    .stream()
                    .anyMatch(d -> d.startsWith("screen " + id + ": ") && d.endsWith(", too large and skipped 1")),
                h.diagnostics()
                    .toString());
        } finally {
            h.shutdown();
        }
    }

    @Test
    void pointerCoordinatesAreClampedToThePage() {
        InputEvent e = new InputEvent(InputEvent.Type.MOUSE_DOWN, -3, 900, "right", 2, 0, 0, null, 0, null);
        InputEvent c = BrowserHostImpl.clampToPage(e, 640, 360);
        assertEquals(0, c.x);
        assertEquals(359, c.y);
        assertEquals("right", c.button);
        assertEquals(2, c.clickCount);
        InputEvent key = new InputEvent(InputEvent.Type.KEY_DOWN, -3, 900, null, 0, 0, 0, "Enter", 0, null);
        assertSame(key, BrowserHostImpl.clampToPage(key, 640, 360));
        InputEvent inside = new InputEvent(InputEvent.Type.MOUSE_MOVE, 5, 5, null, 0, 0, 0, null, 0, null);
        assertSame(inside, BrowserHostImpl.clampToPage(inside, 640, 360));
    }

    @Test
    void statusBeforeStartIsStartingAndShutdownBeforeStartIsSafe() {
        HostLaunchConfig config = HostLaunchConfig
            .builder(
                HostExecutable.fixed(Arrays.asList("does-not-matter"), new File(dir, "u")),
                new File(dir, "logs"),
                new File(dir, "state"))
            .build();
        BrowserHostImpl h = new BrowserHostImpl(config);
        assertEquals(HostStatus.Kind.STARTING, h.status().kind);
        h.shutdown();
        assertEquals(HostStatus.Kind.DOWN, h.status().kind);
        assertEquals(ReasonCode.OK, h.status().reason);
        h.reenable(); // after shutdown: no effect, no exception
        assertEquals(
            0,
            h.supervisor()
                .launches());
    }

    private static void assertPixel(FrameSlot slot, int screenId, int x, int y) {
        int i = (y * slot.width + x) * 4;
        assertEquals((byte) screenId, slot.bgra.get(i), "B at " + x + "," + y);
        assertEquals((byte) x, slot.bgra.get(i + 1), "G at " + x + "," + y);
        assertEquals((byte) y, slot.bgra.get(i + 2), "R at " + x + "," + y);
        assertEquals((byte) 0xFF, slot.bgra.get(i + 3), "A at " + x + "," + y);
    }

    private static List<String> only(FakeHostFixture f) {
        List<List<String>> runs = runs(f);
        List<String> out = new ArrayList<>();
        if (runs.isEmpty()) return out;
        for (String l : runs.get(0)) if (l.startsWith("recv ")) out.add(l);
        return out;
    }

    private static List<List<String>> runs(FakeHostFixture f) {
        try {
            List<List<String>> out = new ArrayList<>();
            for (Map.Entry<String, List<String>> e : f.records()
                .entrySet()) out.add(e.getValue());
            return out;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
