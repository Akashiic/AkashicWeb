package com.akashiic.web.server;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.Rights;
import com.akashiic.web.common.RightsPolicy;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenMode;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.UrlGate;
import com.akashiic.web.common.WebConfig;
import com.akashiic.web.common.WebSettings;
import com.akashiic.web.common.block.TileEntityScreen;
import com.akashiic.web.common.net.C2SControl;
import com.akashiic.web.common.net.C2SMediaSample;
import com.akashiic.web.common.net.C2SNav;
import com.akashiic.web.common.net.C2SRights;
import com.akashiic.web.common.net.C2SSetUrl;
import com.akashiic.web.common.net.S2CUrl;

/**
 * Applies validated client actions to screens, on the main thread at the start of a tick (queued by
 * {@code server.net.ServerPackets}). Every action goes through the same gate: dimension, loaded screen, kill switch,
 * distance, rights and controller lock, epoch. Refusals become reason codes (notice to the player, throttled audit
 * line, diagnostics); accepted changes are audited and broadcast.
 */
public final class ScreenActions {

    private ScreenActions() {}

    /** A resolved target screen for one action. */
    static final class Target {

        final EntityPlayerMP player;
        final UUID id;
        final WorldServer world;
        final TileEntityScreen tile;
        final ScreenState state;
        final WebSettings cfg;
        final boolean admin;
        final double[] eye;

        Target(EntityPlayerMP player, WorldServer world, TileEntityScreen tile, ScreenState state, WebSettings cfg,
            boolean admin) {
            this.player = player;
            this.id = player.getUniqueID();
            this.world = world;
            this.tile = tile;
            this.state = state;
            this.cfg = cfg;
            this.admin = admin;
            this.eye = ScreenWorld.eye(player);
        }
    }

    /** Common gate. Null (and the refusal recorded) when the action cannot go further. */
    static Target resolve(EntityPlayerMP p, ScreenKey key, String action, boolean quiet) {
        if (p.isDead || p.playerNetServerHandler == null) return null;
        if (key.dim != p.dimension) {
            reject(p, ReasonCode.WRONG_DIMENSION, action, key.toString(), quiet);
            return null;
        }
        WorldServer w = ScreenWorld.world(key.dim);
        TileEntityScreen te = ScreenWorld.tile(w, key.x, key.y, key.z);
        ScreenState s = te == null ? null : te.screen(key.side);
        if (s == null) {
            reject(p, ReasonCode.UNKNOWN_SCREEN, action, key.toString(), quiet);
            return null;
        }
        WebSettings cfg = WebConfig.get();
        boolean admin = Admins.isAdmin(p, cfg);
        if (cfg.killSwitch && !admin) {
            reject(p, ReasonCode.DISABLED_BY_SERVER, action, "", quiet);
            return null;
        }
        return new Target(p, w, te, s, cfg, admin);
    }

    /**
     * Records a refusal: diagnostics always; audit line and notice at most once per player, reason and window
     * (a flood of pointer packets yields one line, not thousands).
     */
    public static void reject(EntityPlayerMP p, ReasonCode code, String action, String detail, boolean quiet) {
        long now = ServerClock.nowMs();
        ServerRuntime.DIAGNOSTICS.record(p.getUniqueID(), code, action + " " + detail, now);
        int suppressed = ServerRuntime.THROTTLE.admit(p.getUniqueID(), code, now);
        if (suppressed < 0) return;
        AuditLog.log(
            p.getCommandSenderName(),
            p.getUniqueID(),
            "rejected " + action,
            code.name() + (detail.isEmpty() ? "" : " " + detail)
                + (suppressed > 0 ? " (+" + suppressed + " more)" : ""));
        if (!quiet) ScreenSync.notice(p, code, detail);
    }

