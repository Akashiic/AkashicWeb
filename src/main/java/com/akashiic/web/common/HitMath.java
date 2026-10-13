package com.akashiic.web.common;

import net.minecraft.util.Vec3;

/**
 * Geometry of a screen face: world point <-> face coordinates <-> page coordinates, and the eye ray test the server
 * uses to validate pointer packets. Contract type (docs/CONTRACTS.md, "M4 <-> M5: game state").
 * <p>
 * Coordinate systems:
 * <ul>
 * <li><b>face</b> (fx, fy): 0..1 over the drawable area, fx along {@link BlockSide#right}, fy from the top edge
 * downwards. The drawable area is the screen rectangle minus a {@link #BORDER} frame on every edge.</li>
 * <li><b>page</b> (u, v): 0..1 from the top-left of the web page. The page is turned {@link ScreenState#rotation}
 * degrees clockwise on the face, so for 90 and 270 the page's width runs along the face's height.</li>
 * </ul>
 * Derived from Web Displays by montoyo (public domain), BlockScreen.hit2pixels (2/16 frame, rotation handling);
 * rewritten as explicit, invertible maps so the server can check a claimed (u, v) against the player's eye.
 */
public final class HitMath {

    /** Frame width in blocks on every edge of the screen (WD: 2/16). The renderer draws the page inside it. */
    public static final double BORDER = 2.0 / 16.0;

    private HitMath() {}

    // ---- contract API ----

    /** Page (u, v) of a world point on the face, or null if it falls on the frame or outside the screen. */
    public static double[] uvOnFace(ScreenState s, Vec3 hit) {
        return hit == null ? null : uvOnFace(s, hit.xCoord, hit.yCoord, hit.zCoord);
    }

    /** Page pixel for (u, v), clamped into the page. */
    public static int[] pixel(ScreenState s, double u, double v) {
        int w = Math.max(1, s.resolutionW), h = Math.max(1, s.resolutionH);
        return new int[] { clamp((int) Math.floor(u * w), 0, w - 1), clamp((int) Math.floor(v * h), 0, h - 1) };
    }

    // ---- pure overloads ----

    /** See {@link #uvOnFace(ScreenState, Vec3)}. The point is projected onto the face plane. */
    public static double[] uvOnFace(ScreenState s, double px, double py, double pz) {
        BlockSide side = side(s);
        double[] c = corner(s, side);
        double a = side.dotRight(px - c[0], py - c[1], pz - c[2]);
        double b = side.dotUp(px - c[0], py - c[1], pz - c[2]);
        double fx = (a - BORDER) / (s.widthBlocks - 2 * BORDER);
        double fy = 1.0 - (b - BORDER) / (s.heightBlocks - 2 * BORDER);
        if (!(fx >= 0 && fx <= 1 && fy >= 0 && fy <= 1)) return null;
        return faceToPage(s.rotation, fx, fy);
    }

    /** World point on the face plane for page (u, v). Inverse of {@link #uvOnFace}. */
    public static double[] worldPoint(ScreenState s, double u, double v) {
        BlockSide side = side(s);
        double[] f = pageToFace(s.rotation, u, v);
        double a = BORDER + f[0] * (s.widthBlocks - 2 * BORDER);
        double b = BORDER + (1.0 - f[1]) * (s.heightBlocks - 2 * BORDER);
        double[] c = corner(s, side);
        return new double[] { c[0] + side.rightX * a + side.upX * b, c[1] + side.rightY * a + side.upY * b,
            c[2] + side.rightZ * a + side.upZ * b };
    }

