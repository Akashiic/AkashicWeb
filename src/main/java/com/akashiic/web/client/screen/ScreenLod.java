package com.akashiic.web.client.screen;

/**
 * Source-side level of detail for one screen (ADR-004 "SOURCE-SIDE LOD"): how often the host paints it. 30 fps near,
 * 12 mid, 1-5 when visible from afar or out of view, and no painting at all beyond range. Ranges grow with the screen
 * so a spawn telão stays sharp from across the plaza while a 2x2 monitor goes quiet. Thresholds are INFERRED tuning,
 * checked at G3. Immutable and pure; tested in ScreenLodTest.
 */
public final class ScreenLod {

    public static final int FPS_NEAR = 30;
    public static final int FPS_MID = 12;
    public static final int FPS_FAR_VISIBLE = 5;
    public static final int FPS_OFF_VIEW = 2;
    /** Sent with painting=false; the host keeps the page (and its audio) alive. */
    public static final int FPS_IDLE = 1;
    /** Wider than any FOV setting (vanilla max 110° vertical, ~140° horizontal on wide screens) on purpose. */
    public static final double VIEW_HALF_ANGLE_DEG = 75;

    public final int fps;
    public final boolean painting;

    private ScreenLod(int fps, boolean painting) {
        this.fps = fps;
        this.painting = painting;
    }

    public static ScreenLod of(double distance, boolean inView, int maxDimBlocks) {
        if (!(distance <= paintRange(maxDimBlocks))) return new ScreenLod(FPS_IDLE, false);
        if (!inView) return new ScreenLod(FPS_OFF_VIEW, true);
        if (distance <= nearRange(maxDimBlocks)) return new ScreenLod(FPS_NEAR, true);
        if (distance <= midRange(maxDimBlocks)) return new ScreenLod(FPS_MID, true);
        return new ScreenLod(FPS_FAR_VISIBLE, true);
    }

    static double nearRange(int maxDim) {
        return 16 + 2.0 * Math.max(1, maxDim);
    }

    static double midRange(int maxDim) {
        return 32 + 4.0 * Math.max(1, maxDim);
    }

    /** Beyond this the host stops painting; the texture keeps the last frame. */
    public static double paintRange(int maxDim) {
        return Math.max(48, Math.min(160, 48 + 6.0 * Math.max(1, maxDim)));
    }

    /** A browser is created only within this distance. */
    public static double createRange(int maxDim) {
        return paintRange(maxDim);
    }

    /** An existing browser survives until this distance (hysteresis against create/destroy churn at the edge). */
    public static double keepRange(int maxDim) {
        return paintRange(maxDim) + 16;
    }

    /**
     * Cheap view test: is the screen's bounding sphere inside a cone around the look direction? Not a frustum (the
     * camera matrices belong to the render pass and Angelica may replace them); good enough to pick a frame rate.
     */
    public static boolean inView(double[] eye, double[] look, double[] center, double radius) {
        double[] d = ScreenGeometry.sub(center, eye);
        double dist = Math.sqrt(ScreenGeometry.dot(d, d));
        if (dist <= radius + 1) return true; // standing at or inside the screen
        double lookLen = Math.sqrt(ScreenGeometry.dot(look, look));
        if (!(lookLen > 0)) return true;
        double cos = ScreenGeometry.dot(d, look) / (dist * lookLen);
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
        double angularRadius = Math.toDegrees(Math.asin(Math.min(1, radius / dist)));
        return angle <= VIEW_HALF_ANGLE_DEG + angularRadius;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ScreenLod && ((ScreenLod) o).fps == fps && ((ScreenLod) o).painting == painting;
    }

    @Override
    public int hashCode() {
        return fps * 2 + (painting ? 1 : 0);
    }

    @Override
    public String toString() {
        return painting ? fps + "fps" : "idle";
    }
}
