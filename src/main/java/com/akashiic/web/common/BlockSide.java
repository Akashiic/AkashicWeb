package com.akashiic.web.common;

/**
 * The six faces a screen can show on, with the face's own 2D basis: {@code right} and {@code up} as seen by a viewer
 * standing in front of the face, and {@code forward} pointing out of the face towards that viewer. The ordinal is the
 * Forge side (0 down, 1 up, 2 north, 3 south, 4 west, 5 east), the same number {@link ScreenKey#side} carries.
 * <p>
 * Derived from Web Displays by montoyo (public domain), {@code net.montoyo.wd.utilities.BlockSide}. One deliberate
 * change: WD's BOTTOM face used right = +X, which is mirrored for a viewer looking up at a ceiling screen (WD
 * compensated with a special case in hit2pixels). Here every face satisfies {@code right = (-forward) x up}, so the
 * renderer (M5) and {@link HitMath} need no per-face special cases. Contract type for M5: draw the page with its
 * x axis along {@link #right} and its y axis along {@link #down} (before {@link ScreenState#rotation}).
 */
public enum BlockSide {

    BOTTOM(0, 0, -1, -1, 0, 0, 0, -1, 0),
    TOP(0, 0, -1, 1, 0, 0, 0, 1, 0),
    NORTH(0, 1, 0, -1, 0, 0, 0, 0, -1),
    SOUTH(0, 1, 0, 1, 0, 0, 0, 0, 1),
    WEST(0, 1, 0, 0, 0, 1, -1, 0, 0),
    EAST(0, 1, 0, 0, 0, -1, 1, 0, 0);

    private static final BlockSide[] VALUES = values();

    public final int upX, upY, upZ;
    public final int rightX, rightY, rightZ;
    /** Outward normal of the face. */
    public final int fwdX, fwdY, fwdZ;

    BlockSide(int ux, int uy, int uz, int rx, int ry, int rz, int fx, int fy, int fz) {
        upX = ux;
        upY = uy;
        upZ = uz;
        rightX = rx;
        rightY = ry;
        rightZ = rz;
        fwdX = fx;
        fwdY = fy;
        fwdZ = fz;
    }

    /** Forge side ordinal of this face. */
    public int side() {
        return ordinal();
    }

    /** The face pointing the other way (north for south, and so on). */
    public BlockSide opposite() {
        int s = ordinal();
        return VALUES[(s & ~1) | (1 - (s & 1))];
    }

    /** Null for anything outside 0..5 (wire input is never trusted). */
    public static BlockSide fromSide(int side) {
        return side < 0 || side >= VALUES.length ? null : VALUES[side];
    }

    /** Lenient parse for commands: a name ("north") or a number ("2"). Null if neither. */
    public static BlockSide parse(String s) {
        if (s == null) return null;
        String t = s.trim()
            .toUpperCase(java.util.Locale.ROOT);
        if (t.equals("DOWN")) return BOTTOM;
        if (t.equals("UP")) return TOP;
        for (BlockSide b : VALUES) if (b.name()
            .equals(t)) return b;
        try {
            return fromSide(Integer.parseInt(t));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Component of (dx, dy, dz) along {@link #right}. */
    public int dotRight(int dx, int dy, int dz) {
        return dx * rightX + dy * rightY + dz * rightZ;
    }

    /** Component of (dx, dy, dz) along {@link #up}. */
    public int dotUp(int dx, int dy, int dz) {
        return dx * upX + dy * upY + dz * upZ;
    }

    /** Component of (dx, dy, dz) along the outward normal. */
    public int dotForward(int dx, int dy, int dz) {
        return dx * fwdX + dy * fwdY + dz * fwdZ;
    }

    public double dotRight(double dx, double dy, double dz) {
        return dx * rightX + dy * rightY + dz * rightZ;
    }

    public double dotUp(double dx, double dy, double dz) {
        return dx * upX + dy * upY + dz * upZ;
    }

    public double dotForward(double dx, double dy, double dz) {
        return dx * fwdX + dy * fwdY + dz * fwdZ;
    }

    /** True for TOP and BOTTOM, where the initial rotation follows the placer's yaw. */
    public boolean horizontal() {
        return this == TOP || this == BOTTOM;
    }
}
