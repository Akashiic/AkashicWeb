package com.akashiic.web.client.screen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.akashiic.web.AkashicWeb;
import com.akashiic.web.client.host.BrowserHost;
import com.akashiic.web.client.host.CreateSpec;
import com.akashiic.web.client.host.FrameCanvas;
import com.akashiic.web.client.host.FrameRing;
import com.akashiic.web.client.host.HostStatus;
import com.akashiic.web.client.host.InputEvent;
import com.akashiic.web.client.host.SyncCommand;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.client.sync.LocalMedia;
import com.akashiic.web.client.sync.MediaClock;
import com.akashiic.web.client.sync.ServerClock;
import com.akashiic.web.client.sync.SyncDecision;
import com.akashiic.web.common.HitMath;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.Rights;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenMode;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.common.UrlGate;
import com.akashiic.web.common.UrlPolicy;
import com.akashiic.web.common.net.MediaSample;
import com.akashiic.web.common.net.ScreenInput;
import com.akashiic.web.common.net.ServerBound;

/**
 * The screens this client tracks and their browsers: the one place that decides when a browser exists, what it shows,
 * how loud it is and how it follows the server clock. Client thread only (packet and host callbacks hop here first).
 * <ul>
 * <li>No browser before S2CHello, with an incompatible protocol, under the kill switch, or while the engine is not
 * READY (ADR-011/012).</li>
 * <li>Browsers go to the best size x proximity screens within range, at most 2 or 4 per client (ADR-012 item 7).</li>
 * <li>Each browser gets a LOD (ADR-004), an OS-mode volume with hard mute (ADR-007) and, on followers, playbackRate
 * steering from the server clock (ADR-012); the controller reports its media samples and its own navigations.</li>
 * <li>World unload and disconnect tear everything down; the hard mute always goes out before the destroy.</li>
 * </ul>
 * Minecraft-free (the adapter passes a {@link PlayerView}), so the whole lifecycle is unit-tested with a fake host.
 */
public final class ClientScreens {

    private static final Logger LOG = LogManager.getLogger(AkashicWeb.NAME);

    /** Chat output, injected so this class stays Minecraft-free. */
    public interface Notifier {

        void notice(String langKey, Object... args);

        Notifier NONE = (key, args) -> {};
    }

    /** A crosshair hit on a screen picture: the screen and the page (u, v). */
    public static final class Hit {

        public final ClientScreen screen;
        public final double u;
        public final double v;
        /** Blocks from the eye to the hit along the ray; NaN for a hit from vanilla's crosshair. */
        public final double distance;

        Hit(ClientScreen screen, double u, double v) {
            this(screen, u, v, Double.NaN);
        }

        Hit(ClientScreen screen, double u, double v, double distance) {
            this.screen = screen;
            this.u = u;
            this.v = v;
            this.distance = distance;
        }
    }

    /** Pages larger than 4K are refused here; the texture is also checked against GL_MAX_TEXTURE_SIZE. */
    static final int MAX_PAGE_W = 3840;
    static final int MAX_PAGE_H = 2160;
    static final int MIN_PAGE = 16;
    /** Follower steering runs at 4 Hz, the rate of the controller's samples. */
    static final long SYNC_INTERVAL_MS = 250;
    static final long HOUSEKEEPING_INTERVAL_MS = 1_000;
    static final long PRUNE_GRACE_MS = 3_000;
    /** The controller repeats C2SControl take at most this often while it interacts (D-016 keepalive). */
    static final long KEEPALIVE_MS = 5_000;
    /** A take on a free screen is re-sent at most this often while unanswered (the server allows 1/s). */
    static final long TAKE_RETRY_MS = 1_000;
    /** After asking for a free screen we drive it optimistically for this long, waiting for the server's answer. */
    static final long TAKE_PENDING_MS = 3_000;
    /**
     * How far the in-world pointer reaches, in blocks: the server's default interactRange, within which it accepts
     * C2SControl take (D-016). Far beyond vanilla's 4.5-block reach, so the top of a tall screen is clickable.
     */
    public static final double POINTER_REACH = 24;
    /** The controller hands the lock back beyond this distance (the server releases at interactRange + 4 anyway). */
    static final double RELEASE_DISTANCE = POINTER_REACH + 2;
    static final long RELEASE_RETRY_MS = 2_000;
    /** Closing the screen keyboard releases the lock after this long without further use of the screen. */
    static final long LEAVE_GRACE_MS = 5_000;
    /** At most one chat notice per screen and reason this often. */
    static final long NOTICE_INTERVAL_MS = 10_000;
    /** A crashed page is recreated after 5 s, doubling per crash of the same page up to a minute. */
    static final long RECREATE_BASE_MS = 5_000;
    static final long RECREATE_MAX_MS = 60_000;
    /** Host-level EVENTs kept for /awdoctor. */
    static final int MAX_HOST_EVENTS = 5;

    private final Map<ScreenKey, ClientScreen> screens = new LinkedHashMap<>();
    private final Map<Integer, ClientScreen> byHostId = new HashMap<>();
    private final ServerSession session = new ServerSession();
    private final ServerClock clock;
    private final HostAccess access;
    private final Supplier<ServerBound> server;
    private final LongSupplier timeMs;
    private final Notifier notifier;
    private final HostBridge bridge;
    private final ClickCounter clicks = new ClickCounter();
    private final NoticeThrottle noticeThrottle = new NoticeThrottle(NOTICE_INTERVAL_MS);
    private final java.util.ArrayDeque<String> hostEvents = new java.util.ArrayDeque<>();
    private final int maxBrowsers;

    private BrowserHost attachedHost;
    /** A world was unloaded since the last hello: the next hello may come from another server behind the proxy. */
    private boolean worldChangedSinceHello;
    private HostStatus lastStatus;
    private PlayerView view;
    private boolean ticked;
    private long lastTickMs;
    private long lastHousekeepingMs = ClientScreen.NEVER;

