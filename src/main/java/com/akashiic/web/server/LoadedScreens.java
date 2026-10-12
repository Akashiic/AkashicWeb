package com.akashiic.web.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.ScreenFormation;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.block.TileEntityScreen;

/**
 * Screen tile entities currently loaded on the server, keyed by the origin's (dim, x, y, z) value (Crucible overrides
 * TileEntity.hashCode, so tiles are never used as keys). Filled from TileEntityScreen.validate and emptied on
 * invalidate/unload. Main thread only.
 */
public final class LoadedScreens {

    /** Origin position value. */
    static final class Pos {

        final int dim, x, y, z;

        Pos(int dim, int x, int y, int z) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        static Pos of(ScreenKey k) {
            return new Pos(k.dim, k.x, k.y, k.z);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Pos)) return false;
            Pos p = (Pos) o;
            return dim == p.dim && x == p.x && y == p.y && z == p.z;
        }

        @Override
        public int hashCode() {
            return ((dim * 31 + x) * 31 + y) * 31 + z;
        }
    }

    private final Map<Pos, TileEntityScreen> tiles = new HashMap<>();

    public void register(TileEntityScreen te) {
        tiles.put(Pos.of(te.keyFor(0)), te);
    }

    /** Removes the tile only if it is still the one registered at its position. */
    public void unregister(TileEntityScreen te) {
        Pos p = Pos.of(te.keyFor(0));
        if (tiles.get(p) == te) tiles.remove(p);
    }

    public TileEntityScreen tile(ScreenKey key) {
        return tiles.get(Pos.of(key));
    }

    /** The face for a key, or null if its tile is not loaded or has no such face. */
    public ScreenState face(ScreenKey key) {
        TileEntityScreen te = tile(key);
        return te == null || te.isInvalid() ? null : te.screen(key.side);
    }

    /** Loaded faces (any side, or one side when {@code side >= 0}) that include block (x, y, z). */
    public List<ScreenState> containing(int dim, int x, int y, int z, int side) {
        List<ScreenState> out = new ArrayList<>();
        for (TileEntityScreen te : tiles.values()) {
            if (te.isInvalid()) continue;
            for (ScreenState s : te.screens()) {
                if (s.key.dim != dim || (side >= 0 && s.key.side != side)) continue;
                BlockSide bs = BlockSide.fromSide(s.key.side);
                if (ScreenFormation.contains(s.key.x, s.key.y, s.key.z, bs, s.widthBlocks, s.heightBlocks, x, y, z))
                    out.add(s);
            }
        }
        return out;
    }

    /** All loaded faces (a snapshot, safe to iterate while faces change). */
    public List<ScreenState> faces() {
        List<ScreenState> out = new ArrayList<>();
        for (TileEntityScreen te : tiles.values()) if (!te.isInvalid()) out.addAll(te.screens());
        return out;
    }

    public int size() {
        return tiles.size();
    }

    public void removeDimension(int dim) {
        for (Iterator<Pos> it = tiles.keySet()
            .iterator(); it.hasNext();) if (it.next().dim == dim) it.remove();
    }

    public void clear() {
        tiles.clear();
    }
}
