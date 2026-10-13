package com.akashiic.web.server;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import com.akashiic.web.common.HitMath;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.WebConfig;
import com.akashiic.web.common.WebSettings;
import com.akashiic.web.common.block.TileEntityScreen;
import com.akashiic.web.common.net.S2CMediaClock;
import com.akashiic.web.server.command.CommandSupport;

/**
 * Server-side singletons and the per-tick work: drain the action queue, broadcast media clocks at 2-4 Hz and release
 * idle or absent controllers once a second. Everything here runs on the main thread except the thread-safe queue,
 * rate limits and diagnostics counters reached from network handlers.
 */
public final class ServerRuntime {

    public static final ServerActionQueue QUEUE = new ServerActionQueue();
    public static final ActionLimits LIMITS = new ActionLimits();
    public static final AuditThrottle THROTTLE = new AuditThrottle();
    public static final PlayerDiagnostics DIAGNOSTICS = new PlayerDiagnostics();
    public static final LoadedScreens LOADED = new LoadedScreens();

    private static final Map<ScreenKey, ScreenSession> SESSIONS = new HashMap<>();
    private static ScreenIndexData indexData;
    private static long ticks;

    private ServerRuntime() {}

    /** Per-screen transient state, created on first use. */
    public static ScreenSession session(ScreenState s) {
        ScreenSession session = SESSIONS.get(s.key);
        if (session == null) {
            session = new ScreenSession(s.epoch);
            if (s.url != null && !s.url.isEmpty()) session.history.push(s.url);
            SESSIONS.put(s.key, session);
        }
        return session;
    }

    public static ScreenSession peekSession(ScreenKey key) {
        return SESSIONS.get(key);
    }

    public static void endSession(ScreenKey key) {
        SESSIONS.remove(key);
    }

    public static int sessions() {
        return SESSIONS.size();
    }

    /** The persistent screen index (overworld map storage), or an empty detached one before the overworld exists. */
    public static ScreenIndex index() {
        if (indexData == null) {
            World overworld = DimensionManager.getWorld(0);
            if (overworld == null) return new ScreenIndex();
            indexData = ScreenIndexData.get(overworld);
        }
        return indexData.index;
    }

    public static void indexChanged() {
        if (indexData != null) indexData.markDirty();
    }

    /** ServerTickEvent START. */
    public static void tick() {
        ServerThread.mark();
        CommandSupport.runQueued();
        QUEUE.drain();
        ticks++;
        WebSettings cfg = WebConfig.get();
        if (ticks % cfg.mediaClockIntervalTicks() == 0) broadcastClocks(cfg);
        if (ticks % 20 == 0) releaseIdleControllers(cfg);
    }

    private static void broadcastClocks(WebSettings cfg) {
        if (cfg.killSwitch || SESSIONS.isEmpty()) return;
        long now = ServerClock.nowMs();
        for (ScreenState s : LOADED.faces()) {
            ScreenSession session = SESSIONS.get(s.key);
            if (session == null || !session.clock.valid() || session.clock.epoch() != s.epoch) continue;
            ScreenSync.sendToTrackers(
                s.key,
                new S2CMediaClock(
                    s.key,
                    s.epoch,
                    session.clock.positionAt(now),
                    session.clock.rate(),
                    session.clock.paused(),
                    now,
                    session.clock.live(),
                    session.clock.liveLagMs()));
        }
    }

    /**
     * The controller lock is released when its holder logged out, changed dimension, walked far away or sent no input
     * for {@code controllerIdleSeconds}; the media clock keeps running from its last anchor.
     */
    private static void releaseIdleControllers(WebSettings cfg) {
        long now = ServerClock.nowMs();
        for (ScreenState s : LOADED.faces()) {
            if (s.controller == null) continue;
            EntityPlayerMP p = ScreenWorld.online(s.controller);
            ScreenSession session = session(s);
            String why = null;
            if (p == null) why = "offline";
            else if (p.dimension != s.key.dim) why = "dimension";
            else {
                double[] eye = ScreenWorld.eye(p);
                if (HitMath.distanceToScreen(s, eye[0], eye[1], eye[2]) > cfg.interactRange * 2) why = "far";
                else if (now - session.lastInputMs > cfg.controllerIdleSeconds * 1000L) why = "idle";
            }
            if (why != null) ScreenActions.releaseControl(s, why);
        }
    }

    /** Server stopped: forget everything (a new world may load next). */
    public static void stop() {
        QUEUE.clear();
        LIMITS.clear();
        THROTTLE.clear();
        DIAGNOSTICS.clear();
        LOADED.clear();
        SESSIONS.clear();
        indexData = null;
        ticks = 0;
        ServerThread.clear();
        BukkitBridge.reset();
        AuditLog.close();
    }

    /** Lookup helper for commands and actions. */
    public static TileEntityScreen tile(ScreenKey key) {
        return LOADED.tile(key);
    }
}
