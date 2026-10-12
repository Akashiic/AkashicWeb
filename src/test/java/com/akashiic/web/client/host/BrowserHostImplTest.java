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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.akashiic.web.common.ReasonCode;

@Timeout(120)
class BrowserHostImplTest {

    @TempDir
    File dir;

    /** Records every listener callback. */
    static final class Events implements HostListener {

        final List<String> states = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicReference<short[]> pcm = new AtomicReference<>();
        volatile long pcmPts;

        @Override
        public void onState(int screenId, String json) {
            states.add(screenId + " " + json);
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
            h.destroyScreen(id);
            assertNull(h.frames(id));
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
        int id = h.createScreen(
            new CreateSpec(64, 32, 30, "file:///etc/passwd", CreateSpec.Session.SHARED, CreateSpec.Audio.OS, 1f));
        h.navigate(id, "javascript:alert(1)");
        h.navigate(id, "https://ok.example/ with space");
        assertEquals(3, ev.events.size());
        assertEquals(id + " {\"kind\":\"blocked-url\",\"detail\":\"scheme\"}", ev.events.get(0));
        h.shutdown();
        assertEquals(
            0,
            h.supervisor()
                .launches());
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