    /**
     * Intersects the ray eye + t*dir with the face plane. Returns {u, v, distance} when the eye is in front of the
     * face, the ray points into it, the hit is within {@code maxDistance} and inside the drawable area; null
     * otherwise.
     */
    public static double[] rayHit(ScreenState s, double ex, double ey, double ez, double dx, double dy, double dz,
        double maxDistance) {
        BlockSide side = side(s);
        double[] c = corner(s, side);
        double height = side.dotForward(ex - c[0], ey - c[1], ez - c[2]);
        double towards = side.dotForward(dx, dy, dz);
        if (height <= 0 || towards >= 0) return null;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0) return null;
        double t = -height / towards;
        double dist = t * len;
        if (dist > maxDistance) return null;
        double[] uv = uvOnFace(s, ex + dx * t, ey + dy * t, ez + dz * t);
        return uv == null ? null : new double[] { uv[0], uv[1], dist };
    }

    /**
     * The point of the screen rectangle (frame included) the eye ray aims at: where the ray meets the face plane,
     * clamped into the rectangle, so looking just beside a screen gives the nearest point of its edge. Null when the
     * eye is not in front of the face or the ray does not point towards the face plane (looking away or along it).
     * The server's "is this player looking at the screen" test for the controller lock (D-016).
     */
    public static double[] aimPoint(ScreenState s, double ex, double ey, double ez, double dx, double dy, double dz) {
        BlockSide side = side(s);
        double[] c = corner(s, side);
        double height = side.dotForward(ex - c[0], ey - c[1], ez - c[2]);
        double towards = side.dotForward(dx, dy, dz);
        if (height <= 0 || towards >= 0) return null;
        double t = -height / towards;
        double px = ex + dx * t - c[0], py = ey + dy * t - c[1], pz = ez + dz * t - c[2];
        double a = Math.max(0, Math.min(s.widthBlocks, side.dotRight(px, py, pz)));
        double b = Math.max(0, Math.min(s.heightBlocks, side.dotUp(px, py, pz)));
        return new double[] { c[0] + side.rightX * a + side.upX * b, c[1] + side.rightY * a + side.upY * b,
            c[2] + side.rightZ * a + side.upZ * b };
    }

    /** Signed distance of a point from the face plane: positive in front of the screen. */
    public static double heightAbove(ScreenState s, double px, double py, double pz) {
        BlockSide side = side(s);
        double[] c = corner(s, side);
        return side.dotForward(px - c[0], py - c[1], pz - c[2]);
    }

    /** Distance from a point to the nearest point of the screen rectangle (frame included). */
    public static double distanceToScreen(ScreenState s, double px, double py, double pz) {
        BlockSide side = side(s);
        double[] c = corner(s, side);
        double dx = px - c[0], dy = py - c[1], dz = pz - c[2];
        double a = Math.max(0, Math.min(s.widthBlocks, side.dotRight(dx, dy, dz)));
        double b = Math.max(0, Math.min(s.heightBlocks, side.dotUp(dx, dy, dz)));
        double qx = c[0] + side.rightX * a + side.upX * b - px;
        double qy = c[1] + side.rightY * a + side.upY * b - py;
        double qz = c[2] + side.rightZ * a + side.upZ * b - pz;
        return Math.sqrt(qx * qx + qy * qy + qz * qz);
    }

    /** Unit look vector for Minecraft yaw/pitch in degrees (same convention as Entity.getLook). */
    public static double[] lookVector(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double cp = Math.cos(pitch);
        return new double[] { -Math.sin(yaw) * cp, -Math.sin(pitch), Math.cos(yaw) * cp };
    }

    /** Angle in degrees between a direction and the vector from the eye to a point (180 if degenerate). */
    public static double angleDeg(double ex, double ey, double ez, double[] dir, double px, double py, double pz) {
        double vx = px - ex, vy = py - ey, vz = pz - ez;
        double lv = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double ld = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
        if (lv == 0) return 0;
        if (ld == 0) return 180;
        double cos = (vx * dir[0] + vy * dir[1] + vz * dir[2]) / (lv * ld);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    /** Face (fx, fy) to page (u, v) for a clockwise rotation in degrees (0, 90, 180, 270). */
    public static double[] faceToPage(int rotation, double fx, double fy) {
        switch (quarterTurns(rotation)) {
            case 1:
                return new double[] { fy, 1 - fx };
            case 2:
                return new double[] { 1 - fx, 1 - fy };
            case 3:
                return new double[] { 1 - fy, fx };
            default:
                return new double[] { fx, fy };
        }
    }

    /** Inverse of {@link #faceToPage}. */
    public static double[] pageToFace(int rotation, double u, double v) {
        switch (quarterTurns(rotation)) {
            case 1:
                return new double[] { 1 - v, u };
            case 2:
                return new double[] { 1 - u, 1 - v };
            case 3:
                return new double[] { v, 1 - u };
            default:
                return new double[] { u, v };
        }
    }

    /** 0..3 for 0/90/180/270 degrees; anything else counts as 0 (rotation is validated where it is set). */
    public static int quarterTurns(int rotation) {
        int r = ((rotation % 360) + 360) % 360;
        return r % 90 == 0 ? r / 90 : 0;
    }

    /** True when the page's width runs along the face's height (90 and 270 degrees). */
    public static boolean swapsAxes(int rotation) {
        return (quarterTurns(rotation) & 1) == 1;
    }

    /**
     * Bottom-left corner of the face, as seen by its viewer, in world coordinates: the origin block's corner that
     * lies on the face plane at the left and bottom edges.
     */
    static double[] corner(ScreenState s, BlockSide side) {
        return new double[] { s.key.x + offset(side.rightX, side.upX, side.fwdX),
            s.key.y + offset(side.rightY, side.upY, side.fwdY), s.key.z + offset(side.rightZ, side.upZ, side.fwdZ) };
    }

    private static int offset(int right, int up, int fwd) {
        // Exactly one of the three vectors lies on any axis. Left and bottom edges sit on the far side of the origin
        // block when right/up point to negative coordinates; the face plane is on the block's positive side when the
        // face looks to positive coordinates.
        return (right < 0 ? 1 : 0) + (up < 0 ? 1 : 0) + (fwd > 0 ? 1 : 0);
    }

    private static BlockSide side(ScreenState s) {
        BlockSide side = BlockSide.fromSide(s.key.side);
        if (side == null) throw new IllegalArgumentException("side " + s.key.side);
        return side;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
