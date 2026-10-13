package com.akashiic.web.client.screen;

import com.akashiic.web.common.ScreenState;

/**
 * Where a screen's picture sits in the world, and the one mapping shared by drawing and hit-testing so the two can
 * never disagree: the TESR draws the quad from {@link #basisMatrix()} and {@link #texCoord}, and a click is turned into
 * page (u, v) by {@link #pageUv}, both from the same corners.
 * <p>
 * Multiblock convention (Derived from Web Displays by montoyo (public domain), {@code utilities/BlockSide}): the
 * screen's blocks are {@code origin + i*right + j*up} for {@code i < widthBlocks}, {@code j < heightBlocks}, with
 * Web Displays' right/up vectors per face ({@link #EXTENT_RIGHT}, {@link #EXTENT_UP}); the picture is inset by
 * Web Displays' 2/16-block frame. Web Displays' BOTTOM "up" is mirrored for a viewer below, so the visible orientation
 * uses {@link #VIEW_UP} (its renderer does the same with a +90° X rotation).
 * <p>
 * All coordinates are relative to the origin block's minimum corner. Rotation is clockwise when looking at the face.
 * Immutable and pure; tested in ScreenGeometryTest.
 */
public final class ScreenGeometry {

    /** Web Displays' frame: 2 px of a 16 px block on every edge. */
    public static final double FRAME_INSET = 2.0 / 16.0;
    /** Lifts the picture off the block face to avoid z-fighting (Web Displays draws at 0.505 from the centre). */
    public static final double SURFACE_OFFSET = 0.005;
    /** A ray hit lies on the block surface; anything farther from the plane is not on this face. */
    public static final double PLANE_TOLERANCE = 0.05;

