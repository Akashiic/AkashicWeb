package com.akashiic.web.common;

/**
 * Screen multiblock rules: find the origin (bottom-left as seen from the face), measure, and check a flat rectangle
 * of screen blocks. Pure logic over a {@link BlockQuery}, so it is tested without a world.
 * <p>
 * Derived from Web Displays by montoyo (public domain), {@code net.montoyo.wd.utilities.Multiblock}
 * (findOrigin/measure/check). Changes from WD:
 * <ul>
 * <li>every walk is bounded (WD walked until it met a non-screen block, which on a server could run into unloaded
 * chunks); an unloaded block aborts with {@link ReasonCode#CHUNK_NOT_LOADED} instead of guessing;</li>
 * <li>{@link #intact} is a separate, lenient re-validation for screens that already exist: only the rectangle itself
 * must still be made of screen blocks. A block placed next to a screen therefore never dissolves it (WD destroyed
 * neighbouring screens whenever a screen block was placed beside them).</li>
 * </ul>
 */
public final class ScreenFormation {

    public static final int MIN_SIZE = 2;

    private ScreenFormation() {}

    /** Outcome of {@link #plan}. On failure {@link #reason} says why and {@link #badX} etc. may point at a block. */
    public static final class Plan {

        public final ReasonCode reason;
        public final int originX, originY, originZ;
        public final int width, height;
        /** Offending block for {@link ReasonCode#SCREEN_INVALID_SHAPE}, else the clicked block. */
        public final int badX, badY, badZ;

        Plan(ReasonCode reason, int ox, int oy, int oz, int w, int h, int bx, int by, int bz) {
            this.reason = reason;
            this.originX = ox;
            this.originY = oy;
            this.originZ = oz;
            this.width = w;
            this.height = h;
            this.badX = bx;
            this.badY = by;
            this.badZ = bz;
        }

        public boolean ok() {
            return reason == ReasonCode.OK;
        }
    }

    /** Re-validation result for an existing screen. */
    public enum Integrity {
        INTACT,
        /** At least one block of the rectangle is no longer a screen block. */
        BROKEN,
        /** Part of the rectangle is in an unloaded chunk; check again later. */
        UNKNOWN
    }

    /**
     * Plans a new screen on {@code side} containing the block at (x, y, z): walk left then down to the origin, measure
     * up and right, then check the rectangle and its border (WD rules: no holes, nothing in front or behind, no screen
     * blocks touching the edges, so the rectangle is unambiguous).
     */
    public static Plan plan(BlockQuery q, int x, int y, int z, BlockSide side, int maxWidth, int maxHeight) {
        if (q.at(x, y, z) != BlockQuery.Cell.SCREEN) return fail(ReasonCode.SCREEN_INVALID_SHAPE, x, y, z);
        // Walk left along the row, then down along the column. Bounded by the maximum size plus one: a longer run is
        // already too big, and the bound keeps the walk inside a predictable area.
        int ox = x, oy = y, oz = z;
        for (int i = 0;; i++) {
            BlockQuery.Cell c = q.at(ox - side.rightX, oy - side.rightY, oz - side.rightZ);
            if (c == BlockQuery.Cell.UNLOADED) return fail(ReasonCode.CHUNK_NOT_LOADED, x, y, z);
            if (c != BlockQuery.Cell.SCREEN) break;
            if (i >= maxWidth) return fail(ReasonCode.SCREEN_TOO_BIG, x, y, z);
            ox -= side.rightX;
            oy -= side.rightY;
            oz -= side.rightZ;
        }
        for (int i = 0;; i++) {
            BlockQuery.Cell c = q.at(ox - side.upX, oy - side.upY, oz - side.upZ);
            if (c == BlockQuery.Cell.UNLOADED) return fail(ReasonCode.CHUNK_NOT_LOADED, x, y, z);
            if (c != BlockQuery.Cell.SCREEN) break;
            if (i >= maxHeight) return fail(ReasonCode.SCREEN_TOO_BIG, x, y, z);
            ox -= side.upX;
            oy -= side.upY;
            oz -= side.upZ;
        }
        int h = run(q, ox, oy, oz, side.upX, side.upY, side.upZ, maxHeight + 1);
        int w = run(q, ox, oy, oz, side.rightX, side.rightY, side.rightZ, maxWidth + 1);
        if (h < 0 || w < 0) return fail(ReasonCode.CHUNK_NOT_LOADED, x, y, z);
        if (w < MIN_SIZE || h < MIN_SIZE) return new Plan(ReasonCode.SCREEN_TOO_SMALL, ox, oy, oz, w, h, x, y, z);
        if (w > maxWidth || h > maxHeight) return new Plan(ReasonCode.SCREEN_TOO_BIG, ox, oy, oz, w, h, x, y, z);
        int[] bad = new int[3];
        ReasonCode shape = check(q, ox, oy, oz, w, h, side, bad);
        if (shape != ReasonCode.OK) return new Plan(shape, ox, oy, oz, w, h, bad[0], bad[1], bad[2]);
        return new Plan(ReasonCode.OK, ox, oy, oz, w, h, x, y, z);
    }