    /**
     * {@link ActionRules#urlChange}: reach, rights, then the epoch. A refusal is recorded; a stale epoch (someone
     * changed the page meanwhile) also shows the sender the current page, which is why it is checked last.
     */
    private static boolean urlAllowed(Target t, int epoch, String action, String detail) {
        ReasonCode r = ActionRules.urlChange(t.state, t.id, t.admin, t.eye, epoch, t.cfg.interactRange);
        if (r == ReasonCode.OK) return true;
        if (r == ReasonCode.STALE_EPOCH) {
            reject(t.player, r, action, detail, true);
            ScreenSync.sendTo(t.player, new S2CUrl(t.state.key, t.state.url, t.state.epoch));
        } else {
            reject(t.player, r, action, detail, false);
        }
        return false;
    }

    // ---- URL and navigation ----

    public static void setUrl(EntityPlayerMP p, C2SSetUrl m) {
        Target t = resolve(p, m.key, "url", false);
        if (t == null || !urlAllowed(t, m.epoch, "url", "")) return;
        ScreenState s = t.state;
        UrlGate.Result r = t.cfg.urlGate()
            .check(m.url);
        if (!r.ok()) {
            reject(p, r.reason, "url", r.detail, false);
            return;
        }
        ScreenSession session = ServerRuntime.session(s);
        if (!NavLogic.setUrl(s, session.history, r.url)) return;
        applyUrlChange(t, session, "url");
    }

    public static void nav(EntityPlayerMP p, C2SNav m) {
        if ("stop".equals(m.action)) return; // only the sender's own browser stops: nothing to check or share
        Target t = resolve(p, m.key, "nav", false);
        if (t == null || !urlAllowed(t, m.epoch, "nav", m.action)) return;
        ScreenState s = t.state;
        ScreenSession session = ServerRuntime.session(s);
        String target = NavLogic.target(m.action, s, session.history, t.cfg.homeUrl);
        if (target == null) return; // no history in that direction
        UrlGate.Result r = t.cfg.urlGate()
            .check(target);
        if (!r.ok()) {
            reject(p, r.reason, "nav", m.action + " " + r.detail, false);
            return;
        }
        NavLogic.commit(m.action, s, session.history, r.url);
        applyUrlChange(t, session, "nav " + m.action);
    }

    private static void applyUrlChange(Target t, ScreenSession session, String what) {
        ScreenState s = t.state;
        session.clock.reset(s.epoch);
        t.tile.markDirty();
        if (ServerRuntime.index()
            .put(ScreenIndex.Entry.of(s))) ServerRuntime.indexChanged();
        AuditLog.log(t.player.getCommandSenderName(), t.id, what, s.key + " epoch=" + s.epoch + " " + s.url);
        ScreenSync.sendUrl(s);
    }

    // ---- media clock ----

    /**
     * The controller's media sample (ADR-012 item 3). Only the current controller, and only while it still holds
     * CLICK_TYPE: rights can change under a held lock, and a revoked controller must stop steering everyone's clock at
     * once (the lock itself goes at the next {@link ControlLock} sweep). A sample of playing media (or of an ad)
     * counts as lock activity: watching is not idling.
     */
    public static void media(EntityPlayerMP p, C2SMediaSample m) {
        Target t = resolve(p, m.key, "media", true);
        if (t == null) return;
        if (t.state.mode == ScreenMode.PRIVATE || !t.id.equals(t.state.controller)) {
            reject(p, ReasonCode.NOT_CONTROLLER, "media", "", true);
            return;
        }
        ReasonCode right = RightsPolicy.denial(t.state, t.id, t.admin, Rights.CLICK_TYPE);
        if (right != ReasonCode.OK) {
            reject(p, right, "media", "", true);
            return;
        }
        ScreenSession session = ServerRuntime.session(t.state);
        // One server clock (ServerClock): the sample's clientServerTimeMs is the client's estimate of this clock,
        // synced with C2SClockPing/S2CClockPong, and S2CMediaClock is stamped with it too.
        long now = ServerClock.nowMs();
        switch (session.clock.apply(m.sample, t.state.epoch, now)) {
            case APPLIED:
                if (!m.sample.paused) session.lastPlayingMs = now;
                break;
            case HELD_FOR_AD:
                session.lastPlayingMs = now;
                break;
            case STALE_EPOCH:
                reject(p, ReasonCode.STALE_EPOCH, "media", "", true);
                break;
            case INVALID:
                reject(p, ReasonCode.BAD_PACKET, "media", "values", true);
                break;
            default:
                break; // IGNORED_OLD: a reordered duplicate
        }
    }

