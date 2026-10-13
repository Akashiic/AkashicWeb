package com.akashiic.web.common;

/**
 * Size-derived screen values: the true multiblock render box (ADR-005: Angelica tests the live box of a DYNAMIC class,
 * so it must be exact), the default page resolution and the initial rotation of floor and ceiling screens.
 */
public final class ScreenGeometry {

    /** Margin around the render box so the slightly raised screen quad is never clipped (WD used 0.1). */
    public static final double BOX_MARGIN = 0.1;
    public static final int MIN_RESOLUTION = 64;

    private ScreenGeometry() {}

    /**
     * Union of the blocks of every face, as {minX, minY, minZ, maxX, maxY, maxZ}, starting from the origin block
     * itself. {@code faces} may contain nulls.
     */
    public static double[] renderBox(int x, int y, int z, Iterable<ScreenState> faces) {
        int minX = x, minY = y, minZ = z, maxX = x + 1, maxY = y + 1, maxZ = z + 1;
        for (ScreenState s : faces) {
            if (s == null) continue;
            BlockSide side = BlockSide.fromSide(s.key.side);
            if (side == null) continue;
            int w = Math.max(1, s.widthBlocks) - 1, h = Math.max(1, s.heightBlocks) - 1;
            // Far block of the rectangle; the box spans both corner blocks completely.
            int fx = x + side.rightX * w + side.upX * h;
            int fy = y + side.rightY * w + side.upY * h;
            int fz = z + side.rightZ * w + side.upZ * h;
            minX = Math.min(minX, fx);
            minY = Math.min(minY, fy);
            minZ = Math.min(minZ, fz);
            maxX = Math.max(maxX, fx + 1);
            maxY = Math.max(maxY, fy + 1);
            maxZ = Math.max(maxZ, fz + 1);
        }
        return new double[] { minX - BOX_MARGIN, minY - BOX_MARGIN, minZ - BOX_MARGIN, maxX + BOX_MARGIN,
            maxY + BOX_MARGIN, maxZ + BOX_MARGIN };
    }

    /**
     * How far from the origin block a screen still renders: {@code base} blocks beyond its farthest corner. Vanilla's
     * tile entity renderer measures the distance from the origin block's centre, so a fixed 128 culled a whole 64x64
     * admin screen for a viewer near its far edge (review finding); the diagonal of the largest face, plus the origin
     * block itself, is added. {@code faces} may contain nulls.
     */
    public static double renderDistance(double base, Iterable<ScreenState> faces) {
        double diagonal = 0;
        for (ScreenState s : faces) {
            if (s == null) continue;
            double w = Math.max(1, s.widthBlocks), h = Math.max(1, s.heightBlocks);
            diagonal = Math.max(diagonal, Math.sqrt(w * w + h * h));
        }
        return base + diagonal + 1;
    }

    /**
     * Default page size for a new screen: the drawable area's aspect ratio (page orientation, so swapped for 90/270),
     * fitted inside maxW x maxH (720p by default, ADR-004), never below {@link #MIN_RESOLUTION}.
     */
    public static int[] defaultResolution(int widthBlocks, int heightBlocks, int rotation, int maxW, int maxH) {
        double fw = Math.max(0.1, widthBlocks - 2 * HitMath.BORDER);
        double fh = Math.max(0.1, heightBlocks - 2 * HitMath.BORDER);
        double aspect = HitMath.swapsAxes(rotation) ? fh / fw : fw / fh;
        int w, h;
        if (aspect >= (double) maxW / maxH) {
            w = maxW;
            h = (int) Math.round(maxW / aspect);
        } else {
            h = maxH;
            w = (int) Math.round(maxH * aspect);
        }
        return new int[] { Math.max(MIN_RESOLUTION, w), Math.max(MIN_RESOLUTION, h) };
    }

    /** True if a requested page size is inside the configured limits. */
    public static boolean resolutionAllowed(int w, int h, int maxW, int maxH, long maxPixels) {
        return w >= MIN_RESOLUTION && h >= MIN_RESOLUTION && w <= maxW && h <= maxH && (long) w * h <= maxPixels;
    }

    /**
     * Rotation for a new screen so that its page reads upright for the player who formed it. Vertical faces start
     * at 0. On a floor (TOP) the page's top points away from the player; on a ceiling (BOTTOM) it points towards
     * the player, which is how a surface overhead appears when looking up at it.
     */
    public static int initialRotation(BlockSide side, float yawDeg) {
        if (!side.horizontal()) return 0;
        // 0 south (+Z), 1 west (-X), 2 north (-Z), 3 east (+X): Minecraft yaw convention.
        int facing = ((int) Math.floor(yawDeg / 90.0 + 0.5)) & 3;
        int turns = side == BlockSide.TOP ? (facing + 2) & 3 : (-facing) & 3;
        return turns * 90;
    }
}
