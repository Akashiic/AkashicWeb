package com.akashiic.web.client.host;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Three-slot frame ring between the reader thread (one writer) and the render thread (one consumer), ADR-004.
 *
 * <p>
 * Roles: at most one slot is being written, at most one is READY (newest complete frame) and at most one is held by
 * the consumer; the rest are spare. Publishing while a frame is still READY drops the older one ("latest wins"), but
 * never its damage: the dirty rectangle of every published frame is ORed into one pending bounding box, and
 * {@link #takeLatest} hands the consumer that whole box. So a render thread that skips frames still uploads every
 * changed pixel. A box covering at least half of the page becomes the whole page (one upload is cheaper than a large
 * sub-rectangle with row skips, and it matches CCBlueX's threshold). A page size change makes the damage the whole
 * new page, because the consumer must reallocate its texture.
 *
 * <p>
 * Only role changes and the damage box are under the lock; the pixel copy runs outside it into a slot no one else
 * can see, so the render thread never waits for a copy.
 */
public final class FrameRing3 implements FrameRing {

    private static final int SLOTS = 3;

    private final Object lock = new Object();
    /** Spare slots; null entries are slots not allocated yet (or dropped after a size change). */
    private final FrameSlot[] spare = new FrameSlot[SLOTS];

    private int spareCount = SLOTS;
    private boolean writing;
    private FrameSlot ready;
    private FrameSlot front;

    /** Page size of the newest published frame; damage coordinates refer to it. */
    private int pageW;
    private int pageH;

    private boolean damaged;
    private int dx0;
    private int dy0;
    private int dx1;
    private int dy1;

    private long published;
    private long dropped;
    private long taken;
    private long requeued;

    /**
     * Reader thread: snapshots {@code canvas} into a free slot and publishes it with the dirty rectangle
     * {@code (x, y, w, h)} of the frame just applied. Single writer: never call from two threads at once.
     */
    public void publish(FrameCanvas canvas, int x, int y, int w, int h) {
        int cw = canvas.width();
        int ch = canvas.height();
        if (cw <= 0 || ch <= 0) return;
        FrameSlot target;
        synchronized (lock) {
            if (writing) throw new IllegalStateException("FrameRing3.publish is single-writer");
            // Invariant: spare + ready + front == 3 here, and ready/front hold at most one each, so a spare exists.
            target = spare[--spareCount];
            spare[spareCount] = null;
            writing = true;
        }
        try {
            if (target == null || target.width != cw || target.height != ch) {
                target = new FrameSlot(
                    ByteBuffer.allocateDirect(cw * ch * 4)
                        .order(ByteOrder.nativeOrder()),
                    cw,
                    ch);
            }
            ByteBuffer src = canvas.pixels()
                .duplicate();
            src.clear();
            src.limit(cw * ch * 4);
            ByteBuffer dst = target.bgra.duplicate();
            dst.clear();
            dst.put(src);
        } catch (RuntimeException | Error e) {
            // Keep the slot accounting intact (e.g. direct-memory OutOfMemoryError) before reporting the failure.
            synchronized (lock) {
                writing = false;
                spare[spareCount++] = null;
            }
            throw e;
        }
        synchronized (lock) {
            writing = false;
            if (cw != pageW || ch != pageH) {
                pageW = cw;
                pageH = ch;
                damageAll();
            } else {
                addDamage(x, y, w, h);
            }
            if (ready != null) {
                spare[spareCount++] = ready;
                dropped++;
            }
            ready = target;
            published++;
        }
    }

    @Override
    public FrameSlot takeLatest() {
        synchronized (lock) {
            if (ready == null) return null;
            if (!damaged) {
                // Identical to what the consumer already has: nothing to upload.
                spare[spareCount++] = ready;
                ready = null;
                return null;
            }
            if (front != null) {
                // One consumer thread: taking again means it is done with the previous slot.
                spare[spareCount++] = front;
            }
            front = ready;
            ready = null;
            long area = (long) (dx1 - dx0) * (dy1 - dy0);
            if (area * 2 >= (long) front.width * front.height) {
                front.damageX = 0;
                front.damageY = 0;
                front.damageW = front.width;
                front.damageH = front.height;
            } else {
                front.damageX = dx0;
                front.damageY = dy0;
                front.damageW = dx1 - dx0;
                front.damageH = dy1 - dy0;
            }
            damaged = false;
            front.bgra.clear();
            taken++;
            return front;
        }
    }

    @Override
    public void release(FrameSlot slot) {
        synchronized (lock) {
            if (slot == null || slot != front) return; // stale or double release: ignore
            spare[spareCount++] = slot;
            front = null;
        }
    }

    @Override
    public void requeue(FrameSlot slot) {
        synchronized (lock) {
            if (slot == null || slot != front) return;
            front = null;
            if (slot.width == pageW && slot.height == pageH && slot.hasDamage()) {
                addDamage(slot.damageX, slot.damageY, slot.damageW, slot.damageH);
            }
            if (ready == null) {
                ready = slot; // still the newest frame
            } else {
                spare[spareCount++] = slot;
            }
            requeued++;
        }
    }

    /** True when {@link #takeLatest} would return a frame. Lets the render thread order uploads by budget. */
    public boolean hasNew() {
        synchronized (lock) {
            return ready != null && damaged;
        }
    }

    /** Frames published, dropped unseen (overwritten while READY), taken and requeued, for /web doctor. */
    public long[] counters() {
        synchronized (lock) {
            return new long[] { published, dropped, taken, requeued };
        }
    }

    private void damageAll() {
        damaged = true;
        dx0 = 0;
        dy0 = 0;
        dx1 = pageW;
        dy1 = pageH;
    }

    private void addDamage(int x, int y, int w, int h) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = (int) Math.min(pageW, (long) x + w);
        int y1 = (int) Math.min(pageH, (long) y + h);
        if (x1 <= x0 || y1 <= y0) return;
        if (!damaged) {
            damaged = true;
            dx0 = x0;
            dy0 = y0;
            dx1 = x1;
            dy1 = y1;
        } else {
            dx0 = Math.min(dx0, x0);
            dy0 = Math.min(dy0, y0);
            dx1 = Math.max(dx1, x1);
            dy1 = Math.max(dy1, y1);
        }
    }
}
