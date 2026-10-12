package com.akashiic.web.server;

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
 * Adapted from AkashicFM (same owner) {@code com.akashiic.fm.network.ServerActionQueue}: generic Runnables instead of
 * packet types, rate limiting moved to {@link ActionLimits}, and {@link #drain} runs only what was queued before it
 * started, so a task that queues another defers it by a full tick.
 */
public final class ServerActionQueue {

    static final int MAX_PENDING = 8192;
    static final int MAX_PER_TICK = 2048;

    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong processed = new AtomicLong(), overflow = new AtomicLong(), failed = new AtomicLong();

    /** Any thread. Returns false (and counts it) if the queue is full. */
    public boolean offer(Runnable task) {
        if (size.incrementAndGet() > MAX_PENDING) {
            size.decrementAndGet();
            overflow.incrementAndGet();
            return false;
        }
        queue.add(task);
        return true;
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
            Runnable r = queue.poll();
            if (r == null) break;
            size.decrementAndGet();
            processed.incrementAndGet();
            try {
                r.run();
            } catch (RuntimeException e) {
                failed.incrementAndGet();
                AkashicWeb.LOG.error("AkashicWeb: queued server action failed", e);
            }
        }
        return ran;
    }

    public int pending() {
        return size.get();
    }

    public String stats() {
        return "processed=" + processed
            .get() + " pending=" + size.get() + " overflow=" + overflow.get() + " failed=" + failed.get();
    }

    public void clear() {
        queue.clear();
        size.set(0);
    }
}