    /**
     * @param clientThread how host callbacks reach the client thread ({@code ClientTaskQueue::add} in the game)
     */
    public ClientScreens(HostAccess access, Supplier<ServerBound> server, LongSupplier timeMs, Executor clientThread,
        Notifier notifier, int maxBrowsers, long nonceSeed) {
        this.access = access;
        this.server = server;
        this.timeMs = timeMs;
        this.notifier = notifier == null ? Notifier.NONE : notifier;
        this.maxBrowsers = maxBrowsers;
        this.clock = new ServerClock(nonceSeed);
        this.bridge = new HostBridge(this, clock, timeMs, clientThread);
    }

    // ------------------------------------------------------------------ server packets (client thread)

    public void onHello(int protoMajor, int protoMinor, boolean killSwitch, SitePolicy policy) {
        onHello(protoMajor, protoMinor, killSwitch, policy, null);
    }

    /** @param playerUuid the local player's UUID as the server knows it (D-016), or null from an older server */
    public void onHello(int protoMajor, int protoMinor, boolean killSwitch, SitePolicy policy, UUID playerUuid) {
        // A hello after a world unload is a new server (a proxy transfer has no disconnect event): the old server's
        // clock samples would otherwise steer followers for up to ~80 s until new pongs outvote them. A hello without
        // one (policy reload, kill switch) keeps the clock.
        if (worldChangedSinceHello) clock.reset();
        worldChangedSinceHello = false;
        session.onHello(protoMajor, protoMinor, killSwitch, policy, playerUuid);
        LOG.info("AkashicWeb: server hello: {}", session);
        if (!session.allowsBrowsers()) destroyAllBrowsers();
        BrowserHost host = access.started();
        if (host != null && policy != null) host.policy(policy);
    }

    /** Full record from S2CScreenState or the tile description packet. */
    public void onScreenState(ScreenState state) {
        if (state == null || state.key == null) return;
        ClientScreen s = screens.get(state.key);
        if (s == null) {
            s = new ClientScreen(state);
            s.navigatedEpoch = state.epoch;
            screens.put(state.key, s);
            return; // the next tick decides whether it gets a browser
        }
        ScreenState old = s.state();
        if (isOlder(state.epoch, old.epoch)) {
            // A stale record (e.g. a tile snapshot) must not roll the URL back past a newer S2CUrl.
            ScreenState merged = ClientScreen.copyOf(state);
            merged.url = old.url;
            merged.epoch = old.epoch;
            state = merged;
        }
        s.setState(state);
        if (state.epoch != old.epoch) newPage(s);
        BrowserHost host = access.started();
        if (!hasPage(state.url)) {
            noPage(s, host);
            return;
        }
        if (s.hostScreenId == 0 || host == null) {
            s.navigatedEpoch = state.epoch;
            return;
        }
        if (sessionFor(state) != sessionFor(old)) {
            destroyBrowser(s, host); // a different session kind needs a new browser; recreated next tick
            return;
        }
        int[] size = pageSize(state);
        if (size[0] != s.requestedW || size[1] != s.requestedH) {
            host.resize(s.hostScreenId, size[0], size[1]);
            s.requestedW = size[0];
            s.requestedH = size[1];
        }
        if (state.epoch != s.navigatedEpoch) followServerUrl(s, host);
    }

    /**
     * The renderer saw a screen on a tile we do not track (its description packet hook did not reach us): adopt it.
     * Known
     * screens are left alone; their updates come through {@link #onScreenState}.
     */
    public void observe(ScreenState state) {
        if (state != null && state.key != null && !screens.containsKey(state.key)) onScreenState(state);
    }

    public void onScreenRemoved(ScreenKey key) {
        ClientScreen s = screens.remove(key);
        if (s != null) dispose(s);
    }

    /**
     * The server's URL and epoch (S2CUrl). An empty or non-web URL means "no page" (a fresh screen, or
     * {@code /webadmin clear}): the browser is muted and destroyed and the picture dropped, so a cleared page stops
     * playing on every client at once.
     */
    public void onUrl(ScreenKey key, String url, int epoch) {
        ClientScreen s = screens.get(key);
        if (s == null || url == null || isOlder(epoch, s.state().epoch)) return;
        boolean changed = epoch != s.state().epoch || !url.equals(s.state().url);
        s.state().url = url;
        s.state().epoch = epoch;
        if (!changed) return;
        newPage(s);
        BrowserHost host = access.started();
        if (!hasPage(url)) noPage(s, host);
        else if (s.hostScreenId != 0 && host != null) followServerUrl(s, host);
        else s.navigatedEpoch = epoch;
    }

    /**
     * Brings our browser to the server's URL for a new epoch. S2CUrl goes to every tracker, the controller included,
     * so the controller's own report comes back to it: navigating then would hard-reload the page it is already on
     * (restarting the video every follower steers to), or pull it back from where it has moved on since. That echo
     * is recognised as the URL we reported last and only adopted. A reload, back, forward, home or typed URL clears
     * the remembered report first, so the same URL with a new epoch still navigates.
     */
    private void followServerUrl(ClientScreen s, BrowserHost host) {
        String reported = s.lastReportedUrl;
        s.lastReportedUrl = null;
        if (reported != null && UrlConvergence.sameDocument(s.state().url, reported)) {
            s.navigatedEpoch = s.state().epoch;
            LOG.debug("AkashicWeb: {} epoch {} is our own report; not reloading", s.key, s.state().epoch);
            return;
        }
        navigate(s, host);
    }

    /** True for a URL a browser should load (http or https); "" and anything else mean "no page". */
    static boolean hasPage(String url) {
        return url != null && UrlConvergence.isWeb(url);
    }

    /** Mute, destroy and drop the picture: the screen shows the "no address" card. */
    private void noPage(ClientScreen s, BrowserHost host) {
        destroyBrowser(s, host);
        dropPicture(s);
        s.clearFailure();
        s.navigatedEpoch = s.state().epoch;
    }

    /** Client (GL) thread: packet work runs from ClientTaskQueue, drained at the tick and render-tick starts. */
    private static void dropPicture(ClientScreen s) {
        if (s.texture != null) s.texture.delete();
        s.texture = null;
        s.hasFrame = false;
        s.carriedDamage = com.akashiic.web.client.render.Rect.EMPTY;
        s.skippedFrames = 0;
    }

