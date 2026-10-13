package com.akashiic.web.client.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import com.akashiic.web.client.sync.SyncDecision.Kind;

class SyncControllerTest {

    private static final long SERVER_STAMP = 1_000;
    private static final double SERVER_NOW = 1_000;

    private static MediaClock server(double mediaTime) {
        return new MediaClock(1, mediaTime, 1f, false, SERVER_STAMP);
    }

    /** A VOD sample received at client time 0. */
    private static LocalMedia vod(double mediaTime) {
        return vod(mediaTime, 0);
    }

    /** A VOD sample received at {@code receivedMs} (fresh when evaluated at the same time). */
    private static LocalMedia vod(double mediaTime, long receivedMs) {
        return new LocalMedia(mediaTime, false, 1f, 600, false, 0, false, false, receivedMs, Double.NaN);
    }

    private static LocalMedia live(double mediaTime, double behind) {
        return live(mediaTime, behind, 0);
    }

    private static LocalMedia live(double mediaTime, double behind, long receivedMs) {
        return new LocalMedia(mediaTime, false, 1f, Double.NaN, true, behind, false, false, receivedMs, Double.NaN);
    }

    private static SyncDecision run(SyncController c, MediaClock s, LocalMedia l, long now) {
        return c.update(s, SERVER_NOW, l, now, Double.NaN);
    }

    @Test
    void insideDeadbandPlaysAtNormalRate() {
        SyncDecision d = run(new SyncController(), server(10.0), vod(10.03), 0);
        assertEquals(Kind.IN_SYNC, d.kind);
        assertEquals(1.0f, d.command.rate);
        assertFalse(d.command.seek);
        assertEquals(-0.03, d.errorSeconds, 1e-9);
    }

    @Test
    void steersProportionallyAndClampsTheRate() {
        assertEquals(1.05f, run(new SyncController(), server(10.0), vod(9.9), 0).command.rate, 1e-6);
        assertEquals(1.025f, run(new SyncController(), server(10.0), vod(9.95), 0).command.rate, 1e-6);
        assertEquals(1.05f, run(new SyncController(), server(10.0), vod(9.0), 0).command.rate, 1e-6);
        assertEquals(0.95f, run(new SyncController(), server(10.0), vod(11.0), 0).command.rate, 1e-6);
        SyncDecision ahead = run(new SyncController(), server(10.0), vod(10.06), 0);
        assertEquals(Kind.STEERING, ahead.kind);
        assertEquals(0.97f, ahead.command.rate, 1e-6);
    }

    @Test
    void rateIsAlwaysWithinBoundsAndNeverOneWhileSteering() {
        Random r = new Random(1);
        for (int i = 0; i < 2_000; i++) {
            double err = (r.nextDouble() - 0.5) * 3.0; // within the seek threshold
            float rate = SyncController.steeringRate(err);
            assertTrue(rate >= SyncController.MIN_RATE && rate <= SyncController.MAX_RATE, "rate " + rate);
            assertTrue(Math.abs(rate - 1.0f) > 1e-4, "steering rate must not be exactly 1 for err " + err);
            assertEquals(Math.signum(err), Math.signum(rate - 1.0f), 0, "rate moves toward the target");
        }
    }

    @Test
    void hysteresisKeepsSteeringUntilTheReleaseBand() {
        SyncController c = new SyncController();
        assertEquals(Kind.STEERING, run(c, server(10.0), vod(9.95), 0).kind); // 50 ms: start
        SyncDecision mid = run(c, server(10.0), vod(9.97, 250), 250); // 30 ms: inside ±40 but still steering
        assertEquals(Kind.STEERING, mid.kind);
        assertTrue(mid.command.rate > 1.0f);
        assertEquals(Kind.IN_SYNC, run(c, server(10.0), vod(9.995, 500), 500).kind); // 5 ms: released
        assertEquals(Kind.IN_SYNC, run(c, server(10.0), vod(9.97, 750), 750).kind); // 30 ms again: deadband holds
    }

    @Test
    void vodSeeksOnlyAboveOnePointFiveSecondsWithCooldown() {
        SyncController c = new SyncController();
        assertEquals(Kind.STEERING, run(c, server(10.0), vod(8.6), 0).kind); // 1.4 s
        SyncDecision seek = run(c, server(10.0), vod(8.4), 0); // 1.6 s
        assertEquals(Kind.SEEK, seek.kind);
        assertTrue(seek.command.seek);
        assertEquals(10.0, seek.command.targetMediaTime, 1e-9);
        assertEquals(1.0f, seek.command.rate);
        SyncDecision cooling = run(c, server(10.0), vod(8.4, 1_000), 1_000);
        assertEquals(Kind.STEERING, cooling.kind, "no second seek inside the cooldown");
        assertEquals(1.05f, cooling.command.rate, 1e-6);
        long later = SyncController.SEEK_COOLDOWN_MS;
        assertEquals(Kind.SEEK, run(c, server(10.0), vod(8.4, later), later).kind);
        c.reset();
        assertEquals(Kind.SEEK, run(c, server(10.0), vod(8.4, later + 1), later + 1).kind, "reset clears the cooldown");
    }

