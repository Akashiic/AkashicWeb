package com.akashiic.web.client.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * FrameCanvas + FrameRing3 (ADR-004). The property tests drive a host "page" (a reference image) with random dirty
 * rectangles and a render thread that uploads only the damage it is given into its own "texture", skipping, delaying
 * and requeueing takes at random; after every upload the texture must equal the newest published page exactly.
 */
class FrameRing3Test {

    // --- canvas -------------------------------------------------------------------------------------------------

    @Test
    void canvasAppliesCropsFromArraysAndStreams() throws Exception {
        FrameCanvas c = new FrameCanvas();
        Page page = new Page(7, 5);
        page.paint(new Random(1), 0, 0, 7, 5);
        c.apply(page.header(0, 0, 7, 5), page.payload(0, 0, 7, 5), HostProtocol.FrameHeader.BYTES);
        assertArrayEquals(page.px, read(c));
        assertFalse(c.ensureSize(7, 5), "same size keeps the canvas");

        // Partial rect through the stream path, with a scratch smaller than one row.
        page.paint(new Random(2), 2, 1, 4, 3);
        byte[] payload = page.payload(2, 1, 4, 3);
        DataInputStream in = new DataInputStream(
            new ByteArrayInputStream(
                payload,
                HostProtocol.FrameHeader.BYTES,
                payload.length - HostProtocol.FrameHeader.BYTES));
        c.read(page.header(2, 1, 4, 3), in, new byte[5]);
        assertArrayEquals(page.px, read(c));

        // Full-width rect: the contiguous path.
        page.paint(new Random(3), 0, 3, 7, 2);
        byte[] rows = page.payload(0, 3, 7, 2);
        c.read(
            page.header(0, 3, 7, 2),
            new DataInputStream(new ByteArrayInputStream(rows, 12, rows.length - 12)),
            new byte[9]);
        assertArrayEquals(page.px, read(c));
    }

    @Test
    void canvasResizesAndRefusesOversizedPages() throws Exception {
        FrameCanvas c = new FrameCanvas();
        assertTrue(c.ensureSize(10, 10));
        assertTrue(c.ensureSize(20, 10));
        assertEquals(20, c.width());
        assertEquals(
            ByteOrder.nativeOrder(),
            c.pixels()
                .order());
        assertThrows(HostProtocol.ProtocolException.class, () -> c.ensureSize(65535, 65535));
        assertThrows(HostProtocol.ProtocolException.class, () -> c.ensureSize(4096, 1025));
        assertThrows(HostProtocol.ProtocolException.class, () -> c.ensureSize(0, 10));
        c.ensureSize(4096, 1023); // largest 4096-wide page that fits one FRAME
        // Review finding (low): the pixel cap alone let 65535x64 through, wider than any GL texture.
        assertThrows(HostProtocol.ProtocolException.class, () -> c.ensureSize(65535, 64));
        assertThrows(HostProtocol.ProtocolException.class, () -> c.ensureSize(4097, 16));
        assertThrows(HostProtocol.ProtocolException.class, () -> c.ensureSize(16, 4097));
        assertTrue(FrameCanvas.fits(4096, 1023));
        assertTrue(FrameCanvas.fits(1023, 4096));
        assertFalse(FrameCanvas.fits(4096, 1024), "4,194,304 pixels: 3 over the one-payload cap");
        assertFalse(FrameCanvas.fits(2880, 1620), "a 1080p screen painted at 150% display scale");
        assertFalse(FrameCanvas.fits(4097, 1));
        assertFalse(FrameCanvas.fits(-1, 10));
        assertEquals(4096, FrameSlot.MAX_DIMENSION);
        assertThrows(
            IllegalArgumentException.class,
            () -> new FrameSlot(java.nio.ByteBuffer.allocateDirect(4097 * 4), 4097, 1));
        assertThrows(IllegalArgumentException.class, () -> new FrameSlot(java.nio.ByteBuffer.allocate(16), -2, -2));
        assertEquals(4096, new FrameSlot(java.nio.ByteBuffer.allocate(4096 * 4), 4096, 1).width);
    }

    // --- ring basics ----------------------------------------------------------------------------------------------

    @Test
    void nothingNewMeansNull() {
        FrameRing3 ring = new FrameRing3();
        assertNull(ring.takeLatest());
        assertFalse(ring.hasNew());
    }