    public void onMediaClock(ScreenKey key, int epoch, double mediaTime, float rate, boolean paused,
        long serverTimeMs) {
        onMediaClock(key, epoch, mediaTime, rate, paused, serverTimeMs, false, 0);
    }

    /**
     * S2CMediaClock. {@code live} and {@code liveLagMs} (the controller's lag behind its own live edge) feed the
     * followers' live-edge rule (ADR-012 item 5).
     */
    public void onMediaClock(ScreenKey key, int epoch, double mediaTime, float rate, boolean paused, long serverTimeMs,
        boolean live, int liveLagMs) {
        ClientScreen s = screens.get(key);
        if (s == null || epoch != s.state().epoch || Double.isNaN(mediaTime) || Double.isInfinite(mediaTime)) return;
        s.mediaClock = new MediaClock(epoch, mediaTime, rate, paused, serverTimeMs, live, liveLagMs);
    }

    /** Thread-safe; the Netty thread feeds pongs straight in. */
    public ServerClock clock() {
        return clock;
    }

    public ServerSession session() {
        return session;
    }

    // ------------------------------------------------------------------ host callbacks (client thread, via bridge)

    void onHostStatus(HostStatus status) {
        handleStatus(status);
    }

    void onHostState(int hostId, PageState page) {
        ClientScreen s = byHostId.get(hostId);
        if (s == null) return;
        s.page = page;
        reportControllerNavigation(s, timeMs.getAsLong());
    }

    void onHostMedia(int hostId, LocalMedia media) {
        ClientScreen s = byHostId.get(hostId);
        if (s == null) return;
        s.localMedia = media;
        if (!isController(s)) return;
        MediaSample sample = s.sampler.next(s.state().epoch, media, timeMs.getAsLong());
        if (sample != null) server.get()
            .mediaSample(s.key, sample);
    }

    void onHostEvent(int hostId, String kind) {
        onHostEvent(hostId, new HostEvent(kind, "", "", "", "", "", false));
    }

    /**
     * A host EVENT (ClientReason has the mapping). Screen 0 is the host itself (e.g. policy-error: the host fell back
     * to deny-all): logged and kept for /awdoctor. Notices go only to the player driving the page, at most one per
     * screen and reason every {@link #NOTICE_INTERVAL_MS}: the host rate-limits page-triggered EVENTs to 5/s, still far
     * too many for chat.
     */
    void onHostEvent(int hostId, HostEvent e) {
        long now = timeMs.getAsLong();
        if (hostId == 0) {
            onHostLevelEvent(e, now);
            return;
        }
        ClientScreen s = byHostId.get(hostId);
        if (s == null) return;
        ClientReason.Outcome o = ClientReason.map(e);
        if (o.failure != null) s.fail(o.failure, o.failureDetail);
        if (o.clearsUnresponsive && s.failure == ReasonCode.PAGE_UNRESPONSIVE) s.clearFailure();
        if (o.failure == ReasonCode.PAGE_CRASHED) scheduleRecreate(s, now);
        if (o.notice != null && drivesPage(s) && noticeThrottle.allow(s.key + "|" + o.notice, now))
            notifier.notice(o.notice.langKey(), o.noticeArg);
        LOG.debug("AkashicWeb: {} host event {} -> {}", s.key, e, o);
    }

    private void onHostLevelEvent(HostEvent e, long now) {
        if (e.suppressedSummary) return;
        String line = String.format(java.util.Locale.ROOT, "t=%ds %s", now / 1000, e);
        if (hostEvents.size() == MAX_HOST_EVENTS) hostEvents.removeFirst();
        hostEvents.addLast(line);
        if ("policy-error".equals(e.kind)) {
            LOG.warn("AkashicWeb: the browser host refused the site policy and blocks every page: {}", e);
        } else {
            LOG.info("AkashicWeb: browser host event {}", e);
        }
    }

    /** A crashed renderer does not come back by itself: recreate the browser after a growing delay. */
    private static void scheduleRecreate(ClientScreen s, long now) {
        s.crashes++;
        long delay = Math.min(RECREATE_MAX_MS, RECREATE_BASE_MS << Math.min(4, s.crashes - 1));
        s.recreateAtMs = now + delay;
    }

