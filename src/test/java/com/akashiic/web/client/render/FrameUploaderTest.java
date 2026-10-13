package com.akashiic.web.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akashiic.web.client.host.FrameRing;
import com.akashiic.web.client.host.FrameSlot;
import com.akashiic.web.client.screen.ClientScreen;
import com.akashiic.web.client.screen.ClientScreens;
import com.akashiic.web.client.screen.FakeHost;
import com.akashiic.web.client.screen.PlayerView;
import com.akashiic.web.client.screen.RecordingServer;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.protocol.Protocol;

/** The uploader's scheduling (ADR-004) with a recording texture and fake rings: no GL needed. */
class FrameUploaderTest {

    private static final UUID ME = new UUID(1, 1);

    /** A 3-slot-ring stand-in: the newest published slot wins and carries the damage of the ones it replaced. */
    static final class FakeRing implements FrameRing {

        final Deque<FrameSlot> published = new ArrayDeque<>();
        final Map<FrameSlot, Integer> released = new IdentityHashMap<>();
        int takes;

        void publish(FrameSlot s) {
            published.add(s);
        }

        @Override
        public FrameSlot takeLatest() {
            if (published.isEmpty()) return null;
            takes++;
            FrameSlot newest = published.pollLast();
            Rect d = FrameUploader.damageOf(newest);
            while (!published.isEmpty()) {
                FrameSlot old = published.pollFirst();
                d = d.union(FrameUploader.damageOf(old));
                released.merge(old, 1, Integer::sum); // the ring recycles superseded slots itself
            }
            newest.damageX = d.x;
            newest.damageY = d.y;
            newest.damageW = d.w;
            newest.damageH = d.h;
            return newest;
        }

        @Override
        public void release(FrameSlot slot) {
            released.merge(slot, 1, Integer::sum);
        }
    }

    /** Records allocations and uploads instead of calling GL. */
    static final class RecordingTexture implements TextureTarget {

        final List<String> ops = new ArrayList<>();
        int w;
        int h;
        boolean failNextUpload;

        @Override
        public int width() {
            return w;
        }

        @Override
        public int height() {
            return h;
        }

        @Override
        public boolean isAllocated() {
            return w > 0;
        }

        @Override
        public void allocate(int width, int height) {
            w = width;
            h = height;
            ops.add("alloc " + width + "x" + height);
        }

        @Override
        public void upload(ByteBuffer canvas, int canvasW, int canvasH, Rect r) {
            if (failNextUpload) {
                failNextUpload = false;
                throw new IllegalStateException("simulated GL failure");
            }
            ops.add("upload " + r);
        }

        @Override
        public void bind() {}

        @Override
        public void delete() {
            ops.add("delete");
        }
    }

    private FakeHost host;
    private ClientScreens screens;
    private final List<RecordingTexture> textures = new ArrayList<>();
    private int maxTexture = 16384;
    private FrameUploader uploader;
    private final long[] now = { 1_000 };

    @BeforeEach
    void setUp() {
        host = new FakeHost();
        screens = new ClientScreens(
            host,
            RecordingServer::new,
            () -> now[0],
            Runnable::run,
            ClientScreens.Notifier.NONE,
            4,
            2L);
        uploader = new FrameUploader(screens, () -> now[0], () -> {
            RecordingTexture t = new RecordingTexture();
            textures.add(t);
            return t;
        }, () -> maxTexture);
        screens.onHello(Protocol.MAJOR, Protocol.MINOR, false, SitePolicy.blocklist(Collections.<String>emptyList()));
    }

    private ClientScreen addScreen(int x, int w, int h, int resW, int resH) {
        ScreenState s = new ScreenState();
        s.key = new ScreenKey(0, x, 64, 0, 3);
        s.widthBlocks = w;
        s.heightBlocks = h;
        s.resolutionW = resW;
        s.resolutionH = resH;
        s.url = "https://a.example/";
        s.owner = ME;
        screens.onScreenState(s);
        screens.tick(new PlayerView(0, 1, 65, 5, 0, 0, -1, ME, false, 1f, 1f));
        ClientScreen cs = screens.get(s.key);
        assertNotNull(cs);
        assertTrue(cs.hostScreenId() > 0);
        return cs;
    }

    private FakeRing ring(ClientScreen s) {
        FakeRing r = new FakeRing();
        host.rings.put(s.hostScreenId(), r);
        return r;
    }

    private static FrameSlot slot(int w, int h, Rect damage) {
        FrameSlot s = new FrameSlot(
            ByteBuffer.allocateDirect(w * h * 4)
                .order(ByteOrder.nativeOrder()),
            w,
            h);
        s.damageX = damage.x;
        s.damageY = damage.y;
        s.damageW = damage.w;
        s.damageH = damage.h;
        return s;
    }

    private static RecordingTexture tex(ClientScreen s) {
        return (RecordingTexture) s.texture;
    }