    @Test
    void firstFrameIsWholePageAndSlotsAreDirectNativeBuffers() throws Exception {
        Fixture f = new Fixture(40, 20);
        f.paintAndPublish(new Random(4), 3, 4, 2, 2);
        assertTrue(f.ring.hasNew());
        FrameSlot s = f.ring.takeLatest();
        assertTrue(s.bgra.isDirect());
        assertEquals(ByteOrder.nativeOrder(), s.bgra.order());
        assertEquals(40 * 20 * 4, s.bgra.remaining());
        assertDamage(s, 0, 0, 40, 20); // page size "changed" from nothing
        assertArrayEquals(f.page.px, read(s));
        f.ring.release(s);
        assertNull(f.ring.takeLatest());
    }

    @Test
    void skippedFramesAccumulateDamage() throws Exception {
        Fixture f = new Fixture(100, 100);
        f.paintAndPublish(new Random(5), 0, 0, 100, 100);
        f.ring.release(f.ring.takeLatest());
        f.paintAndPublish(new Random(6), 10, 10, 5, 5);
        f.paintAndPublish(new Random(7), 30, 40, 10, 2); // drops the previous READY frame, keeps its damage
        FrameSlot s = f.ring.takeLatest();
        assertDamage(s, 10, 10, 30, 32);
        assertArrayEquals(f.page.px, read(s));
        assertEquals(1, f.ring.counters()[1], "one frame dropped unseen");
        f.ring.release(s);
    }

    @Test
    void damageOfHalfThePageOrMoreBecomesTheWholePage() throws Exception {
        Fixture f = new Fixture(10, 10);
        f.paintAndPublish(new Random(8), 0, 0, 10, 10);
        f.ring.release(f.ring.takeLatest());
        f.paintAndPublish(new Random(9), 0, 0, 10, 4); // 40 %
        assertDamage(f.takeAndRelease(), 0, 0, 10, 4);
        f.paintAndPublish(new Random(10), 0, 0, 10, 5); // 50 %
        assertDamage(f.takeAndRelease(), 0, 0, 10, 10);
        f.paintAndPublish(new Random(11), 0, 0, 1, 1);
        f.paintAndPublish(new Random(12), 9, 9, 1, 1); // two pixels, but their box covers everything
        assertDamage(f.takeAndRelease(), 0, 0, 10, 10);
    }

    @Test
    void sizeChangeDamagesTheWholeNewPage() throws Exception {
        Fixture f = new Fixture(10, 10);
        f.paintAndPublish(new Random(13), 0, 0, 10, 10);
        FrameSlot old = f.ring.takeLatest();
        f.resize(16, 8);
        f.paintAndPublish(new Random(14), 1, 1, 2, 2); // the canvas is new and mostly zero
        FrameSlot s = f.ring.takeLatest(); // implicitly releases `old`
        assertNotSame(old, s);
        assertEquals(16, s.width);
        assertDamage(s, 0, 0, 16, 8);
        assertArrayEquals(f.page.px, read(s));
        f.ring.release(old); // stale release is ignored
        f.ring.release(s);
    }

    @Test
    void requeueKeepsDamageForTheNextTake() throws Exception {
        Fixture f = new Fixture(50, 50);
        f.paintAndPublish(new Random(15), 0, 0, 50, 50);
        f.ring.release(f.ring.takeLatest());
        f.paintAndPublish(new Random(16), 5, 5, 3, 3);
        FrameSlot s = f.ring.takeLatest();
        f.ring.requeue(s); // budget exhausted: not uploaded
        assertTrue(f.ring.hasNew());
        FrameSlot again = f.ring.takeLatest();
        assertSame(s, again, "still the newest frame");
        assertDamage(again, 5, 5, 3, 3);
        f.ring.requeue(again);
        f.paintAndPublish(new Random(17), 20, 20, 2, 2);
        FrameSlot merged = f.ring.takeLatest();
        assertDamage(merged, 5, 5, 17, 17);
        assertArrayEquals(f.page.px, read(merged));
        assertEquals(2, f.ring.counters()[3]);
    }

