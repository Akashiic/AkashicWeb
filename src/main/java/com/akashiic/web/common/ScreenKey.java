package com.akashiic.web.common;

/**
 * Identity of one screen: the multiblock origin plus the face it shows on. Value object, safe as a map key and on
 * the wire. Contract type (docs/CONTRACTS.md): extend only by adding.
 */
public final class ScreenKey {

    public final int dim;
    public final int x;
    public final int y;
    public final int z;
    /** Forge side ordinal (0 down, 1 up, 2 north, 3 south, 4 west, 5 east). */
    public final int side;

    public ScreenKey(int dim, int x, int y, int z, int side) {
        if (side < 0 || side > 5) throw new IllegalArgumentException("side " + side);
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.side = side;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ScreenKey)) return false;
        ScreenKey k = (ScreenKey) o;
        return dim == k.dim && x == k.x && y == k.y && z == k.z && side == k.side;
    }

    @Override
    public int hashCode() {
        int h = dim;
        h = 31 * h + x;
        h = 31 * h + y;
        h = 31 * h + z;
        return 31 * h + side;
    }

    @Override
    public String toString() {
        return "screen[" + dim + ":" + x + "," + y + "," + z + "/" + side + "]";
    }
}