    /** Number of consecutive screen blocks from the origin (inclusive) along a direction, -1 on an unloaded block. */
    private static int run(BlockQuery q, int x, int y, int z, int dx, int dy, int dz, int limit) {
        int n = 0;
        while (n < limit) {
            BlockQuery.Cell c = q.at(x + dx * n, y + dy * n, z + dz * n);
            if (c == BlockQuery.Cell.UNLOADED) return -1;
            if (c != BlockQuery.Cell.SCREEN) break;
            n++;
        }
        return n;
    }

    /**
     * WD's check: every cell of the rectangle is a screen block with no screen block directly in front or behind,
     * and no screen block touches the four edges. Writes the offending position into {@code bad}.
     */
    static ReasonCode check(BlockQuery q, int ox, int oy, int oz, int w, int h, BlockSide s, int[] bad) {
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int px = ox + s.rightX * i + s.upX * j;
                int py = oy + s.rightY * i + s.upY * j;
                int pz = oz + s.rightZ * i + s.upZ * j;
                ReasonCode r = expect(q, px, py, pz, true, bad);
                if (r != ReasonCode.OK) return r;
                r = expect(q, px + s.fwdX, py + s.fwdY, pz + s.fwdZ, false, bad);
                if (r != ReasonCode.OK) return r;
                r = expect(q, px - s.fwdX, py - s.fwdY, pz - s.fwdZ, false, bad);
                if (r != ReasonCode.OK) return r;
            }
        }
        for (int j = 0; j < h; j++) {
            int lx = ox - s.rightX + s.upX * j, ly = oy - s.rightY + s.upY * j, lz = oz - s.rightZ + s.upZ * j;
            ReasonCode r = expect(q, lx, ly, lz, false, bad);
            if (r != ReasonCode.OK) return r;
            r = expect(q, lx + s.rightX * (w + 1), ly + s.rightY * (w + 1), lz + s.rightZ * (w + 1), false, bad);
            if (r != ReasonCode.OK) return r;
        }
        for (int i = 0; i < w; i++) {
            int bx = ox - s.upX + s.rightX * i, by = oy - s.upY + s.rightY * i, bz = oz - s.upZ + s.rightZ * i;
            ReasonCode r = expect(q, bx, by, bz, false, bad);
            if (r != ReasonCode.OK) return r;
            r = expect(q, bx + s.upX * (h + 1), by + s.upY * (h + 1), bz + s.upZ * (h + 1), false, bad);
            if (r != ReasonCode.OK) return r;
        }
        return ReasonCode.OK;
    }

    private static ReasonCode expect(BlockQuery q, int x, int y, int z, boolean screen, int[] bad) {
        BlockQuery.Cell c = q.at(x, y, z);
        if (c == BlockQuery.Cell.UNLOADED) {
            bad[0] = x;
            bad[1] = y;
            bad[2] = z;
            return ReasonCode.CHUNK_NOT_LOADED;
        }
        if ((c == BlockQuery.Cell.SCREEN) != screen) {
            bad[0] = x;
            bad[1] = y;
            bad[2] = z;
            return ReasonCode.SCREEN_INVALID_SHAPE;
        }
        return ReasonCode.OK;
    }

    /** Lenient re-validation of an existing screen: only its own blocks matter (see the class comment). */
    public static Integrity intact(BlockQuery q, int ox, int oy, int oz, BlockSide s, int w, int h) {
        boolean unknown = false;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                BlockQuery.Cell c = q
                    .at(ox + s.rightX * i + s.upX * j, oy + s.rightY * i + s.upY * j, oz + s.rightZ * i + s.upZ * j);
                if (c == BlockQuery.Cell.OTHER) return Integrity.BROKEN;
                if (c == BlockQuery.Cell.UNLOADED) unknown = true;
            }
        }
        return unknown ? Integrity.UNKNOWN : Integrity.INTACT;
    }

    /** True if block (px, py, pz) is one of the blocks of the w x h screen whose origin is (ox, oy, oz). */
    public static boolean contains(int ox, int oy, int oz, BlockSide s, int w, int h, int px, int py, int pz) {
        int dx = px - ox, dy = py - oy, dz = pz - oz;
        if (s.dotForward(dx, dy, dz) != 0) return false;
        int r = s.dotRight(dx, dy, dz), u = s.dotUp(dx, dy, dz);
        return r >= 0 && r < w && u >= 0 && u < h;
    }

    /** True if two screens on the same side and plane share at least one block. */
    public static boolean overlaps(int ax, int ay, int az, int aw, int ah, int bx, int by, int bz, int bw, int bh,
        BlockSide s) {
        int dx = bx - ax, dy = by - ay, dz = bz - az;
        if (s.dotForward(dx, dy, dz) != 0) return false;
        int r = s.dotRight(dx, dy, dz), u = s.dotUp(dx, dy, dz);
        return r < aw && r + bw > 0 && u < ah && u + bh > 0;
    }

    private static Plan fail(ReasonCode reason, int x, int y, int z) {
        return new Plan(reason, x, y, z, 0, 0, x, y, z);
    }
}
