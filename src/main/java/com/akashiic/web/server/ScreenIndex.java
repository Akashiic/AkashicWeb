package com.akashiic.web.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.ScreenFormation;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;

/**
 * Every screen the server knows, loaded or not: who owns it, its size and URL. Used for the per-player screen limit
 * and {@code /webadmin list}, and healed against the real tile entities whenever a chunk loads. Keyed by
 * {@link ScreenKey} values, never by tile entity objects (Crucible overrides TileEntity.hashCode). Pure; persisted by
 * {@link ScreenIndexData}. Main thread only.
 */
public final class ScreenIndex {

    /** Longest URL kept in the index (display only; the tile entity holds the real one). */
    public static final int URL_CHARS = 256;

    public static final class Entry {

        public final ScreenKey key;
        public final UUID owner;
        public final String ownerName;
        public final int width, height;
        public final String url;

        public Entry(ScreenKey key, UUID owner, String ownerName, int width, int height, String url) {
            this.key = key;
            this.owner = owner;
            this.ownerName = ownerName == null ? "" : ownerName;
            this.width = width;
            this.height = height;
            this.url = url == null ? "" : url.length() > URL_CHARS ? url.substring(0, URL_CHARS) : url;
        }

        public static Entry of(ScreenState s) {
            return new Entry(s.key, s.owner, s.ownerName, s.widthBlocks, s.heightBlocks, s.url);
        }

        public boolean contains(int dim, int x, int y, int z) {
            BlockSide side = BlockSide.fromSide(key.side);
            return key.dim == dim && side != null
                && ScreenFormation.contains(key.x, key.y, key.z, side, width, height, x, y, z);
        }

        boolean sameAs(Entry o) {
            return o != null && key.equals(o.key)
                && (owner == null ? o.owner == null : owner.equals(o.owner))
                && ownerName.equals(o.ownerName)
                && width == o.width
                && height == o.height
                && url.equals(o.url);
        }
    }

    private final Map<ScreenKey, Entry> entries = new LinkedHashMap<>();
    /**
     * The newest epoch issued by this server, so a screen formed or merged at a key always starts above every epoch a
     * client may still hold for that key (review finding: a merge could reuse a smaller screen's key with a lower
     * epoch, and clients that kept the old one then ignored every URL of the new one).
     */
    private int epochHighWater;

    /**
     * An epoch newer than every epoch issued so far and than every absorbed screen's (epochs compare wrap-aware, as
     * the client does), recorded as the new high-water mark. For a new or merged screen.
     */
    public int nextEpoch(Iterable<ScreenState> absorbed) {
        int next = epochHighWater;
        for (ScreenState s : absorbed) if (s.epoch - next > 0) next = s.epoch;
        epochHighWater = next + 1;
        return epochHighWater;
    }

    /** Raises the high-water mark to an epoch that is in use (a URL change, a screen loaded from disk). */
    public boolean noteEpoch(int epoch) {
        if (epoch - epochHighWater <= 0) return false;
        epochHighWater = epoch;
        return true;
    }

    public int epochHighWater() {
        return epochHighWater;
    }

    /** Adds or replaces. Returns true if anything changed (so the caller marks the save data dirty). */
    public boolean put(Entry e) {
        Entry old = entries.put(e.key, e);
        return !e.sameAs(old);
    }

    public boolean remove(ScreenKey key) {
        return entries.remove(key) != null;
    }

    public Entry get(ScreenKey key) {
        return entries.get(key);
    }

    public int size() {
        return entries.size();
    }

    public Collection<Entry> all() {
        return Collections.unmodifiableCollection(entries.values());
    }

    /** Screens owned by a player. */
    public int ownedBy(UUID owner) {
        if (owner == null) return 0;
        int n = 0;
        for (Entry e : entries.values()) if (owner.equals(e.owner)) n++;
        return n;
    }

    /** Screens in a dimension whose origin is within {@code radius} blocks (horizontal + vertical) of a point. */
    public List<Entry> within(int dim, double x, double y, double z, double radius) {
        List<Entry> out = new ArrayList<>();
        double r2 = radius * radius;
        for (Entry e : entries.values()) {
            if (e.key.dim != dim) continue;
            double dx = e.key.x + 0.5 - x, dy = e.key.y + 0.5 - y, dz = e.key.z + 0.5 - z;
            if (dx * dx + dy * dy + dz * dz <= r2) out.add(e);
        }
        return out;
    }

    /** Screens (any side) that include the block at (x, y, z). */
    public List<Entry> containing(int dim, int x, int y, int z) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) if (e.contains(dim, x, y, z)) out.add(e);
        return out;
    }

    /** Screens whose origin lies in the given chunk (to heal against the tile entities when it loads). */
    public List<Entry> inChunk(int dim, int chunkX, int chunkZ) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.key.dim == dim && (e.key.x >> 4) == chunkX && (e.key.z >> 4) == chunkZ) out.add(e);
        }
        return out;
    }

    public void clear() {
        entries.clear();
        epochHighWater = 0;
    }
}
