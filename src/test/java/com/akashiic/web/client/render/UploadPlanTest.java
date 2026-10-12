package com.akashiic.web.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.akashiic.web.client.render.UploadPlan.Kind;

class UploadPlanTest {

    private static final int W = 1280, H = 720, MAX = 16384;
    private static final long CAP = (long) W * H * 4;

    private static UploadPlan plan(Rect damage, int texW, int texH) {
        return UploadPlan.plan(W, H, CAP, true, damage, W, H, texW, texH, MAX);
    }

    @Test
    void smallDamageUploadsOnlyItsBoundingBox() {
        UploadPlan p = plan(new Rect(100, 50, 200, 40), W, H);
        assertEquals(Kind.PARTIAL, p.kind);
        assertEquals(new Rect(100, 50, 200, 40), p.rect);
        assertEquals(200L * 40 * 4, p.bytes());
    }

    @Test
    void halfThePageOrMoreUploadsTheWholeFrame() {
        UploadPlan p = plan(new Rect(0, 0, W, H / 2), W, H);
        assertEquals(Kind.FULL, p.kind);
        assertEquals(new Rect(0, 0, W, H), p.rect);
        assertEquals(CAP, p.bytes());
        assertEquals(Kind.PARTIAL, plan(new Rect(0, 0, W, H / 2 - 1), W, H).kind);
    }

    @Test
    void firstFrameOrResizeAllocatesAndFillsEverything() {
        UploadPlan p = plan(new Rect(10, 10, 5, 5), 0, 0);
        assertEquals(Kind.ALLOCATE, p.kind);
        assertEquals(new Rect(0, 0, W, H), p.rect);
        assertEquals(Kind.ALLOCATE, plan(Rect.EMPTY, 1920, 1080).kind, "texture still at the old size");
    }

    @Test
    void damageIsClippedAndEmptyDamageUploadsNothing() {
        UploadPlan p = plan(new Rect(W - 10, H - 10, 50, 50), W, H);
        assertEquals(Kind.PARTIAL, p.kind);
        assertEquals(new Rect(W - 10, H - 10, 10, 10), p.rect);
        assertEquals(Kind.NOTHING, plan(Rect.EMPTY, W, H).kind);
        assertEquals(Kind.NOTHING, plan(new Rect(W, 0, 10, 10), W, H).kind, "entirely outside");
        assertEquals(Kind.NOTHING, plan(new Rect(-50, -50, 10, 10), W, H).kind);
        assertEquals(Kind.NOTHING, plan(null, W, H).kind);
        assertEquals(0, plan(Rect.EMPTY, W, H).bytes());
    }

    @Test
    void staleSizeIsDroppedNotUploaded() {
        UploadPlan p = UploadPlan.plan(W, H, CAP, true, new Rect(0, 0, 1, 1), 1920, 1080, W, H, MAX);
        assertEquals(Kind.DROP_SIZE, p.kind);
        assertFalse(p.uploads());
    }

    @Test
    void impossibleSlotsNeverReachGl() {
        Rect d = new Rect(0, 0, 1, 1);
        assertEquals(Kind.DROP_INVALID, UploadPlan.plan(W, H, CAP - 1, true, d, W, H, W, H, MAX).kind, "short buffer");
        assertEquals(Kind.DROP_INVALID, UploadPlan.plan(W, H, CAP, false, d, W, H, W, H, MAX).kind, "heap buffer");
        assertEquals(Kind.DROP_INVALID, UploadPlan.plan(0, H, CAP, true, d, 0, H, 0, H, MAX).kind);
        assertEquals(Kind.DROP_INVALID, UploadPlan.plan(W, -1, CAP, true, d, W, -1, W, H, MAX).kind);
        assertEquals(Kind.DROP_INVALID, UploadPlan.plan(W, H, CAP, true, d, W, H, W, H, 1024).kind, "over max size");
    }

    @Test
    void everyUploadedRectangleFitsTheTextureAndTheBuffer() {
        java.util.Random r = new java.util.Random(11);
        for (int i = 0; i < 5_000; i++) {
            Rect d = new Rect(
                r.nextInt(3000) - 1000,
                r.nextInt(2000) - 600,
                r.nextInt(3000) - 500,
                r.nextInt(2000) - 500);
            UploadPlan p = plan(d, r.nextBoolean() ? W : 0, H);
            if (!p.uploads()) continue;
            assertTrue(p.rect.within(W, H), "rect " + p.rect + " from damage " + d);
            long lastByte = ((long) (p.rect.y + p.rect.h - 1) * W + p.rect.x + p.rect.w) * 4;
            assertTrue(lastByte <= CAP, "reads past the canvas");
        }
    }

    @Test
    void rectUnionAndClip() {
        assertEquals(new Rect(0, 0, 30, 30), new Rect(0, 0, 10, 10).union(new Rect(20, 20, 10, 10)));
        assertEquals(new Rect(5, 5, 1, 1), Rect.EMPTY.union(new Rect(5, 5, 1, 1)));
        assertEquals(new Rect(5, 5, 1, 1), new Rect(5, 5, 1, 1).union(Rect.EMPTY));
        assertEquals(new Rect(5, 5, 1, 1), new Rect(5, 5, 1, 1).union(null));
        assertEquals(Rect.EMPTY, new Rect(0, 0, 0, 5).clip(10, 10));
        assertTrue(new Rect(0, 0, -1, 5).isEmpty());
        assertEquals(0, new Rect(0, 0, -3, 5).area());
        assertFalse(new Rect(Integer.MAX_VALUE - 1, 0, 10, 10).within(100, 100), "no int overflow");
        assertEquals(Rect.EMPTY, new Rect(Integer.MAX_VALUE - 1, 0, 10, 10).clip(100, 100));
    }
}