    @Test
    void firstFrameAllocatesThenOnlyDamageIsUploaded() {
        ClientScreen s = addScreen(0, 2, 2, 1280, 720);
        FakeRing r = ring(s);
        FrameSlot first = slot(1280, 720, new Rect(10, 10, 5, 5));
        r.publish(first);
        uploader.uploadFrame();
        assertEquals("[alloc 1280x720, upload 0,0 1280x720]", tex(s).ops.toString());
        assertTrue(s.hasFrame);
        assertEquals(1, (int) r.released.get(first));
        FrameSlot second = slot(1280, 720, new Rect(100, 50, 200, 40));
        r.publish(second);
        uploader.uploadFrame();
        assertEquals("upload 100,50 200x40", tex(s).ops.get(2));
        uploader.uploadFrame();
        assertEquals(3, tex(s).ops.size(), "nothing new, nothing uploaded");
        assertEquals(
            2,
            uploader.stats()
                .uploaded());
    }

    @Test
    void skippedScreensKeepTheirSlotAndDamageUntilTheBudgetAllows() {
        ClientScreen big = addScreen(0, 4, 3, 1920, 1080); // closer and larger: served first
        ClientScreen small = addScreen(30, 2, 2, 1920, 1080);
        FakeRing rb = ring(big);
        FakeRing rs = ring(small);
        rb.publish(slot(1920, 1080, new Rect(0, 0, 1920, 1080)));
        FrameSlot s1 = slot(1920, 1080, new Rect(0, 0, 1920, 1080));
        rs.publish(s1);
        uploader.uploadFrame();
        assertTrue(big.hasFrame);
        assertFalse(small.hasFrame, "one 1080p frame per render frame");
        assertEquals(s1, small.heldSlot, "the slot is kept, not dropped");
        assertFalse(rs.released.containsKey(s1));
        assertEquals(1, small.skippedFrames);
        assertEquals(
            1,
            uploader.stats()
                .skippedBudget());

        // A newer paint arrives for the held screen: the held slot goes back, its damage moves to the newer one.
        FrameSlot s2 = slot(1920, 1080, new Rect(5, 5, 1, 1));
        rs.publish(s2);
        uploader.uploadFrame();
        assertEquals(1, (int) rs.released.get(s1));
        assertEquals(1, (int) rs.released.get(s2));
        assertEquals("[alloc 1920x1080, upload 0,0 1920x1080]", tex(small).ops.toString());
        assertTrue(small.hasFrame);
        assertEquals(0, small.skippedFrames);
    }

    @Test
    void carriedDamageIsUploadedFromTheNewerCanvas() {
        ClientScreen a = addScreen(0, 4, 3, 1920, 1080);
        ClientScreen b = addScreen(30, 2, 2, 1920, 1080);
        FakeRing ra = ring(a);
        FakeRing rb = ring(b);
        // Allocate both first, one per frame.
        ra.publish(slot(1920, 1080, new Rect(0, 0, 1, 1)));
        rb.publish(slot(1920, 1080, new Rect(0, 0, 1, 1)));
        uploader.uploadFrame();
        uploader.uploadFrame();
        assertTrue(a.hasFrame && b.hasFrame);
        // Now a full repaint of a fills the budget, b's small damage waits, then b repaints elsewhere.
        ra.publish(slot(1920, 1080, new Rect(0, 0, 1920, 1080)));
        rb.publish(slot(1920, 1080, new Rect(0, 0, 1920, 1080)));
        uploader.uploadFrame();
        rb.publish(slot(1920, 1080, new Rect(1000, 1000, 10, 10)));
        uploader.uploadFrame();
        String last = tex(b).ops.get(tex(b).ops.size() - 1);
        assertEquals("upload 0,0 1920x1080", last, "the skipped full damage is not lost: " + tex(b).ops);
    }

    @Test
    void staleSizeIsDroppedAndReleased() {
        ClientScreen s = addScreen(0, 2, 2, 1280, 720);
        FakeRing r = ring(s);
        FrameSlot old = slot(800, 600, new Rect(0, 0, 800, 600));
        r.publish(old);
        uploader.uploadFrame();
        assertEquals(1, (int) r.released.get(old));
        assertFalse(s.hasFrame);
        assertEquals(
            1,
            uploader.stats()
                .dropped());
        assertTrue(textures.isEmpty(), "no texture allocated for a stale frame");
    }

