package com.akashiic.web.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Name to UUID of the players who joined this server, so a screen owner can add a friend by name without any network
 * lookup. The server's own profile cache (PlayerProfileCache.func_152655_a) asks Mojang on a miss, with retries and
 * 15 s timeouts, on the calling thread, and rewrites usercache.json on every call: on the main thread that froze the
 * server for every unknown name (review finding, high). Here a name is known only if that player logged in, and a
 * lookup is a map read. Filled at login ({@code ServerEvents}), saved with the screen index ({@link ScreenIndexData}).
 * <p>
 * Bounded: at most {@link #MAX_ENTRIES} players, and a player not seen for {@link #FORGET_AFTER_MS} is forgotten
 * (retention of player identifiers, P-09). Pure; main thread only.
 */
public final class KnownPlayers {

    public static final int MAX_ENTRIES = 10_000;
    /** 90 days. */
    public static final long FORGET_AFTER_MS = 90L * 24 * 60 * 60 * 1000;

    /** One player as last seen. */
    public static final class Entry {

        public final UUID id;
        /** Canonical spelling. */
        public final String name;
        public final long seenMs;

        Entry(UUID id, String name, long seenMs) {
            this.id = id;
            this.name = name;
            this.seenMs = seenMs;
        }
    }

    private final Map<String, Entry> byName = new HashMap<>();
    private final Map<UUID, Entry> byId = new HashMap<>();

    /**
     * Records a login. A renamed player keeps one entry (the old name stops resolving to them). Returns true when the
     * stored data changed, so the caller marks the save file dirty.
     */
    public boolean seen(UUID id, String name, long nowMs) {
        if (id == null || name == null || name.isEmpty()) return false;
        Entry old = byId.get(id);
        if (old != null && old.name.equals(name) && nowMs - old.seenMs < 60_000) return false;
        if (old != null) byName.remove(key(old.name));
        Entry taken = byName.get(key(name));
        if (taken != null && !taken.id.equals(id)) byId.remove(taken.id); // the name now belongs to someone else
        Entry e = new Entry(id, name, nowMs);
        byName.put(key(name), e);
        byId.put(id, e);
        if (byId.size() > MAX_ENTRIES) evictOldest();
        return true;
    }

    /** The player who last logged in with this name (any case), or null. */
    public Entry lookup(String name) {
        return name == null ? null : byName.get(key(name));
    }

    /** Drops players not seen for {@link #FORGET_AFTER_MS}. Returns true if any went. */
    public boolean expire(long nowMs) {
        boolean changed = false;
        for (Iterator<Entry> it = byId.values()
            .iterator(); it.hasNext();) {
            Entry e = it.next();
            if (nowMs - e.seenMs > FORGET_AFTER_MS) {
                it.remove();
                byName.remove(key(e.name));
                changed = true;
            }
        }
        return changed;
    }

    public int size() {
        return byId.size();
    }

    public Collection<Entry> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public void clear() {
        byName.clear();
        byId.clear();
    }

    private void evictOldest() {
        List<Entry> entries = new ArrayList<>(byId.values());
        Collections.sort(entries, (a, b) -> Long.compare(a.seenMs, b.seenMs));
        for (int i = 0; byId.size() > MAX_ENTRIES && i < entries.size(); i++) {
            Entry e = entries.get(i);
            byId.remove(e.id);
            byName.remove(key(e.name));
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
