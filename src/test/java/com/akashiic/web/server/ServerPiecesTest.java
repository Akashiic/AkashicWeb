package com.akashiic.web.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.TestScreens;
import com.akashiic.web.common.WebSettings;

/** Queue deferral, rate limits per kind, audit throttle and format, diagnostics and the screen index. */
class ServerPiecesTest {

    @Test
    void queueDefersTasksQueuedDuringADrainByOneTick() {
        ServerActionQueue q = new ServerActionQueue();
        List<String> log = new ArrayList<>();
        q.offer(() -> {
            log.add("form");
            q.offer(() -> log.add("re-validate"));
        });
        assertEquals(1, q.drain());
        assertEquals(1, log.size(), "a task queued while draining waits for the next tick");
        assertEquals(1, q.pending());
        q.drain();
        assertEquals("re-validate", log.get(1));
    }

    @Test
    void oneFailingTaskDoesNotStopTheOthers() {
        ServerActionQueue q = new ServerActionQueue();
        List<String> log = new ArrayList<>();
        q.offer(() -> { throw new IllegalStateException("boom"); });
        q.offer(() -> log.add("ok"));
        assertEquals(2, q.drain());
        assertEquals(1, log.size());
        assertTrue(
            q.stats()
                .contains("failed=1"));
    }

    @Test
    void queueIsBounded() {
        ServerActionQueue q = new ServerActionQueue();
        for (int i = 0; i < ServerActionQueue.MAX_PENDING; i++) assertTrue(q.offer(() -> {}));
        assertFalse(q.offer(() -> {}));
        assertTrue(
            q.stats()
                .contains("overflow=1"));
        assertEquals(ServerActionQueue.MAX_PER_TICK, q.drain());
    }

    /** Review finding: one flooding player could use the whole queue and starve everyone, heals included. */
    @Test
    void onePlayerCannotTakeMoreThanItsShare() {
        ServerActionQueue q = new ServerActionQueue();
        UUID flooder = UUID.randomUUID(), other = UUID.randomUUID();
        for (int i = 0; i < ServerActionQueue.MAX_PER_PLAYER; i++) assertTrue(q.offer(flooder, () -> {}));
        assertFalse(q.offer(flooder, () -> {}), "the flooder's own cap");
        assertEquals(ServerActionQueue.MAX_PER_PLAYER, q.pending(flooder));
        assertTrue(q.offer(other, () -> {}), "everyone else is unaffected");
        q.drain();
        assertEquals(0, q.pending(flooder));
        assertTrue(q.offer(flooder, () -> {}), "room again after the tick");
    }

    @Test
    void playersTogetherNeverUseTheSystemReserve() {
        ServerActionQueue q = new ServerActionQueue();
        int playerSlots = ServerActionQueue.MAX_PENDING - ServerActionQueue.SYSTEM_RESERVE;
        for (int p = 0; q.offer(new UUID(7, p), () -> {}); p++) {
            // one task per player, so only the shared limit stops them
        }
        assertEquals(playerSlots, q.pending(), "player tasks stop at the reserve");
        for (int i = 0; i < ServerActionQueue.SYSTEM_RESERVE; i++) assertTrue(q.offer(() -> {}), "heal " + i);
        assertFalse(q.offer(() -> {}), "and the queue is still bounded");
    }

    /** Review finding: a heal refused by a full queue stayed "pending" forever, so that screen was never healed. */
    @Test
    void aRefusedHealCanBeScheduledAgain() {
        ServerRuntime.stop();
        try {
            while (ServerRuntime.QUEUE.offer(() -> {})) {
                // fill it with server tasks
            }
            ScreenKey key = new ScreenKey(0, 10, 64, 10, 2);
            ScreenLifecycle.scheduleHeal(key);
            assertFalse(ScreenLifecycle.healPending(key), "not left pending when the queue refused it");
            ServerRuntime.QUEUE.clear();
            ScreenLifecycle.scheduleHeal(key);
            assertEquals(1, ServerRuntime.QUEUE.pending());
            assertTrue(ScreenLifecycle.healPending(key));
        } finally {
            ScreenLifecycle.clear();
            ServerRuntime.stop();
        }
    }

    @Test
    void rateLimitsAreSeparatePerKind() {
        AtomicLong now = new AtomicLong();
        ActionLimits l = new ActionLimits(now::get);
        WebSettings cfg = WebSettings.defaults();
        UUID p = UUID.randomUUID();
        int urls = 0;
        while (l.tryAcquire(p, ActionLimits.Kind.URL, cfg)) urls++;
        assertEquals(cfg.actionsPerSecond * 2, urls);
        assertTrue(l.tryAcquire(p, ActionLimits.Kind.MEDIA, cfg), "media bucket unaffected");
        int samples = 1;
        while (l.tryAcquire(p, ActionLimits.Kind.MEDIA, cfg)) samples++;
        assertEquals(cfg.mediaSamplesPerSecond * 2, samples);
        l.forget(p);
        assertTrue(l.tryAcquire(p, ActionLimits.Kind.URL, cfg));
    }