    // Forge side order: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east.
    static final int[][] NORMAL = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };
    static final int[][] EXTENT_RIGHT = { { 1, 0, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 1, 0, 0 }, { 0, 0, 1 },
        { 0, 0, -1 } };
    static final int[][] EXTENT_UP = { { 0, 0, -1 }, { 0, 0, -1 }, { 0, 1, 0 }, { 0, 1, 0 }, { 0, 1, 0 }, { 0, 1, 0 } };
    /** Up as seen by a viewer facing the face (right = viewer forward x up); differs from EXTENT_UP on BOTTOM only. */
    static final int[][] VIEW_UP = { { 0, 0, 1 }, { 0, 0, -1 }, { 0, 1, 0 }, { 0, 1, 0 }, { 0, 1, 0 }, { 0, 1, 0 } };

    /** Page corners in face-corner order TL, TR, BR, BL (unrotated). */
    private static final float[][] PAGE_CORNERS = { { 0, 0 }, { 1, 0 }, { 1, 1 }, { 0, 1 } };

    public final int side;
    public final int widthBlocks;
    public final int heightBlocks;
    /** Clockwise quarter turns, 0..3. */
    public final int quarterTurns;
    /** Picture size in blocks (frame excluded). */
    public final double quadWidth;

    public final double quadHeight;
    private final double[] topLeft; // on the block surface
    private final double[] right;
    private final double[] down;
    private final double[] normal;

    private ScreenGeometry(int side, int widthBlocks, int heightBlocks, int quarterTurns) {
        this.side = side;
        this.widthBlocks = widthBlocks;
        this.heightBlocks = heightBlocks;
        this.quarterTurns = quarterTurns;
        this.normal = toDouble(NORMAL[side]);
        this.right = toDouble(EXTENT_RIGHT[side]);
        double[] viewUp = toDouble(VIEW_UP[side]);
        this.down = scale(viewUp, -1);
        // Centre of the origin block's face, then to the centre of the whole multiblock face.
        double[] center = add(new double[] { 0.5, 0.5, 0.5 }, scale(normal, 0.5));
        center = add(center, scale(toDouble(EXTENT_RIGHT[side]), (widthBlocks - 1) / 2.0));
        center = add(center, scale(toDouble(EXTENT_UP[side]), (heightBlocks - 1) / 2.0));
        this.quadWidth = widthBlocks - 2 * FRAME_INSET;
        this.quadHeight = heightBlocks - 2 * FRAME_INSET;
        this.topLeft = add(add(center, scale(right, -quadWidth / 2)), scale(down, -quadHeight / 2));
    }

    /** Geometry of a screen record; null when the record cannot be drawn (bad side or size). */
    public static ScreenGeometry of(ScreenState s) {
        if (s == null || s.key == null) return null;
        return of(s.key.side, s.widthBlocks, s.heightBlocks, s.rotation);
    }

    public static ScreenGeometry of(int side, int widthBlocks, int heightBlocks, int rotationDegrees) {
        if (side < 0 || side > 5 || widthBlocks < 1 || heightBlocks < 1) return null;
        return new ScreenGeometry(side, widthBlocks, heightBlocks, quarterTurns(rotationDegrees));
    }

    /** 0/90/180/270 to 0..3; anything else snaps to the nearest quarter turn. */
    static int quarterTurns(int rotationDegrees) {
        int q = Math.round(rotationDegrees / 90f);
        return ((q % 4) + 4) % 4;
    }

    /** Face corner {@code i} (0 TL, 1 TR, 2 BR, 3 BL as the viewer sees it), lifted off the surface. */
    public double[] corner(int i) {
        double[] c = add(topLeft, scale(normal, SURFACE_OFFSET));
        if (i == 1 || i == 2) c = add(c, scale(right, quadWidth));
        if (i == 2 || i == 3) c = add(c, scale(down, quadHeight));
        return c;
    }

    /** Page texture coordinate (u, v) drawn at face corner {@code i}: the page rotated clockwise by the rotation. */
    public float[] texCoord(int i) {
        float[] p = PAGE_CORNERS[((i - quarterTurns) % 4 + 4) % 4];
        return new float[] { p[0], p[1] };
    }

    /**
     * Face coordinates (fu, fv in 0..1 from the picture's top-left as the viewer sees it) of a point, or null when the
     * point is off the face plane or outside the picture (the frame does not count).
     */
    public double[] faceUv(double px, double py, double pz) {
        double[] q = { px - topLeft[0], py - topLeft[1], pz - topLeft[2] };
        if (Math.abs(dot(q, normal)) > PLANE_TOLERANCE) return null;
        double fu = dot(q, right) / quadWidth;
        double fv = dot(q, down) / quadHeight;
        if (!(fu >= 0 && fu <= 1 && fv >= 0 && fv <= 1)) return null;
        return new double[] { fu, fv };
    }

    /** Page coordinates (u, v in 0..1 from the page's top-left) of a point, or null when it misses the picture. */
    public double[] pageUv(double px, double py, double pz) {
        double[] f = faceUv(px, py, pz);
        return f == null ? null : faceToPage(f[0], f[1], quarterTurns);
    }

    /** Inverse of the clockwise page rotation; consistent with {@link #texCoord} (tested). */
    static double[] faceToPage(double fu, double fv, int quarterTurns) {
        switch (quarterTurns) {
            case 1:
                return new double[] { fv, 1 - fu };
            case 2:
                return new double[] { 1 - fu, 1 - fv };
            case 3:
                return new double[] { 1 - fv, fu };
            default:
                return new double[] { fu, fv };
        }
    }

    /** Distance from a point to the nearest point of the picture (blocks); used for audio and LOD. */
    public double distanceTo(double px, double py, double pz) {
        double[] p = { px, py, pz };
        double[] q = sub(p, topLeft);
        double s = clamp(dot(q, right), 0, quadWidth);
        double t = clamp(dot(q, down), 0, quadHeight);
        double[] nearest = add(add(topLeft, scale(right, s)), scale(down, t));
        return Math.sqrt(dot(sub(p, nearest), sub(p, nearest)));
    }

    /** Centre of the picture on the surface. */
    public double[] center() {
        return add(add(topLeft, scale(right, quadWidth / 2)), scale(down, quadHeight / 2));
    }

    public double boundingRadius() {
        return 0.5 * Math.sqrt(quadWidth * quadWidth + quadHeight * quadHeight);
    }

    /** Outward face normal. */
    public double[] normal() {
        return normal.clone();
    }

    /**
     * Column-major 4x4 for glMultMatrix: x along the viewer's right, y along the viewer's down (so text and the page
     * read like a GUI), z = x cross y (into the face), origin at the lifted top-left corner. Units are blocks.
     */
    public float[] basisMatrix() {
        double[] z = cross(right, down);
        double[] o = corner(0);
        return new float[] { (float) right[0], (float) right[1], (float) right[2], 0, (float) down[0], (float) down[1],
            (float) down[2], 0, (float) z[0], (float) z[1], (float) z[2], 0, (float) o[0], (float) o[1], (float) o[2],
            1 };
    }

    private static double[] toDouble(int[] v) {
        return new double[] { v[0], v[1], v[2] };
    }

    static double[] add(double[] a, double[] b) {
        return new double[] { a[0] + b[0], a[1] + b[1], a[2] + b[2] };
    }

    static double[] sub(double[] a, double[] b) {
        return new double[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] };
    }

    static double[] scale(double[] a, double k) {
        return new double[] { a[0] * k, a[1] * k, a[2] * k };
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double[] cross(double[] a, double[] b) {
        return new double[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