    /** The local player drives this screen's page: the controller, or a player with CLICK_TYPE on a PRIVATE one. */
    private boolean drivesPage(ClientScreen s) {
        return s.state().mode == ScreenMode.PRIVATE ? can(s, Rights.CLICK_TYPE) : isController(s);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Every client tick (20 Hz). A null view (no world/player) tears everything down. */
    public void tick(PlayerView v) {
        long now = timeMs.getAsLong();
        double dt = ticked ? Math.max(0, Math.min(0.5, (now - lastTickMs) / 1000.0)) : 0.05;
        ticked = true;
        lastTickMs = now;
        view = v;
        if (v == null) {
            if (!screens.isEmpty()) teardownAll();
            return;
        }
        if (session.helloReceived()) {
            long nonce = clock.pollPing(now);
            if (nonce != 0) server.get()
                .clockPing(nonce, now);
        }
        BrowserHost started = access.started();
        if (started != null) {
            attach(started);
            handleStatus(started.status());
        }
        boolean housekeeping = now - lastHousekeepingMs >= HOUSEKEEPING_INTERVAL_MS;
        if (housekeeping) lastHousekeepingMs = now;

        updateViewMetrics(v);
        Set<ScreenKey> granted = browsersAllowed() ? grant() : Collections.<ScreenKey>emptySet();
        for (ClientScreen s : new ArrayList<>(screens.values())) {
            updateControl(s, now);
            if (s.recreateAtMs != 0 && now >= s.recreateAtMs) {
                s.recreateAtMs = 0;
                if (s.failure == ReasonCode.PAGE_CRASHED) destroyBrowser(s, access.started()); // recreated below
            }
            s.granted = granted.contains(s.key);
            if (s.granted && s.hostScreenId == 0) create(s);
            else if (!s.granted && s.hostScreenId != 0) destroyBrowser(s, access.started());
            if (s.hostScreenId == 0) continue;
            BrowserHost host = access.started();
            if (host == null) continue;
            applyLod(s, host);
            applyAudio(s, host, v, dt, now);
            applySync(s, host, now);
            if (housekeeping) reportControllerNavigation(s, now);
        }
    }

    /**
     * Drops screens whose tile is gone (missed onScreenRemoved, chunk unloaded); called about once a second. A screen
     * must be missing for {@link #PRUNE_GRACE_MS} first: S2CScreenState can arrive before the chunk holding its tile.
     */
    public void prune(Predicate<ScreenKey> stillExists) {
        long now = timeMs.getAsLong();
        Iterator<ClientScreen> it = screens.values()
            .iterator();
        while (it.hasNext()) {
            ClientScreen s = it.next();
            if (stillExists.test(s.key)) {
                s.missing = false;
                continue;
            }
            if (!s.missing) {
                s.missing = true;
                s.missingSinceMs = now;
            }
            if (now - s.missingSinceMs >= PRUNE_GRACE_MS) {
                it.remove();
                dispose(s);
            }
        }
    }

    /** World unload (dimension change, proxy transfer): mute and destroy every browser, forget every screen. */
    public void teardownAll() {
        for (ClientScreen s : new ArrayList<>(screens.values())) dispose(s);
        screens.clear();
        byHostId.clear();
        worldChangedSinceHello = true;
    }

    /** Disconnect: as {@link #teardownAll()}, and the next server must send a new hello and clock. */
    public void onDisconnect() {
        teardownAll();
        session.reset();
        clock.reset();
    }

    // ------------------------------------------------------------------ queries for render, input and GUIs

    public Collection<ClientScreen> all() {
        return Collections.unmodifiableCollection(screens.values());
    }

    public ClientScreen get(ScreenKey key) {
        return screens.get(key);
    }

    /** The started host for the frame uploader, or null. */
    public BrowserHost host() {
        return access.started();
    }

    public FrameRing frames(ClientScreen s) {
        BrowserHost host = access.started();
        return host == null || s.hostScreenId == 0 ? null : host.frames(s.hostScreenId);
    }

    public Placeholder placeholder(ClientScreen s) {
        PlaceholderModel.Inputs in = new PlaceholderModel.Inputs();
        in.session = session;
        in.engine = access.engine();
        in.hostAvailable = access.available();
        BrowserHost host = access.started();
        in.hostStatus = host == null ? null : lastStatus;
        in.browserLive = s.hostScreenId != 0;
        in.granted = s.granted;
        in.hasFrame = s.hasFrame;
        in.painting = s.lastLodSent == null || s.lastLodSent.painting;
        in.failure = s.failure;
        in.failureDetail = s.failureDetail;
        in.hasUrl = s.state().url != null && !s.state().url.isEmpty();
        in.consentPostponed = access.consentPostponed();
        return PlaceholderModel.resolve(in);
    }

    /** The screen picture under a crosshair hit (coordinates absolute, side = Forge side hit), or null. */
    public Hit hit(int dimension, double x, double y, double z, int side) {
        for (ClientScreen s : screens.values()) {
            ScreenKey k = s.key;
            if (k.dim != dimension || k.side != side || s.geometry() == null) continue;
            double[] uv = s.geometry()
                .pageUv(x - k.x, y - k.y, z - k.z);
            if (uv != null) return new Hit(s, uv[0], uv[1]);
        }
        return null;
    }

    /**
     * The in-world pointer: the nearest screen picture the eye ray (eye + t * look, absolute coordinates) hits within
     * {@code reach} blocks in this dimension, from the front, or null. Unlike vanilla's crosshair (4.5 blocks) it
     * reaches the top of a tall screen; the caller checks that no block stands in between. The same math as the
     * server's checks (common.HitMath).
     */
    public Hit aim(int dimension, double ex, double ey, double ez, double dx, double dy, double dz, double reach) {
        Hit best = null;
        for (ClientScreen s : screens.values()) {
            if (s.key.dim != dimension || s.geometry() == null) continue;
            double[] r = HitMath.rayHit(s.state(), ex, ey, ez, dx, dy, dz, reach);
            if (r != null && (best == null || r[2] < best.distance)) best = new Hit(s, r[0], r[1], r[2]);
        }
        return best;
    }

    /**
     * The local player as the server knows it: the UUID from S2CHello (D-016; on offline-mode or proxied servers it
     * can differ from the session's), else the one Minecraft reports.
     */
    public UUID player() {
        UUID server = session.playerUuid();
        if (server != null) return server;
        return view == null ? null : view.player;
    }

    public boolean isController(ClientScreen s) {
        return LocalRights.isController(s.state(), player());
    }

    public boolean can(ClientScreen s, int right) {
        return LocalRights.can(s.state(), player(), right);
    }

    // ------------------------------------------------------------------ player actions (client thread)

    /**
     * A page click (press and release at once), e.g. from a test or a GUI button. In-world clicks use
     * {@link #pointerDown} and {@link #pointerUp} so drags work. False when the player may not click this screen.
     */
    public boolean click(ClientScreen s, double u, double v, int button, int modifiers) {
        if (!pointerDown(s, u, v, button, modifiers)) return false;
        pointerUp(s, u, v, button, modifiers);
        return true;
    }

    /**
     * Button pressed at page (u, v). On a free screen this asks for the lock and the press is applied at once
     * (optimistically, see {@link #drivesInput}); while someone else controls the screen nothing reaches our browser.
     * False when the player may not click this screen (the click then falls through to the game).
     */
    public boolean pointerDown(ClientScreen s, double u, double v, int button, int modifiers) {
        if (!can(s, Rights.CLICK_TYPE)) return false;
        long now = timeMs.getAsLong();
        claim(s, now);
        s.pressCount = clicks.click(s.key, u, v, now);
        apply(
            s,
            ScreenInput.pointer(ScreenInput.Kind.DOWN, (float) u, (float) v, button, s.pressCount, modifiers),
            now);
        return true;
    }

    /** Button released at page (u, v): ends a click or a drag (seek bars, sliders, text selection). */
    public void pointerUp(ClientScreen s, double u, double v, int button, int modifiers) {
        long now = timeMs.getAsLong();
        apply(
            s,
            ScreenInput
                .pointer(ScreenInput.Kind.UP, (float) u, (float) v, button, Math.max(1, s.pressCount), modifiers),
            now);
    }

    /**
     * The crosshair moved over the page (hover menus, video controls, drags). Sent only when it lands on another page
     * pixel, and only to a browser we drive; hovering never takes the lock and is not a keepalive.
     */
    public void pointerMove(ClientScreen s, double u, double v) {
        int[] px = InputTranslator.pixel((float) u, (float) v, s.requestedW, s.requestedH);
        if (px == null || (px[0] == s.lastPointerX && px[1] == s.lastPointerY)) return;
        long now = timeMs.getAsLong();
        if (apply(
            s,
            ScreenInput.pointer(ScreenInput.Kind.MOVE, (float) u, (float) v, ScreenInput.BUTTON_LEFT, 0, 0),
            now)) {
            s.lastPointerX = px[0];
            s.lastPointerY = px[1];
        }
    }

    public boolean wheel(ClientScreen s, double u, double v, float notches, int modifiers) {
        if (!can(s, Rights.CLICK_TYPE) || notches == 0) return false;
        long now = timeMs.getAsLong();
        claim(s, now);
        apply(s, ScreenInput.wheel((float) u, (float) v, 0, notches, modifiers), now);
        return true;
    }

    /** Keyboard GUI output, applied to this client's own browser like {@link #pointerDown}. */
    public boolean type(ClientScreen s, ScreenInput input) {
        if (input == null || !can(s, Rights.CLICK_TYPE)) return false;
        long now = timeMs.getAsLong();
        claim(s, now);
        apply(s, input, now);
        return true;
    }

    /**
     * D-016: whether the local player's input reaches its own browser now. On a PRIVATE screen every viewer drives
     * their own browser. On a SHARED screen only the controller does, as the server says it (ScreenState.controller
     * against S2CHello.playerUuid) - or, for {@link #TAKE_PENDING_MS} after we asked for the lock of a free screen,
     * us optimistically: the gesture that takes a screen is applied at once instead of being swallowed. If the server
     * gives the lock to someone else, or never answers, input stops and our page is brought back to the server's URL.
     */
    public boolean drivesInput(ClientScreen s) {
        return drivesInput(s, timeMs.getAsLong());
    }

    private boolean drivesInput(ClientScreen s, long now) {
        if (s.state().mode == ScreenMode.PRIVATE || isController(s)) return true;
        return s.state().controller == null && now < s.takePendingUntilMs;
    }

    /**
     * Asks for the lock of a free screen (re-sent at most every {@link #TAKE_RETRY_MS}, the server's rate), or keeps
     * ours alive: the controller repeats take at most every {@link #KEEPALIVE_MS} while it interacts, and the server
     * releases a lock that has seen none for controllerIdleSeconds. Never on PRIVATE screens (no lock there).
     */
    private void claim(ClientScreen s, long now) {
        if (s.state().mode == ScreenMode.PRIVATE) return;
        s.leaveAtMs = 0; // still using the screen
        if (s.state().controller == null) {
            if (now - s.lastTakeMs >= TAKE_RETRY_MS) {
                control(s, true);
                s.takePendingUntilMs = now + TAKE_PENDING_MS;
            }
        } else if (isController(s) && now - s.lastTakeMs >= KEEPALIVE_MS) {
            control(s, true);
        }
    }

    /** D-016: input goes straight to our own browser, never to the server; dropped when we do not drive it. */
    private boolean apply(ClientScreen s, ScreenInput input, long now) {
        if (!drivesInput(s, now)) return false;
        BrowserHost host = access.started();
        if (host == null || s.hostScreenId == 0) return false;
        InputEvent e = InputTranslator.toHost(input, s.requestedW, s.requestedH);
        if (e == null) return false;
        host.input(s.hostScreenId, e);
        if (s.state().mode != ScreenMode.PRIVATE && !isController(s)) s.optimistic = true;
        return true;
    }

    /**
     * The player is done with this screen: hand the lock back now so someone else can take it. Nothing to do on
     * PRIVATE screens or when we do not hold it.
     */
    public void leave(ClientScreen s) {
        if (s == null) return;
        s.takePendingUntilMs = 0;
        s.leaveAtMs = 0;
        if (s.state().mode != ScreenMode.PRIVATE && isController(s)) control(s, false);
    }

    /**
     * The player closed the screen keyboard: release the lock after {@link #LEAVE_GRACE_MS} unless they keep using the
     * screen in the world meanwhile (closing the keyboard to click a search result must not drop and re-take the lock).
     */
    public void leaveSoon(ClientScreen s) {
        if (s == null || s.state().mode == ScreenMode.PRIVATE) return;
        s.leaveAtMs = timeMs.getAsLong() + LEAVE_GRACE_MS;
    }

    public void setUrl(ClientScreen s, String url) {
        s.lastReportedUrl = null; // the echo of a typed URL must navigate, even to the page we reported last
        server.get()
            .setUrl(s.key, s.state().epoch, url);
    }

    /**
     * Address bar navigation. The server validates and audits every action, and turns back, forward, home and reload
     * into a URL change with a new epoch that reaches every viewer (S2CUrl), the controller included: applying back
     * or forward locally as well would navigate the controller twice. Only "stop" (which the server ignores) is
     * applied to our own browser, when we drive it.
     */
    public void nav(ClientScreen s, String action) {
        s.lastReportedUrl = null; // a reload of the page we reported last must still reload
        server.get()
            .nav(s.key, s.state().epoch, action);
        BrowserHost host = access.started();
        if ("stop".equals(action) && host != null && s.hostScreenId != 0 && drivesPage(s))
            host.action(s.hostScreenId, action);
    }

    /** C2SControl take (also the keepalive) or release. */
    public void control(ClientScreen s, boolean take) {
        long now = timeMs.getAsLong();
        if (take) {
            s.lastTakeMs = now;
        } else {
            s.takePendingUntilMs = 0;
            s.lastReleaseMs = now;
        }
        server.get()
            .control(s.key, take);
    }

    /**
     * Our optimistic input did not become control (the server gave the lock to someone else, refused, or did not
     * answer): stop treating the page as ours and, if our clicks moved it away from the server's URL, bring it back
     * like any follower.
     */
    private void endOptimism(ClientScreen s) {
        if (!s.optimistic) return;
        s.optimistic = false;
        s.takePendingUntilMs = 0;
        BrowserHost host = access.started();
        String page = s.page.url;
        if (host != null && s.hostScreenId != 0
            && hasPage(page)
            && hasPage(s.state().url)
            && !UrlConvergence.sameDocument(page, s.state().url)) {
            LOG.debug("AkashicWeb: {} lock not granted; back to the server's URL", s.key);
            navigate(s, host);
        }
    }

    /** Per tick: settle optimistic input and hand the lock back when the controller walked away. */
    private void updateControl(ClientScreen s, long now) {
        if (s.state().mode == ScreenMode.PRIVATE) return;
        if (isController(s)) {
            s.optimistic = false;
            s.takePendingUntilMs = 0;
            boolean away = !Double.isInfinite(s.distance) && s.distance > RELEASE_DISTANCE;
            boolean closed = s.leaveAtMs != 0 && now >= s.leaveAtMs;
            if ((away || closed) && now - s.lastReleaseMs >= RELEASE_RETRY_MS) {
                s.leaveAtMs = 0;
                control(s, false);
            }
        } else if (s.optimistic && (s.state().controller != null || now >= s.takePendingUntilMs)) {
            endOptimism(s);
        }
    }

    // ------------------------------------------------------------------ internals

    private boolean browsersAllowed() {
        EngineState e = access.engine();
        // No policy, no browser: M2 denies every URL until policy() is called, so a browser created first would
        // start blank with a URL_BLOCKED failure.
        if (!session.allowsBrowsers() || session.policy() == null
            || e == null
            || e.kind != EngineState.Kind.READY
            || !access.available()) return false;
        return lastStatus == null || lastStatus.kind == HostStatus.Kind.READY
            || lastStatus.kind == HostStatus.Kind.STARTING;
    }

    private void updateViewMetrics(PlayerView v) {
        double[] eye = { v.eyeX, v.eyeY, v.eyeZ };
        double[] look = { v.lookX, v.lookY, v.lookZ };
        for (ClientScreen s : screens.values()) {
            ScreenGeometry g = s.geometry();
            if (g == null || s.key.dim != v.dimension) {
                s.distance = Double.POSITIVE_INFINITY;
                s.originDistance = Double.POSITIVE_INFINITY;
                s.inView = false;
                s.priority = 0;
                continue;
            }
            double lx = v.eyeX - s.key.x, ly = v.eyeY - s.key.y, lz = v.eyeZ - s.key.z;
            s.distance = g.distanceTo(lx, ly, lz);
            double ox = lx - 0.5, oy = ly - 0.5, oz = lz - 0.5;
            s.originDistance = Math.sqrt(ox * ox + oy * oy + oz * oz);
            double[] c = g.center();
            double[] center = { c[0] + s.key.x, c[1] + s.key.y, c[2] + s.key.z };
            s.inView = ScreenLod.inView(eye, look, center, g.boundingRadius());
            s.priority = BrowserBudget.score(s.state().widthBlocks, s.state().heightBlocks, s.distance);
        }
    }

    private Set<ScreenKey> grant() {
        List<BrowserBudget.Candidate<ScreenKey>> candidates = new ArrayList<>();
        for (ClientScreen s : screens.values()) {
            // No page, no browser: a fresh or cleared screen must not spend a browser or a budget slot.
            if (s.geometry() == null || Double.isInfinite(s.distance) || !hasPage(s.state().url)) continue;
            // Never a browser for a screen the renderer does not draw (vanilla culls the tile beyond its render
            // distance, measured from the origin block): it would paint, upload and outbid visible screens.
            double drawn = ScreenLod.TESR_RANGE + (s.hostScreenId != 0 ? ScreenLod.TESR_KEEP_MARGIN : 0);
            if (s.originDistance > drawn) continue;
            int dim = s.maxDimBlocks();
            candidates.add(
                new BrowserBudget.Candidate<>(
                    s.key,
                    s.priority,
                    s.distance,
                    ScreenLod.createRange(dim),
                    ScreenLod.keepRange(dim),
                    s.hostScreenId != 0));
        }
        return new HashSet<>(BrowserBudget.select(candidates, maxBrowsers));
    }

    private void create(ClientScreen s) {
        if (session.policy() == null) return;
        BrowserHost host = access.obtain();
        if (host == null) return;
        attach(host); // pushes the site policy before the first CREATE (M2 is deny-all until then)
        int[] size = pageSize(s.state());
        ScreenLod lod = ScreenLod.of(s.distance, s.inView, s.maxDimBlocks());
        CreateSpec spec = new CreateSpec(
            size[0],
            size[1],
            lod.fps,
            s.state().url,
            sessionFor(s.state()),
            CreateSpec.Audio.OS,
            0f);
        int id;
        try {
            id = host.createScreen(spec);
        } catch (RuntimeException e) {
            // Documented boundary: a host bug must leave the screen on its placeholder, not break the tick.
            LOG.error("AkashicWeb: createScreen failed for {}", s.key, e);
            s.fail(ReasonCode.HOST_UNAVAILABLE, "");
            return;
        }
        if (id <= 0) return;
        s.hostScreenId = id;
        byHostId.put(id, s);
        s.requestedW = size[0];
        s.requestedH = size[1];
        s.navigatedEpoch = s.state().epoch;
        s.lastNavigateMs = timeMs.getAsLong();
        s.clearFailure();
        s.page = PageState.EMPTY;
        // Silent until the first audio tick decides; the page must never blare before distance is applied.
        host.audio(id, true, 0f);
        s.audio.invalidate();
        host.render(id, lod.fps, lod.painting);
        s.lastLodSent = lod;
        resetSync(s);
        LOG.debug("AkashicWeb: browser {} for {} ({}x{})", id, s.key, size[0], size[1]);
    }

    private void navigate(ClientScreen s, BrowserHost host) {
        host.navigate(s.hostScreenId, s.state().url);
        s.navigatedEpoch = s.state().epoch;
        s.lastNavigateMs = timeMs.getAsLong();
        s.clearFailure();
    }

    private void applyLod(ClientScreen s, BrowserHost host) {
        ScreenLod lod = ScreenLod.of(s.distance, s.inView, s.maxDimBlocks());
        if (lod.equals(s.lastLodSent)) return;
        host.render(s.hostScreenId, lod.fps, lod.painting);
        s.lastLodSent = lod;
    }

    private void applyAudio(ClientScreen s, BrowserHost host, PlayerView v, double dt, long now) {
        boolean hardMute = v.menuOpen || !session.allowsBrowsers() || s.key.dim != v.dimension;
        ScreenAudio.Command c = s.audio
            .update(s.distance, s.maxDimBlocks(), v.masterLevel, v.recordsLevel, hardMute, dt, now);
        if (c != null) host.audio(s.hostScreenId, c.muted, c.volume);
    }

    private void applySync(ClientScreen s, BrowserHost host, long now) {
        if (s.state().mode == ScreenMode.PRIVATE) return;
        boolean controller = isController(s);
        if (controller) {
            if (!s.wasController && s.lastSyncSent != null && s.lastSyncSent.rate != 1.0f) {
                // We just became the source of the clock: stop any steering left over from following.
                boolean paused = s.localMedia != null && s.localMedia.paused;
                double position = s.localMedia != null ? s.localMedia.mediaTimeAt(now) : 0;
                SyncCommand neutral = new SyncCommand(position, 1.0f, paused, false);
                host.sync(s.hostScreenId, neutral);
                s.lastSyncSent = neutral;
            }
            s.wasController = true;
            s.lastDecision = SyncDecision.WAITING;
            return;
        }
        if (s.wasController) {
            s.wasController = false;
            s.sync.reset();
        }
        if (now - s.lastSyncEvalMs < SYNC_INTERVAL_MS) return;
        s.lastSyncEvalMs = now;
        MediaClock mc = s.mediaClock != null && s.mediaClock.epoch == s.state().epoch ? s.mediaClock : null;
        double controllerLiveLagS = mc == null ? Double.NaN : mc.liveLagSeconds();
        SyncDecision d = s.sync.update(mc, clock.serverNowMs(now), s.localMedia, now, controllerLiveLagS);
        s.lastDecision = d;
        if (d.kind != SyncDecision.Kind.WAITING && d.kind != SyncDecision.Kind.AD_SUSPENDED) {
            s.syncStats.add(d.errorSeconds);
        }
        if (d.command != null && worthSending(d.command, s.lastSyncSent)) {
            host.sync(s.hostScreenId, d.command);
            s.lastSyncSent = d.command;
        }
    }

    static boolean worthSending(SyncCommand next, SyncCommand last) {
        if (next.seek || last == null) return true;
        return Math.abs(next.rate - last.rate) >= 0.001f || next.paused != last.paused;
    }

    private void reportControllerNavigation(ClientScreen s, long now) {
        if (s.hostScreenId == 0) return;
        PageState p = s.page;
        if (!UrlConvergence.shouldReport(
            s.state().url,
            p.url,
            isController(s),
            p.loading,
            now - s.lastNavigateMs,
            now,
            s.lastUrlReportMs,
            s.reportedUrl)) return;
        // One report per page and epoch: a report the server refuses (too long, blocked) leaves the epoch as it is,
        // and resending it every second only fills the audit log. A new page or a new epoch reports again.
        if (s.attemptedUrl != null && s.attemptedEpoch == s.state().epoch
            && UrlConvergence.sameDocument(s.attemptedUrl, p.url)) return;
        s.attemptedUrl = p.url;
        s.attemptedEpoch = s.state().epoch;
        // The server would refuse a URL its own site policy blocks (the host should have blocked it already).
        SitePolicy policy = session.policy();
        if (policy != null && !new UrlGate(REPORT_URLS, policy).check(p.url)
            .ok()) return;
        s.reportedUrl = true;
        s.lastUrlReportMs = now;
        s.lastReportedUrl = p.url;
        server.get()
            .setUrl(s.key, s.state().epoch, p.url);
    }

    /** The syntax and internal-address rules M2 checks URLs with before they reach the host (UrlPolicy(true, 2048)). */
    private static final UrlPolicy REPORT_URLS = new UrlPolicy(true, UrlPolicy.MAX_URL_LENGTH);

    private void handleStatus(HostStatus status) {
        HostStatus previous = lastStatus;
        lastStatus = status;
        if (status == null || (previous != null && previous.kind == status.kind)) return;
        BrowserHost host = access.started();
        if (status.kind == HostStatus.Kind.DOWN || status.kind == HostStatus.Kind.DISABLED) {
            // The process is gone: every browser died with it. Destroy (not just forget) the ids so M2's
            // BrowserHostImpl drops its records and does not replay them on the next connect; the tick recreates
            // fresh browsers when READY again (integration of wave 1: M2 replays every record it still holds).
            LOG.warn("AkashicWeb: browser host {}; {} browser(s) lost", status, byHostId.size());
            for (ClientScreen s : screens.values()) destroyBrowser(s, host);
        } else if (status.kind == HostStatus.Kind.READY && host != null && session.policy() != null) {
            host.policy(session.policy());
        }
    }

    private void attach(BrowserHost host) {
        if (attachedHost == host) return;
        attachedHost = host;
        host.addListener(bridge);
        if (session.policy() != null) host.policy(session.policy());
    }

    private void destroyAllBrowsers() {
        BrowserHost host = access.started();
        for (ClientScreen s : screens.values()) if (s.hostScreenId != 0) destroyBrowser(s, host);
    }

    /** Mute first (instant, engine level), then destroy: no lingering audio after a teardown (ADR-007 G3 check). */
    private void destroyBrowser(ClientScreen s, BrowserHost host) {
        int id = s.hostScreenId;
        if (id == 0) return;
        if (host != null) {
            // The held slot goes back while the ring still exists: M2 drops a destroyed id's ring.
            releaseHeldSlot(s, host);
            try {
                host.audio(id, true, 0f);
                host.destroyScreen(id);
            } catch (RuntimeException e) {
                // Documented boundary: teardown must finish for every other screen.
                LOG.error("AkashicWeb: destroyScreen failed for {}", s.key, e);
            }
        }
        forgetBrowser(s, host);
    }

    private static void releaseHeldSlot(ClientScreen s, BrowserHost host) {
        if (s.heldSlot == null || host == null) return;
        FrameRing ring = host.frames(s.hostScreenId);
        if (ring != null) ring.release(s.heldSlot);
        s.heldSlot = null;
    }

    private void forgetBrowser(ClientScreen s, BrowserHost host) {
        if (s.hostScreenId == 0) return;
        releaseHeldSlot(s, host);
        s.heldSlot = null;
        byHostId.remove(s.hostScreenId);
        s.hostScreenId = 0;
        s.lastLodSent = null;
        s.lastSyncSent = null;
        s.localMedia = null;
        s.page = PageState.EMPTY;
        s.lastPointerX = -1;
        s.lastPointerY = -1;
        s.optimistic = false;
        s.wrongSizeFrames = 0;
        s.audio.invalidate();
        resetSync(s);
    }

    private void dispose(ClientScreen s) {
        destroyBrowser(s, access.started());
        dropPicture(s);
    }

    /** A new epoch: the clock, the reports and the crash history belong to the old page. */
    private static void newPage(ClientScreen s) {
        resetSync(s);
        s.syncStats.reset();
        s.crashes = 0;
        s.recreateAtMs = 0;
    }

    private static void resetSync(ClientScreen s) {
        s.sync.reset();
        s.sampler.reset();
        s.mediaClock = null;
        s.lastDecision = SyncDecision.WAITING;
        s.reportedUrl = false;
    }

    static CreateSpec.Session sessionOf(ScreenMode mode) {
        // SHARED and CAST screens get a fresh in-memory session (no logins: the confused-deputy fix, ADR-012 item 1).
        return mode == ScreenMode.PRIVATE ? CreateSpec.Session.PRIVATE : CreateSpec.Session.SHARED;
    }

    /**
     * The session kind for this player's browser of a screen. The persistent profile (logins) is the owner's own: any
     * other viewer of a PRIVATE screen loads the owner's chosen URL in a fresh in-memory session, otherwise the owner
     * could point the URL at a page acting with the visitor's logins (the confused deputy ADR-012 closed for SHARED).
     */
    private CreateSpec.Session sessionFor(ScreenState st) {
        boolean owner = st.owner != null && st.owner.equals(player());
        return st.mode == ScreenMode.PRIVATE && owner ? CreateSpec.Session.PRIVATE : CreateSpec.Session.SHARED;
    }

    /**
     * Page size in CSS pixels, clamped to what we accept; a missing size falls back to 1280x720 (ADR default). The
     * area is clamped with M2's rule (one full frame must fit one FRAME, {@link FrameCanvas#MAX_PIXELS}: the height
     * shrinks, the width stays), so the size we request is the size the host paints. Requesting 3840x2160 used to get
     * 3840x1092 frames, which the uploader dropped as "wrong size" forever, with no reason shown.
     */
    static int[] pageSize(ScreenState s) {
        int w = s.resolutionW > 0 ? s.resolutionW : 1280;
        int h = s.resolutionH > 0 ? s.resolutionH : 720;
        w = Math.max(MIN_PAGE, Math.min(MAX_PAGE_W, w));
        h = Math.max(MIN_PAGE, Math.min(MAX_PAGE_H, h));
        if ((long) w * h > FrameCanvas.MAX_PIXELS) h = (int) Math.max(MIN_PAGE, FrameCanvas.MAX_PIXELS / w);
        return new int[] { w, h };
    }

    /** Epochs only grow; a wrapped int is treated as newer. */
    static boolean isOlder(int epoch, int current) {
        return epoch - current < 0;
    }

    // ------------------------------------------------------------------ diagnostics

    public List<String> diagnostics(long nowMs) {
        List<String> lines = new ArrayList<>();
        EngineState e = access.engine();
        lines.add(
            "session: " + session
                + " | engine: "
                + (e == null ? "not wired" : e)
                + " | host: "
                + (access.started() == null ? (access.available() ? "not started" : "unavailable") : lastStatus));
        lines.add(
            String.format(
                java.util.Locale.ROOT,
                "clock: %s offset=%.1fms bestRtt=%.1fms samples=%d | browsers %d/%d | screens %d",
                clock.ready() ? "synced" : "not synced",
                clock.offsetMs(),
                clock.bestRttMs(),
                clock.samples(),
                byHostId.size(),
                maxBrowsers,
                screens.size()));
        for (ClientScreen s : screens.values()) {
            lines.add(
                String.format(
                    java.util.Locale.ROOT,
                    "%s %dx%d d=%.1f %s id=%d lod=%s vol=%s sync=%s %s %s",
                    s.key,
                    s.state().widthBlocks,
                    s.state().heightBlocks,
                    s.distance,
                    s.granted ? "granted" : "denied",
                    s.hostScreenId,
                    s.lastLodSent,
                    s.audio.lastMuted() ? "muted" : String.format(java.util.Locale.ROOT, "%.2f", s.audio.lastVolume()),
                    s.lastDecision,
                    s.syncStats,
                    s.failure == null ? "" : s.failure.name()));
        }
        for (String event : hostEvents) lines.add("host event: " + event);
        return lines;
    }
}
