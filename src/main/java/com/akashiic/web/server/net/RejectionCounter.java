package com.akashiic.web.server.net;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.entity.player.EntityPlayerMP;

import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.server.ScreenActions;
import com.akashiic.web.server.ScreenWorld;
import com.akashiic.web.server.ServerActionQueue;
import com.akashiic.web.server.ServerClock;
import com.akashiic.web.server.ServerRuntime;

/**
 * Packets refused on arrival (malformed, over the rate limit, from a FakePlayer) are counted here instead of each
 * queueing a task (review finding: 1.7.10 processes up to 1001 packets per tick per connection, so a few flooding
 * clients filled the global action queue with "rejected" tasks). A player has at most one flush task queued at a
 * time; on the main thread it records each reason once, with the count, in the player's diagnostics and the
 * throttled audit log. Arrival side thread-safe.
 */
final class RejectionCounter {

    static final ReasonCode[] REASONS = { ReasonCode.BAD_PACKET, ReasonCode.RATE_LIMITED, ReasonCode.FAKE_PLAYER };

    /** One player's refusals since the last flush. Counters exist for every reason from the start. */
    static final class Tally {

        final EntityPlayerMP player;
        final Map<ReasonCode, AtomicInteger> counts = new EnumMap<>(ReasonCode.class);
        final Map<ReasonCode, String> lastAction = new ConcurrentHashMap<>();
        final Map<ReasonCode, String> lastDetail = new ConcurrentHashMap<>();
        final AtomicBoolean flushQueued = new AtomicBoolean();

        Tally(EntityPlayerMP player) {
            this.player = player;
            for (ReasonCode r : REASONS) counts.put(r, new AtomicInteger());
        }
    }

    private final ConcurrentHashMap<UUID, Tally> tallies = new ConcurrentHashMap<>();
    private final ServerActionQueue queue;

    RejectionCounter(ServerActionQueue queue) {
        this.queue = queue;
    }

    /** Any thread. Counts the refusal and makes sure one flush is queued for this player. */
    void add(EntityPlayerMP p, ReasonCode reason, String action, String detail) {
        UUID id = p.getUniqueID();
        if (id == null) return;
        Tally t = tallies.compute(id, (k, old) -> old == null || old.player != p ? new Tally(p) : old);
        t.counts.get(reason)
            .incrementAndGet();
        t.lastAction.put(reason, action);
        t.lastDetail.put(reason, detail == null ? "" : detail);
        if (t.flushQueued.compareAndSet(false, true) && !queue.offer(id, () -> flush(t))) {
            t.flushQueued.set(false); // the player's share is full: the next refusal tries again
        }
    }

    /** Main thread. */
    void flush(Tally t) {
        t.flushQueued.set(false); // first, so a refusal arriving now queues the next flush
        if (!ScreenWorld.isOnline(t.player)) return; // logged out meanwhile: logout already dropped their records
        for (ReasonCode reason : REASONS) {
            int n = t.counts.get(reason)
                .getAndSet(0);
            if (n == 0) continue;
            String action = t.lastAction.get(reason);
            String detail = t.lastDetail.get(reason);
            String what = (detail == null || detail.isEmpty() ? "" : detail + " ") + "x" + n;
            if (reason == ReasonCode.FAKE_PLAYER) {
                ServerRuntime.DIAGNOSTICS
                    .record(t.player.getUniqueID(), reason, action + " " + what, ServerClock.nowMs());
            } else {
                // A flood must not flood notices: media and clock pings are refused quietly.
                boolean quiet = "media".equals(action) || "ping".equals(action);
                ScreenActions.reject(t.player, reason, action, what, quiet);
            }
        }
    }

    /** Refusals counted and not yet flushed for a player (tests, diagnostics). */
    int pending(UUID id) {
        Tally t = tallies.get(id);
        if (t == null) return 0;
        int n = 0;
        for (AtomicInteger c : t.counts.values()) n += c.get();
        return n;
    }

    void forget(UUID id) {
        tallies.remove(id);
    }

    void clear() {
        tallies.clear();
    }
}