    // ---- control ----

    /**
     * C2SControl and {@code /web take|release} (D-016). Take is checked for rights (CLICK_TYPE, lock, owner or admin
     * to pre-empt someone), then the player must be near, in front of the screen, looking at it and in sight of it
     * ({@link ViewCheck}). The current controller repeats take as its keepalive while it interacts: an accepted one
     * refreshes the lock, a refused one is recorded quietly and the lock ages until {@link ControlLock} lets it go.
     * Release only needs the lock. Every change goes to every tracker in the tile's description packet.
     */
    public static void control(EntityPlayerMP p, C2SControl m) {
        Target t = resolve(p, m.key, "control", false);
        if (t == null) return;
        if (!m.take) {
            if (t.id.equals(t.state.controller)) setController(t, null, "release");
            return;
        }
        boolean keepalive = t.id.equals(t.state.controller);
        ReasonCode r = ActionRules
            .takeControl(t.state, t.id, t.admin, t.eye, ScreenWorld.look(p), t.cfg, ScreenWorld.sight(t.world));
        if (r != ReasonCode.OK) {
            reject(p, r, "control", "", keepalive);
            return;
        }
        if (keepalive) ServerRuntime.session(t.state).lastKeepaliveMs = ServerClock.nowMs();
        else setController(t, t.id, "take");
    }

    private static void setController(Target t, UUID controller, String why) {
        UUID previous = t.state.controller;
        t.state.controller = controller;
        t.state.controllerName = controller == null ? "" : t.player.getCommandSenderName();
        lockChanged(t.state);
        if (previous != null && controller != null) {
            AuditLog
                .log(t.player.getCommandSenderName(), t.id, "control pre-empted", t.state.key + " from " + previous);
        }
        ScreenSync.sendDescription(t.tile);
        com.akashiic.web.AkashicWeb.LOG.debug("AkashicWeb: {} controller {} ({})", t.state.key, controller, why);
    }

    /** A new holder (or none) starts with a fresh keepalive and none of the previous holder's media activity. */
    private static void lockChanged(ScreenState s) {
        ScreenSession session = ServerRuntime.session(s);
        session.lastKeepaliveMs = ServerClock.nowMs();
        session.lastPlayingMs = 0;
    }

    /** Housekeeping release ({@link ControlLock}: offline, other dimension, far, rights, idle; logout). */
    static void releaseControl(ScreenState s, String why) {
        s.controller = null;
        s.controllerName = "";
        lockChanged(s);
        TileEntityScreen te = ServerRuntime.LOADED.tile(s.key);
        if (te != null) ScreenSync.sendDescription(te);
        com.akashiic.web.AkashicWeb.LOG.debug("AkashicWeb: {} controller released ({})", s.key, why);
    }

    /** Admin takeover from /webadmin. */
    public static void takeover(EntityPlayerMP admin, ScreenState s) {
        TileEntityScreen te = ServerRuntime.LOADED.tile(s.key);
        UUID previous = s.controller;
        s.controller = admin.getUniqueID();
        s.controllerName = admin.getCommandSenderName();
        lockChanged(s);
        if (te != null) ScreenSync.sendDescription(te);
        AuditLog.log(admin.getCommandSenderName(), admin.getUniqueID(), "admin takeover", s.key + " from " + previous);
    }

    /** Releases every lock a player holds (logout, dimension change). */
    public static void releaseAll(UUID player, String why) {
        for (ScreenState s : ServerRuntime.LOADED.faces()) {
            if (player.equals(s.controller)) releaseControl(s, why);
        }
    }