    @Test
    void extrapolatesBothClocks() {
        MediaClock s = new MediaClock(1, 10.0, 1f, false, 1_000);
        LocalMedia l = new LocalMedia(11.5, false, 1f, 600, false, 0, false, false, 0, Double.NaN);
        // Server at 3000 ms: controller at 12.0. Local at 500 ms: 12.0.
        SyncDecision d = new SyncController().update(s, 3_000, l, 500, Double.NaN);
        assertEquals(Kind.IN_SYNC, d.kind);
        assertEquals(0, d.errorSeconds, 1e-9);
    }

    @Test
    void targetIsClampedToTheDuration() {
        SyncDecision d = run(new SyncController(), server(700), vod(599.99), 0);
        assertEquals(Kind.IN_SYNC, d.kind);
        assertEquals(600, d.command.targetMediaTime, 1e-9);
    }

    @Test
    void followsAPauseAndAlignsTheFrame() {
        MediaClock paused = new MediaClock(1, 10.0, 1f, true, SERVER_STAMP);
        SyncDecision far = new SyncController().update(paused, 99_999, vod(10.5), 0, Double.NaN);
        assertEquals(Kind.PAUSED, far.kind);
        assertTrue(far.command.paused);
        assertTrue(far.command.seek, "0.5 s off while paused: align");
        assertEquals(10.0, far.command.targetMediaTime, 1e-9, "a paused clock does not extrapolate");
        assertFalse(new SyncController().update(paused, 1_000, vod(10.1), 0, Double.NaN).command.seek);
    }

    @Test
    void suspendedWhileOurOwnAdShows() {
        SyncController c = new SyncController();
        assertEquals(Kind.STEERING, run(c, server(10.0), vod(9.9), 0).kind);
        LocalMedia ad = new LocalMedia(3.0, false, 1f, 15, false, 0, true, false, 250, Double.NaN);
        SyncDecision d = run(c, server(10.0), ad, 250);
        assertEquals(Kind.AD_SUSPENDED, d.kind);
        assertEquals(1.0f, d.command.rate, "rate back to normal during the ad");
        assertFalse(d.command.seek);
        assertFalse(c.isSteering());
    }

    @Test
    void waitsWithoutInputsOrWithAStaleSample() {
        SyncController c = new SyncController();
        assertSame(SyncDecision.WAITING, run(c, null, vod(1), 0));
        assertSame(SyncDecision.WAITING, run(c, server(1), null, 0));
        assertSame(SyncDecision.WAITING, c.update(server(1), Double.NaN, vod(1), 0, Double.NaN));
        assertSame(SyncDecision.WAITING, run(c, server(1), vod(1), SyncController.LOCAL_STALE_MS + 1));
        assertNull(SyncDecision.WAITING.command);
    }

    @Test
    void liveTargetLagIsMaxInRangeLagPlusMargin() {
        assertEquals(2.0, SyncController.liveTargetLag(1.5, Double.NaN), 1e-9);
        assertEquals(0.5, SyncController.liveTargetLag(-1.0, Double.NaN), 1e-9, "a controller ahead counts as 0");
        assertEquals(4.5, SyncController.liveTargetLag(1.5, 4.0), 1e-9);
        assertEquals(0.5, SyncController.liveTargetLag(Double.NaN, Double.NaN), 1e-9);
    }

    @Test
    void liveFollowerSitsHalfASecondBehindTheLargestLag() {
        // Our edge at 102; controller at 100.5 (1.5 s behind our edge) -> target lag 2.0 -> target 100.0.
        SyncDecision d = run(new SyncController(), server(100.5), live(100.0, 2.0), 0);
        assertTrue(d.live);
        assertEquals(Kind.IN_SYNC, d.kind);
        assertEquals(100.0, d.command.targetMediaTime, 1e-9);
        // Controller ahead of our edge: we aim 0.5 s inside our own edge, never past it.
        SyncDecision ahead = run(new SyncController(), server(103.0), live(100.0, 2.0), 0);
        assertEquals(101.5, ahead.command.targetMediaTime, 1e-9);
        assertEquals(Kind.STEERING, ahead.kind);
        assertEquals(1.05f, ahead.command.rate, 1e-6);
        assertFalse(ahead.command.seek, "1.5 s on a live is steered, not seeked");
    }

