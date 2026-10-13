package com.akashiic.web.client.render;

/** Integer pixel rectangle (x, y, w, h); empty when w or h is not positive. Immutable value. */
public final class Rect {

    public static final Rect EMPTY = new Rect(0, 0, 0, 0);

    public final int x;
    public final int y;
    public final int w;
    public final int h;

    public Rect(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public boolean isEmpty() {
        return w <= 0 || h <= 0;
    }

    public long area() {
        return isEmpty() ? 0 : (long) w * h;
    }

    /** Smallest rectangle holding both (an empty side is ignored). */
    public Rect union(Rect o) {
        if (o == null || o.isEmpty()) return this;
        if (isEmpty()) return o;
        int x0 = Math.min(x, o.x);
        int y0 = Math.min(y, o.y);
        long x1 = Math.max((long) x + w, (long) o.x + o.w);
        long y1 = Math.max((long) y + h, (long) o.y + o.h);
        return new Rect(x0, y0, (int) Math.min(Integer.MAX_VALUE, x1 - x0), (int) Math.min(Integer.MAX_VALUE, y1 - y0));
    }

    /** Intersection with (0, 0, width, height); {@link #EMPTY} when nothing is left. */
    public Rect clip(int width, int height) {
        if (isEmpty()) return EMPTY;
        long x0 = Math.max(0, x);
        long y0 = Math.max(0, y);
        long x1 = Math.min(width, (long) x + w);
        long y1 = Math.min(height, (long) y + h);
        if (x1 <= x0 || y1 <= y0) return EMPTY;
        return new Rect((int) x0, (int) y0, (int) (x1 - x0), (int) (y1 - y0));
    }

    public boolean within(int width, int height) {
        return !isEmpty() && x >= 0 && y >= 0 && (long) x + w <= width && (long) y + h <= height;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Rect)) return false;
        Rect r = (Rect) o;
        return x == r.x && y == r.y && w == r.w && h == r.h;
    }

    @Override
    public int hashCode() {
        return ((x * 31 + y) * 31 + w) * 31 + h;
    }

    @Override
    public String toString() {
        return x + "," + y + " " + w + "x" + h;
    }
}
