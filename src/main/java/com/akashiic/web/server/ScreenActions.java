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
import com.akashiic.web.common.net.C2SKey;
import com.akashiic.web.common.net.C2SMediaSample;
import com.akashiic.web.common.net.C2SNav;
import com.akashiic.web.common.net.C2SPointer;
import com.akashiic.web.common.net.C2SRights;
import com.akashiic.web.common.net.C2SSetUrl;
import com.akashiic.web.common.net.S2CInput;
import com.akashiic.web.common.net.S2CUrl;
import com.akashiic.web.common.net.ScreenInput;
import com.mojang.authlib.GameProfile;

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

    /** CHANGE_URL, or the controller's own navigation (clicking a link is navigating) with CLICK_TYPE. */
    static ReasonCode urlRight(Target t) {
        ReasonCode r = RightsPolicy.denial(t.state, t.id, t.admin, Rights.CHANGE_URL);
        if (r == ReasonCode.OK) return r;
        if (t.id.equals(t.state.controller) && RightsPolicy.can(t.state, t.id, t.admin, Rights.CLICK_TYPE)) {
            return ReasonCode.OK;
        }
        return r;
    }

    // ---- URL and navigation ----

    public static void setUrl(EntityPlayerMP p, C2SSetUrl m) {
        Target t = resolve(p, m.key, "url", false);
        if (t == null) return;
        ScreenState s = t.state;
        if (m.epoch != s.epoch) {
            // Someone changed the page meanwhile: drop it and show the sender the current page.
            reject(p, ReasonCode.STALE_EPOCH, "url", "", true);
            ScreenSync.sendTo(p, new S2CUrl(s.key, s.url, s.epoch));
            return;
        }
        if (!near(t, t.cfg.interactRange, "url")) return;
        ReasonCode right = urlRight(t);
        if (right != ReasonCode.OK) {
            reject(p, right, "url", "", false);
            return;
        }
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
        Target t = resolve(p, m.key, "nav", false);
        if (t == null) return;
        ScreenState s = t.state;
        if (m.epoch != s.epoch) {
            reject(p, ReasonCode.STALE_EPOCH, "nav", m.action, true);
            ScreenSync.sendTo(p, new S2CUrl(s.key, s.url, s.epoch));
            return;
        }
        if (!near(t, t.cfg.interactRange, "nav")) return;
        if ("stop".equals(m.action)) return; // only the sender's browser stops; nothing to share
        ReasonCode right = urlRight(t);
        if (right != ReasonCode.OK) {
            reject(p, right, "nav", m.action, false);
            return;
        }
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

    // ---- input ----

    public static void pointer(EntityPlayerMP p, C2SPointer m) {
        Target t = resolve(p, m.key, "pointer", true);
        if (t == null || !inputAllowed(t, "pointer")) return;
        double[] look = ScreenWorld.look(p);
        ReasonCode r = PointerCheck.check(
            t.state,
            t.eye[0],
            t.eye[1],
            t.eye[2],
            look,
            m.input.u,
            m.input.v,
            t.cfg.pointerReach,
            t.cfg.pointerMaxAngleDeg);
        if (r != ReasonCode.OK) {
            reject(p, r, "pointer", "", m.input.kind == ScreenInput.Kind.MOVE);
            return;
        }
        echo(t, m.input);
    }

    public static void key(EntityPlayerMP p, C2SKey m) {
        Target t = resolve(p, m.key, "key", true);
        if (t == null || !inputAllowed(t, "key")) return;
        ReasonCode r = PointerCheck.near(t.state, t.eye[0], t.eye[1], t.eye[2], t.cfg.pointerReach, true);
        if (r != ReasonCode.OK) {
            reject(p, r, "key", "", false);
            return;
        }
        echo(t, m.input);
    }

    /**
     * Input rights plus the controller lock. A player with CLICK_TYPE on a screen without controller becomes the
     * controller implicitly on their first input.
     */
    private static boolean inputAllowed(Target t, String action) {
        ReasonCode r = RightsPolicy.canInput(t.state, t.id, t.admin);
        if (r == ReasonCode.NOT_CONTROLLER && t.state.controller == null
            && RightsPolicy.canTakeControl(t.state, t.id, t.admin) == ReasonCode.OK) {
            setController(t, t.id, "implicit");
            r = ReasonCode.OK;
        }
        if (r != ReasonCode.OK) {
            reject(t.player, r, action, "", r == ReasonCode.NOT_CONTROLLER);
            return false;
        }
        return true;
    }

    /** ADR-012 amendment: accepted input goes back to the sender's own client only, never to followers. */
    private static void echo(Target t, ScreenInput input) {
        ServerRuntime.session(t.state).lastInputMs = ServerClock.nowMs();
        ScreenSync.sendTo(t.player, new S2CInput(t.state.key, t.state.epoch, input));
    }

    // ---- media clock ----

    public static void media(EntityPlayerMP p, C2SMediaSample m) {
        Target t = resolve(p, m.key, "media", true);
        if (t == null) return;
        if (t.state.mode == ScreenMode.PRIVATE || !t.id.equals(t.state.controller)) {
            reject(p, ReasonCode.NOT_CONTROLLER, "media", "", true);
            return;
        }
        MediaClock.Result r = ServerRuntime.session(t.state).clock.apply(m.sample, t.state.epoch, ServerClock.nowMs());
        if (r == MediaClock.Result.STALE_EPOCH) reject(p, ReasonCode.STALE_EPOCH, "media", "", true);
        else if (r == MediaClock.Result.INVALID) reject(p, ReasonCode.BAD_PACKET, "media", "values", true);
    }

    // ---- control ----

    public static void control(EntityPlayerMP p, C2SControl m) {
        Target t = resolve(p, m.key, "control", false);
        if (t == null) return;
        if (!near(t, t.cfg.interactRange, "control")) return;
        if (m.take) {
            ReasonCode r = RightsPolicy.canTakeControl(t.state, t.id, t.admin);
            if (r != ReasonCode.OK) {
                reject(p, r, "control", "", false);
                return;
            }
            if (!t.id.equals(t.state.controller)) setController(t, t.id, "take");
            ServerRuntime.session(t.state).lastInputMs = ServerClock.nowMs();
        } else if (t.id.equals(t.state.controller)) {
            setController(t, null, "release");
        }
    }

    private static void setController(Target t, UUID controller, String why) {
        UUID previous = t.state.controller;
        t.state.controller = controller;
        ServerRuntime.session(t.state).lastInputMs = ServerClock.nowMs();
        if (previous != null && controller != null) {
            AuditLog
                .log(t.player.getCommandSenderName(), t.id, "control pre-empted", t.state.key + " from " + previous);
        }
        ScreenSync.sendDescription(t.tile);
        com.akashiic.web.AkashicWeb.LOG.debug("AkashicWeb: {} controller {} ({})", t.state.key, controller, why);
    }

    /** Housekeeping release (offline, idle, far, other dimension). */
    static void releaseControl(ScreenState s, String why) {
        s.controller = null;
        TileEntityScreen te = ServerRuntime.LOADED.tile(s.key);
        if (te != null) ScreenSync.sendDescription(te);
        com.akashiic.web.AkashicWeb.LOG.debug("AkashicWeb: {} controller released ({})", s.key, why);
    }

    /** Admin takeover from /webadmin. */
    public static void takeover(EntityPlayerMP admin, ScreenState s) {
        TileEntityScreen te = ServerRuntime.LOADED.tile(s.key);
        UUID previous = s.controller;
        s.controller = admin.getUniqueID();
        ServerRuntime.session(s).lastInputMs = ServerClock.nowMs();
        if (te != null) ScreenSync.sendDescription(te);
        AuditLog.log(admin.getCommandSenderName(), admin.getUniqueID(), "admin takeover", s.key + " from " + previous);
    }

    /** Releases every lock a player holds (logout). */
    public static void releaseAll(UUID player) {
        for (ScreenState s : ServerRuntime.LOADED.faces()) {
            if (player.equals(s.controller)) releaseControl(s, "logout");
        }
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
        ScreenSync.sendDescription(t.tile);
    }

    /** Online player first, then the server's profile cache (players who joined before). */
    static RightsEditor.Profile profile(String name) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || name == null || name.isEmpty()) return null;
        EntityPlayerMP online = server.getConfigurationManager()
            .func_152612_a(name);
        if (online != null) return new RightsEditor.Profile(online.getUniqueID(), online.getCommandSenderName());
        GameProfile cached = server.func_152358_ax()
            .func_152655_a(name);
        return cached == null || cached.getId() == null ? null
            : new RightsEditor.Profile(cached.getId(), cached.getName());
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
        if (locked && s.controller != null) s.controller = null;
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
        ReasonCode r = PointerCheck.near(t.state, t.eye[0], t.eye[1], t.eye[2], range, false);
        if (r == ReasonCode.OK) return true;
        reject(t.player, r, action, "", false);
        return false;
    }
}