    @Test
    void liveNeverRunsFasterThanRealTimeInsideTheMargin() {
        Random r = new Random(7);
        for (int i = 0; i < 2_000; i++) {
            double behind = r.nextDouble() * 6;
            double controller = 100 + (r.nextDouble() - 0.5) * 12;
            double known = r.nextBoolean() ? Double.NaN : r.nextDouble() * 5;
            SyncDecision d = new SyncController().update(server(controller), SERVER_NOW, live(100, behind), 0, known);
            double edge = 100 + behind;
            assertTrue(d.command.targetMediaTime <= edge - SyncController.LIVE_MARGIN_S + 1e-9, "target past edge");
            if (behind <= SyncController.LIVE_MARGIN_S) assertTrue(d.command.rate <= 1.0f, "rate up near edge");
        }
    }

    @Test
    void liveSeeksOnlyForGrossErrorsWithALongCooldown() {
        SyncController c = new SyncController();
        // Our edge 110, we are 10 s behind; controller 1 s behind -> target lag 1.5 -> target 108.5 -> error 8.5 s.
        SyncDecision d = c.update(server(109.0), SERVER_NOW, live(100.0, 10.0), 0, Double.NaN);
        assertEquals(Kind.SEEK, d.kind);
        assertEquals(108.5, d.command.targetMediaTime, 1e-9);
        SyncDecision again = c.update(server(109.0), SERVER_NOW, live(100.0, 10.0, 30_000), 30_000, Double.NaN);
        assertEquals(Kind.STEERING, again.kind);
        long later = SyncController.LIVE_SEEK_COOLDOWN_MS;
        assertEquals(Kind.SEEK, c.update(server(109.0), SERVER_NOW, live(100.0, 10.0, later), later, Double.NaN).kind);
    }

    private static MediaClock liveServer(double mediaTime, int liveLagMs) {
        return new MediaClock(1, mediaTime, 1f, false, SERVER_STAMP, true, liveLagMs);
    }

    /** D-016: S2CMediaClock carries the controller's own lag behind its edge; it counts as an in-range lag. */
    @Test
    void theControllersReportedLagSetsTheTargetWhenItIsTheLargest() {
        MediaClock server = liveServer(101.0, 3_000); // 1 s behind our edge by position, 3 s by its own report
        SyncDecision d = new SyncController().update(server, SERVER_NOW, live(101.0, 1.0), 0, server.liveLagSeconds());
        // Our edge 102, target lag 3.0 + 0.5 -> target 98.5: we steer back (rate < 1), never forward past the edge.
        assertEquals(98.5, d.command.targetMediaTime, 1e-9);
        assertTrue(d.command.rate < 1.0f);
        assertEquals(3.0, server.liveLagSeconds(), 1e-9);
        assertTrue(Double.isNaN(new MediaClock(1, 0, 1f, false, 0, false, 500).liveLagSeconds()), "VOD: no lag");
        assertTrue(Double.isNaN(liveServer(0, -1).liveLagSeconds()), "garbage from the wire: unknown");
    }

    /** ADR-012 fallback: when live currentTime is not comparable across clients, steer on lags alone. */
    @Test
    void incomparableLiveTimelinesSteerOnTheReportedLagInsteadOfSeekingFar() {
        // The controller's player counts from 10 s, ours from 100 s: by position it is 92 s behind our edge.
        MediaClock server = liveServer(10.0, 1_000);
        SyncController c = new SyncController();
        SyncDecision d = c.update(server, SERVER_NOW, live(100.0, 2.0), 0, server.liveLagSeconds());
        assertTrue(d.kind != Kind.SEEK, "no 90 s jump backwards: " + d);
        assertEquals(100.5, d.command.targetMediaTime, 1e-9, "1.0 s reported lag + 0.5 s behind our edge (102)");
        assertFalse(SyncController.comparableTimelines(92.0, 1.0));
        assertTrue(SyncController.comparableTimelines(2.0, 1.0));
        assertTrue(SyncController.comparableTimelines(92.0, Double.NaN), "without a report we must assume so");
    }

    @Test
    void aLiveOurPlayerDoesNotKnowYetPlaysAtNormalSpeed() {
        SyncDecision d = new SyncController().update(liveServer(500.0, 2_000), SERVER_NOW, vod(10.0), 0, 2.0);
        assertEquals(Kind.LIVE_EDGE_UNKNOWN, d.kind);
        assertEquals(1.0f, d.command.rate);
        assertFalse(d.command.seek, "a VOD-style seek to 500 s could run past the live edge");
        assertTrue(d.live);
    }

    @Test
    void livePauseIsFollowedWithoutSeeking() {
        MediaClock paused = new MediaClock(1, 50, 1f, true, SERVER_STAMP);
        SyncDecision d = new SyncController().update(paused, SERVER_NOW, live(100, 5), 0, Double.NaN);
        assertEquals(Kind.PAUSED, d.kind);
        assertTrue(d.command.paused);
        assertFalse(d.command.seek);
    }
}
