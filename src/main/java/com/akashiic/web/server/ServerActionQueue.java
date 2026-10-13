package com.akashiic.web.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.akashiic.web.AkashicWeb;

/**
 * Queue between packet arrival (any thread) and the start of the next server tick, where everything touching tile
 * entities, chunks or the network is done. Also the one-tick deferral the ADR-010 placement rule needs: an action
 * queued during tick N runs at the start of tick N+1, after Crucible has had the chance to roll back a placement
 * (BlockPlaceEvent) or a block change, so formation and audit lines are re-validated against the final world.
 * <p>
 * Shares (review finding: a few flooding clients filled the global queue, after which every other player's actions
 * and the heal tasks were dropped): a player's tasks are capped at {@link #MAX_PER_PLAYER} pending, and all player
 * tasks together may not use the last {@link #SYSTEM_RESERVE} slots, which stay free for the server's own tasks
 * (healing screens, sends from other threads). Refused packets never get here (see {@code server.net.ServerPackets}).
 * <p>
 * Adapted from AkashicFM (same owner) {@code com.akashiic.fm.network.ServerActionQueue}: generic Runnables instead of
 * packet types, rate limiting moved to {@link ActionLimits}, and {@link #drain} runs only what was queued before it
 * started, so a task that queues another defers it by a full tick.
 */
public final class ServerActionQueue {

    static final int MAX_PENDING = 8192;
    static final int MAX_PER_TICK = 2048;
    /** Slots only the server's own tasks may use. */
    static final int SYSTEM_RESERVE = 1024;
    /** Pending tasks one player may have; far above what the rate limits let an honest client queue in a tick. */
    static final int MAX_PER_PLAYER = 128;
    /** At most one "queue full" warning per this many ms (each one counts the drops since the last). */
    static final long WARN_INTERVAL_MS = 10_000;

    /** A queued task and the player it is charged to (null for the server's own). */
    private static final class Task {

        final UUID owner;
        final Runnable work;

        Task(UUID owner, Runnable work) {
            this.owner = owner;
            this.work = work;
        }
    }

    private final ConcurrentLinkedQueue<Task> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final ConcurrentHashMap<UUID, AtomicInteger> perPlayer = new ConcurrentHashMap<>();
    private final AtomicLong processed = new AtomicLong(), overflow = new AtomicLong(), failed = new AtomicLong();
    private final AtomicLong lastWarnMs = new AtomicLong(Long.MIN_VALUE / 2), droppedSinceWarn = new AtomicLong();

    /** The server's own task (heal, a send from another thread). Any thread; false (and counted) if full. */
    public boolean offer(Runnable task) {
        return offer(null, task);
    }

    /**
     * A task charged to {@code owner} (null for the server's own). Any thread. Returns false, counts and (rate
     * limited) logs the drop when the queue, the player share or the player's own cap is exhausted.
     */
    public boolean offer(UUID owner, Runnable task) {
        int limit = owner == null ? MAX_PENDING : MAX_PENDING - SYSTEM_RESERVE;
        if (size.incrementAndGet() > limit) {
            size.decrementAndGet();
            return dropped(owner);
        }
        if (owner != null) {
            AtomicInteger mine = perPlayer.computeIfAbsent(owner, k -> new AtomicInteger());
            if (mine.incrementAndGet() > MAX_PER_PLAYER) {
                mine.decrementAndGet();
                size.decrementAndGet();
                return dropped(owner);
            }
        }
        queue.add(new Task(owner, task));
        return true;
    }

    private boolean dropped(UUID owner) {
        overflow.incrementAndGet();
        droppedSinceWarn.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastWarnMs.get();
        if (now - last >= WARN_INTERVAL_MS && lastWarnMs.compareAndSet(last, now)) {
            AkashicWeb.LOG.warn(
                "AkashicWeb: action queue full, dropped {} task(s) in the last {} s (latest from {}); {}",
                droppedSinceWarn.getAndSet(0),
                WARN_INTERVAL_MS / 1000,
                owner == null ? "the server" : owner,
                stats());
        }
        return false;
    }

    /**
     * Main thread, start of the tick. Runs at most what was pending when it started (and at most
     * {@link #MAX_PER_TICK}); a task that throws is logged and counted. This is a documented boundary: one bad action
     * must not stop the others or the server tick.
     */
    public int drain() {
        int budget = Math.min(size.get(), MAX_PER_TICK);
        int ran = 0;
        for (; ran < budget; ran++) {
            Task t = queue.poll();
            if (t == null) break;
            size.decrementAndGet();
            if (t.owner != null) release(t.owner);
            processed.incrementAndGet();
            try {
                t.work.run();
            } catch (RuntimeException e) {
                failed.incrementAndGet();
                AkashicWeb.LOG.error("AkashicWeb: queued server action failed", e);
            }
        }
        return ran;
    }

    private void release(UUID owner) {
        AtomicInteger mine = perPlayer.get(owner);
        if (mine != null) mine.updateAndGet(v -> Math.max(0, v - 1));
    }

    /** Logout: drop the player's counter (tasks still queued for them are released without one). */
    public void forget(UUID owner) {
        perPlayer.remove(owner);
    }

    public int pending() {
        return size.get();
    }

    /** Tasks pending for one player. */
    public int pending(UUID owner) {
        AtomicInteger mine = perPlayer.get(owner);
        return mine == null ? 0 : Math.max(0, mine.get());
    }

    public String stats() {
        return "processed=" + processed
            .get() + " pending=" + size.get() + " overflow=" + overflow.get() + " failed=" + failed.get();
    }

    public void clear() {
        queue.clear();
        perPlayer.clear();
        size.set(0);
    }
}