    @Test
    void identicalRepublishIsNotNew() throws Exception {
        Fixture f = new Fixture(8, 8);
        f.paintAndPublish(new Random(18), 0, 0, 8, 8);
        f.ring.release(f.ring.takeLatest());
        f.ring.publish(f.canvas, 0, 0, 0, 0); // empty rect: nothing changed
        assertFalse(f.ring.hasNew());
        assertNull(f.ring.takeLatest());
    }

    // --- properties -----------------------------------------------------------------------------------------------

    @Test
    void randomizedRenderLoopNeverLosesAPixel() throws Exception {
        for (long seed = 1; seed <= 40; seed++) runRandomized(seed, 600);
    }

    private static void runRandomized(long seed, int steps) throws Exception {
        Random rnd = new Random(seed);
        Fixture f = new Fixture(1 + rnd.nextInt(40), 1 + rnd.nextInt(30));
        Texture tex = new Texture();
        int[] published = null;
        FrameSlot held = null;
        for (int step = 0; step < steps; step++) {
            int op = rnd.nextInt(100);
            if (op < 3) {
                f.resize(1 + rnd.nextInt(40), 1 + rnd.nextInt(30));
            } else if (op < 55) {
                int x = rnd.nextInt(f.page.w), y = rnd.nextInt(f.page.h);
                int w = 1 + rnd.nextInt(f.page.w - x), h = 1 + rnd.nextInt(f.page.h - y);
                f.paintAndPublish(rnd, x, y, w, h);
                published = f.page.px.clone();
            } else {
                FrameSlot s = f.ring.takeLatest(); // may implicitly release `held`
                if (s == null) {
                    if (published != null) assertArrayEquals(published, tex.px, "seed " + seed + " step " + step);
                    continue;
                }
                assertTrue(s.hasDamage());
                long area = (long) s.damageW * s.damageH;
                assertTrue(
                    (s.damageW == s.width && s.damageH == s.height) || area * 2 < (long) s.width * s.height,
                    "damage >= 50% must be the whole page");
                if (rnd.nextInt(10) == 0) {
                    f.ring.requeue(s); // skipped upload: must not lose damage
                    held = null;
                    continue;
                }
                tex.upload(s);
                assertArrayEquals(published, tex.px, "seed " + seed + " step " + step);
                if (rnd.nextBoolean()) {
                    f.ring.release(s);
                    held = null;
                } else {
                    held = s; // keep it a while; the next take releases it implicitly
                }
            }
        }
        if (held != null) f.ring.release(held);
    }