    @Test
    void auditThrottleKeepsOneLinePerWindow() {
        AuditThrottle t = new AuditThrottle();
        UUID p = UUID.randomUUID();
        assertEquals(0, t.admit(p, ReasonCode.TOO_FAR, 0));
        assertEquals(-1, t.admit(p, ReasonCode.TOO_FAR, 100));
        assertEquals(-1, t.admit(p, ReasonCode.TOO_FAR, 200));
        assertEquals(0, t.admit(p, ReasonCode.NO_PERMISSION, 200), "other reasons are independent");
        assertEquals(2, t.admit(p, ReasonCode.TOO_FAR, AuditThrottle.WINDOW_MS + 1));
        t.forget(p);
        assertEquals(0, t.admit(p, ReasonCode.TOO_FAR, AuditThrottle.WINDOW_MS + 2));
    }

    @Test
    void auditLinesCannotBeForged() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String line = AuditLog.format(0, "Eve\n2026-01-01T00:00:00Z admin", id, "url", "https://a.com/\r\nfake");
        assertEquals(
            "1970-01-01T00:00:00Z Eve 2026-01-01T00:00:00Z admin (00000000-0000-0000-0000-000000000001) url: https://a.com/  fake",
            line);
        assertEquals("1970-01-01T00:00:00Z console kill switch", AuditLog.format(0, null, null, "kill switch", ""));
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 2000; i++) huge.append('x');
        assertEquals(
            512,
            AuditLog.clean(huge.toString())
                .length());
    }

    @Test
    void diagnosticsKeepRecentAndCounts() {
        PlayerDiagnostics d = new PlayerDiagnostics();
        UUID p = UUID.randomUUID();
        assertNull(d.peek(p));
        for (int i = 0; i < 12; i++) d.record(p, i % 2 == 0 ? ReasonCode.TOO_FAR : ReasonCode.URL_BLOCKED, "d" + i, i);
        PlayerDiagnostics.Record r = d.peek(p);
        assertEquals(
            PlayerDiagnostics.RECENT,
            r.recent()
                .size());
        assertEquals(
            "d11",
            r.recent()
                .get(PlayerDiagnostics.RECENT - 1).detail);
        assertEquals(
            6,
            (int) r.counts()
                .get(ReasonCode.TOO_FAR));
        d.forget(p);
        assertNull(d.peek(p));
    }

    @Test
    void indexCountsQueriesAndPersists() {
        ScreenIndex idx = new ScreenIndex();
        ScreenState a = TestScreens.state(BlockSide.NORTH, 10, 64, 20, 4, 3, 0);
        ScreenState b = TestScreens.state(BlockSide.TOP, 100, 64, 100, 2, 2, 0);
        b.owner = TestScreens.FRIEND;
        assertTrue(idx.put(ScreenIndex.Entry.of(a)));
        assertFalse(idx.put(ScreenIndex.Entry.of(a)), "unchanged entry: no dirty mark");
        idx.put(ScreenIndex.Entry.of(b));
        assertEquals(1, idx.ownedBy(TestScreens.OWNER));
        assertEquals(1, idx.ownedBy(TestScreens.FRIEND));
        assertEquals(
            1,
            idx.within(0, 12, 64, 20, 5)
                .size());
        assertEquals(
            0,
            idx.within(1, 12, 64, 20, 5)
                .size());
        assertEquals(
            1,
            idx.containing(0, 8, 66, 20)
                .size()); // north face extends towards -X
        assertEquals(
            0,
            idx.containing(0, 11, 66, 20)
                .size());
        assertEquals(
            1,
            idx.inChunk(0, 0, 1)
                .size()); // origin (10, 20) is in chunk (0, 1)
        NBTTagCompound tag = ScreenIndexData.write(idx.get(a.key));
        ScreenIndex.Entry back = ScreenIndexData.read(tag);
        assertEquals(a.key, back.key);
        assertEquals(TestScreens.OWNER, back.owner);
        assertEquals(4, back.width);
        assertTrue(idx.remove(a.key));
        assertFalse(idx.remove(new ScreenKey(0, 0, 0, 0, 0)));
    }

    @Test
    void indexKeepsUrlsShort() {
        StringBuilder url = new StringBuilder("https://a.com/");
        while (url.length() < 1000) url.append('x');
        ScreenState s = TestScreens.state(BlockSide.SOUTH, 0, 0, 0, 2, 2, 0);
        s.url = url.toString();
        assertEquals(ScreenIndex.URL_CHARS, ScreenIndex.Entry.of(s).url.length());
    }
}