    /** Review medium finding: a page that keeps painting at another size used to show "Loading" forever. */
    @Test
    void framesThatNeverMatchTheRequestedSizeShowBadResolution() {
        ClientScreen s = addScreen(0, 2, 2, 1280, 720);
        FakeRing r = ring(s);
        for (int i = 0; i < FrameUploader.WRONG_SIZE_LIMIT - 1; i++) {
            r.publish(slot(1280, 360, new Rect(0, 0, 1280, 360)));
            uploader.uploadFrame();
        }
        assertEquals(null, s.failure(), "a few stale frames after a resize are normal");
        r.publish(slot(1280, 360, new Rect(0, 0, 1280, 360)));
        uploader.uploadFrame();
        assertEquals(ReasonCode.BAD_RESOLUTION, s.failure());
        assertEquals("1280x360", screens.placeholder(s).detailArgs[0]);
        r.publish(slot(1280, 720, new Rect(0, 0, 1280, 720)));
        uploader.uploadFrame();
        assertEquals(null, s.failure(), "a frame of the right size clears it");
        assertEquals(0, s.wrongSizeFrames);
    }

    /**
     * Review low finding (ADR-004 upload hygiene): a stray pixel-unpack buffer is cleared once per frame, always
     * restored.
     */
    @Test
    void theUnpackBufferScopeWrapsEachFramesUploadsEvenWhenGlThrows() {
        List<String> scopeCalls = new ArrayList<>();
        UploadScope recording = new UploadScope() {

            @Override
            public void begin() {
                scopeCalls.add("begin");
            }

            @Override
            public void end() {
                scopeCalls.add("end");
            }
        };
        uploader = new FrameUploader(screens, () -> now[0], () -> {
            RecordingTexture t = new RecordingTexture();
            textures.add(t);
            return t;
        }, () -> maxTexture, UploadBudget.DEFAULT_BYTES_PER_FRAME * 4, recording);
        ClientScreen a = addScreen(0, 2, 2, 1280, 720);
        ClientScreen b = addScreen(30, 2, 2, 1280, 720);
        ring(a).publish(slot(1280, 720, new Rect(0, 0, 1280, 720)));
        ring(b).publish(slot(1280, 720, new Rect(0, 0, 1280, 720)));
        uploader.uploadFrame();
        assertEquals("[begin, end]", scopeCalls.toString(), "once per frame, not per screen");
        uploader.uploadFrame();
        assertEquals(2, scopeCalls.size(), "nothing to upload: no GL state touched");
        tex(a).failNextUpload = true;
        ring(a).publish(slot(1280, 720, new Rect(0, 0, 4, 4)));
        uploader.uploadFrame();
        assertEquals("[begin, end, begin, end]", scopeCalls.toString(), "restored after a GL failure too");
    }

    @Test
    void glFailureShowsAReasonAndRetriesInFull() {
        ClientScreen s = addScreen(0, 2, 2, 1280, 720);
        FakeRing r = ring(s);
        r.publish(slot(1280, 720, new Rect(0, 0, 1280, 720)));
        uploader.uploadFrame();
        tex(s).failNextUpload = true;
        FrameSlot bad = slot(1280, 720, new Rect(0, 0, 4, 4));
        r.publish(bad);
        uploader.uploadFrame();
        assertEquals(1, (int) r.released.get(bad), "released even when GL throws");
        assertEquals(
            1,
            uploader.stats()
                .failures());
        assertEquals(ReasonCode.UPLOAD_FAILED.langKey(), screens.placeholder(s).key);
        r.publish(slot(1280, 720, new Rect(0, 0, 4, 4)));
        uploader.uploadFrame();
        assertEquals("upload 0,0 1280x720", tex(s).ops.get(tex(s).ops.size() - 1), "full retry after a failure");
        assertEquals(null, screens.placeholder(s), "a good frame clears the failure");
    }

    @Test
    void pagesLargerThanTheGpuAllowsAreRefused() {
        maxTexture = 1024;
        ClientScreen s = addScreen(0, 2, 2, 1280, 720);
        FakeRing r = ring(s);
        FrameSlot big = slot(1280, 720, new Rect(0, 0, 1280, 720));
        r.publish(big);
        uploader.uploadFrame();
        assertEquals(1, (int) r.released.get(big));
        assertTrue(textures.isEmpty());
        assertEquals(ReasonCode.TEXTURE_TOO_LARGE.langKey(), screens.placeholder(s).detailKey);
    }

    @Test
    void teardownReleasesAHeldSlot() {
        ClientScreen a = addScreen(0, 4, 3, 1920, 1080);
        ClientScreen b = addScreen(30, 2, 2, 1920, 1080);
        ring(a).publish(slot(1920, 1080, new Rect(0, 0, 1920, 1080)));
        FakeRing rb = ring(b);
        FrameSlot held = slot(1920, 1080, new Rect(0, 0, 1920, 1080));
        rb.publish(held);
        uploader.uploadFrame();
        assertEquals(held, b.heldSlot);
        RecordingTexture texA = tex(a);
        screens.teardownAll();
        assertEquals(1, (int) rb.released.get(held));
        assertTrue(texA.ops.contains("delete"), "GL texture deleted with the screen");
        assertEquals(null, a.texture);
    }
}