    @Test
    @Timeout(60)
    void concurrentWriterAndRenderThreadSeeOnlyWholePublishedFrames() throws Exception {
        final int w = 37, h = 23, frames = 3000;
        Fixture f = new Fixture(w, h);
        Map<Integer, Integer> indexByHash = new ConcurrentHashMap<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                Random rnd = new Random(99);
                for (int i = 0; i < frames; i++) {
                    int x = rnd.nextInt(w), y = rnd.nextInt(h);
                    int rw = 1 + rnd.nextInt(w - x), rh = 1 + rnd.nextInt(h - y);
                    int[] before = f.page.px.clone();
                    f.page.paint(rnd, x, y, rw, rh);
                    f.page.px[0] = i; // every published page is distinct
                    if (x != 0 || y != 0) {
                        // Pixel 0 lies outside the rect: send it as its own paint first, as a host would. The page
                        // published in between is the previous page with only pixel 0 changed.
                        int[] between = before.clone();
                        between[0] = i;
                        indexByHash.put(Arrays.hashCode(between), i);
                        f.pipeline.receive(f.page.header(0, 0, 1, 1), f.page.payload(0, 0, 1, 1));
                    }
                    indexByHash.put(Arrays.hashCode(f.page.px), i);
                    f.pipeline.receive(f.page.header(x, y, rw, rh), f.page.payload(x, y, rw, rh));
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "test-writer");
        Texture tex = new Texture();
        int lastIndex = -1;
        int uploads = 0;
        writer.start();
        while (writer.isAlive() || f.ring.hasNew()) {
            FrameSlot s = f.ring.takeLatest();
            if (s == null) {
                Thread.yield();
                continue;
            }
            tex.upload(s);
            f.ring.release(s);
            Integer idx = indexByHash.get(Arrays.hashCode(tex.px));
            assertNotNull(idx, "the texture is not any published page (torn or lost damage)");
            assertTrue(idx >= lastIndex, "frames went backwards");
            lastIndex = idx;
            uploads++;
        }
        writer.join();
        if (failure.get() != null) fail(failure.get());
        assertNull(f.ring.takeLatest());
        assertArrayEquals(f.page.px, tex.px, "the texture ends equal to the last page");
        assertTrue(uploads > 0);
        long[] c = f.ring.counters();
        assertEquals(c[0], c[1] + c[2], "every publish was either taken or dropped unseen");
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private static void assertDamage(FrameSlot s, int x, int y, int w, int h) {
        assertNotNull(s);
        assertArrayEquals(new int[] { x, y, w, h }, new int[] { s.damageX, s.damageY, s.damageW, s.damageH });
    }

    private static int[] read(FrameCanvas c) {
        int[] out = new int[c.width() * c.height()];
        for (int i = 0; i < out.length; i++) out[i] = c.pixels()
            .getInt(i * 4);
        return out;
    }

    private static int[] read(FrameSlot s) {
        int[] out = new int[s.width * s.height];
        for (int i = 0; i < out.length; i++) out[i] = s.bgra.getInt(i * 4);
        return out;
    }

    /** The host's page: one int per pixel, sent as 4 bytes in the buffer's native order. */
    static final class Page {

        int w;
        int h;
        int[] px;

        Page(int w, int h) {
            this.w = w;
            this.h = h;
            this.px = new int[w * h];
        }

        void paint(Random rnd, int x, int y, int rw, int rh) {
            for (int j = y; j < y + rh; j++) for (int i = x; i < x + rw; i++) px[j * w + i] = rnd.nextInt();
        }

        HostProtocol.FrameHeader header(int x, int y, int rw, int rh) {
            return new HostProtocol.FrameHeader(w, h, x, y, rw, rh);
        }

        byte[] payload(int x, int y, int rw, int rh) {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(HostProtocol.FrameHeader.BYTES + rw * rh * 4)
                .order(ByteOrder.nativeOrder());
            b.position(HostProtocol.FrameHeader.BYTES);
            for (int j = y; j < y + rh; j++) for (int i = x; i < x + rw; i++) b.putInt(px[j * w + i]);
            return b.array();
        }
    }

    /** Page + pipeline (canvas + ring) as the reader thread drives them. */
    static final class Fixture {

        final Page page;
        final FramePipeline pipeline = new FramePipeline();
        final FrameRing3 ring = pipeline.ring();
        final FrameCanvas canvas;

        Fixture(int w, int h) throws Exception {
            page = new Page(w, h);
            java.lang.reflect.Field fc = FramePipeline.class.getDeclaredField("canvas");
            fc.setAccessible(true);
            canvas = (FrameCanvas) fc.get(pipeline);
        }

        void paintAndPublish(Random rnd, int x, int y, int w, int h) throws Exception {
            page.paint(rnd, x, y, w, h);
            pipeline.receive(page.header(x, y, w, h), page.payload(x, y, w, h));
        }

        /** A resized page starts zeroed, like the reallocated canvas. */
        void resize(int w, int h) {
            page.w = w;
            page.h = h;
            page.px = new int[w * h];
        }

        FrameSlot takeAndRelease() {
            FrameSlot s = ring.takeLatest();
            ring.release(s);
            return s;
        }
    }

    /** The render thread's texture: uploads only the damage rectangle; reallocation fills it with garbage. */
    static final class Texture {

        int w = -1;
        int h = -1;
        int[] px = new int[0];

        void upload(FrameSlot s) {
            if (s.width != w || s.height != h) {
                assertEquals(0, s.damageX);
                assertEquals(0, s.damageY);
                assertEquals(s.width, s.damageW, "a texture reallocation needs the whole page");
                assertEquals(s.height, s.damageH);
                w = s.width;
                h = s.height;
                px = new int[w * h];
                Arrays.fill(px, 0xDEADBEEF);
            }
            for (int j = s.damageY; j < s.damageY + s.damageH; j++) {
                for (int i = s.damageX; i < s.damageX + s.damageW; i++) px[j * w + i] = s.bgra.getInt((j * w + i) * 4);
            }
        }
    }
}