    /**
     * Whether the current controller still passes the rights part of taking control (CLICK_TYPE, lock, mode). False
     * when there is no controller or it is offline; used after rights changes and by the once-a-second sweep.
     */
    static boolean controllerMayStay(ScreenState s, WebSettings cfg) {
        if (s.controller == null) return false;
        EntityPlayerMP holder = ScreenWorld.online(s.controller);
        if (holder == null) return false;
        return RightsPolicy.canTakeControl(s, s.controller, Admins.isAdmin(holder, cfg)) == ReasonCode.OK;
    }

    // ---- rights ----

    public static void rights(EntityPlayerMP p, C2SRights m) {
        Target t = resolve(p, m.key, "rights", false);
        if (t == null) return;
        if (!near(t, t.cfg.interactRange, "rights")) return;
        RightsEditor.Outcome o = RightsEditor.apply(t.state, m.change, t.id, t.admin, t.cfg, ScreenActions::profile);
        if (o.reason != ReasonCode.OK) {
            reject(p, o.reason, "rights", o.detail, false);
            ScreenSync.resync(p, t.state);
            return;
        }
        if (!o.changed) return;
        t.tile.markDirty();
        if (ServerRuntime.index()
            .put(ScreenIndex.Entry.of(t.state))) ServerRuntime.indexChanged();
        AuditLog.log(p.getCommandSenderName(), t.id, "rights", t.state.key + " " + o.audit);
        // Removing a friend, lowering rights or locking must not leave that player in control.
        if (t.state.controller != null && !controllerMayStay(t.state, t.cfg)) {
            releaseControl(t.state, "rights");
            return; // releaseControl already sent the new state
        }
        ScreenSync.sendDescription(t.tile);
    }

    /**
     * A friend name to a player: online players first, then {@link KnownPlayers} (everyone who logged in here). Map
     * reads only: never PlayerProfileCache.func_152655_a, which asks Mojang on a miss on the calling thread (review
     * finding: up to tens of seconds of main-thread freeze per packet) and rewrites usercache.json on every call.
     */
    static RightsEditor.Profile profile(String name) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || name == null || name.isEmpty()) return null;
        EntityPlayerMP online = server.getConfigurationManager()
            .func_152612_a(name);
        if (online != null) return new RightsEditor.Profile(online.getUniqueID(), online.getCommandSenderName());
        KnownPlayers.Entry known = ServerRuntime.knownPlayers()
            .lookup(name);
        return known == null ? null : new RightsEditor.Profile(known.id, known.name);
    }

    // ---- admin helpers (commands) ----

    /** /webadmin clear: blank page, new epoch, clock and history reset. */
    public static void clear(ScreenState s, String actor, UUID actorId) {
        TileEntityScreen te = ServerRuntime.LOADED.tile(s.key);
        ScreenSession session = ServerRuntime.session(s);
        NavLogic.clear(s, session.history);
        session.clock.reset(s.epoch);
        if (te != null) te.markDirty();
        if (ServerRuntime.index()
            .put(ScreenIndex.Entry.of(s))) ServerRuntime.indexChanged();
        AuditLog.log(actor, actorId, "admin clear", s.key + " epoch=" + s.epoch);
        ScreenSync.sendUrl(s);
    }

    /** /webadmin lock|unlock. */
    public static void setLocked(ScreenState s, boolean locked, String actor, UUID actorId) {
        if (s.locked == locked) return;
        s.locked = locked;
        if (locked && s.controller != null) {
            s.controller = null;
            s.controllerName = "";
            lockChanged(s);
        }
        TileEntityScreen te = ServerRuntime.LOADED.tile(s.key);
        if (te != null) {
            te.markDirty();
            ScreenSync.sendDescription(te);
        }
        AuditLog.log(actor, actorId, locked ? "admin lock" : "admin unlock", s.key.toString());
    }

    /** The screen went away (dissolved or merged): forget its clock and history. */
    static void forgetScreen(ScreenKey key) {
        ServerRuntime.endSession(key);
    }

    private static boolean near(Target t, double range, String action) {
        ReasonCode r = ViewCheck.near(t.state, t.eye[0], t.eye[1], t.eye[2], range);
        if (r == ReasonCode.OK) return true;
        reject(t.player, r, action, "", false);
        return false;
    }
}
