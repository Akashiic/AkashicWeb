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
import com.akashiic.web.client.host.FrameRing;
import com.akashiic.web.client.host.HostStatus;
import com.akashiic.web.client.host.InputEvent;
import com.akashiic.web.client.host.SyncCommand;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.client.sync.LocalMedia;
import com.akashiic.web.client.sync.MediaClock;
import com.akashiic.web.client.sync.ServerClock;
import com.akashiic.web.client.sync.SyncDecision;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.Rights;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenMode;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.SitePolicy;
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

        Hit(ClientScreen screen, double u, double v) {
            this.screen = screen;
            this.u = u;
            this.v = v;
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
    private final int maxBrowsers;

    private BrowserHost attachedHost;
    private HostStatus lastStatus;
    private PlayerView view;
    private boolean ticked;
    private long lastTickMs;
    private long lastHousekeepingMs;

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
        session.onHello(protoMajor, protoMinor, killSwitch, policy);
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
        if (state.epoch != old.epoch) resetSync(s);
        BrowserHost host = access.started();
        if (s.hostScreenId == 0 || host == null) {
            s.navigatedEpoch = state.epoch;
            return;
        }
        if (sessionOf(state.mode) != sessionOf(old.mode)) {
            destroyBrowser(s, host); // a different session kind needs a new browser; recreated next tick
            return;
        }
        int[] size = pageSize(state);
        if (size[0] != s.requestedW || size[1] != s.requestedH) {
            host.resize(s.hostScreenId, size[0], size[1]);
            s.requestedW = size[0];
            s.requestedH = size[1];
        }
        if (state.epoch != s.navigatedEpoch) navigate(s, host);
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

    public void onUrl(ScreenKey key, String url, int epoch) {
        ClientScreen s = screens.get(key);
        if (s == null || url == null || isOlder(epoch, s.state().epoch)) return;
        boolean changed = epoch != s.state().epoch || !url.equals(s.state().url);
        s.state().url = url;
        s.state().epoch = epoch;
        if (!changed) return;
        resetSync(s);
        BrowserHost host = access.started();
        if (s.hostScreenId != 0 && host != null) navigate(s, host);
        else s.navigatedEpoch = epoch;
    }

    /** S2CInput: our own input, validated and relayed back by the server (we are the controller). */
    public void onInput(ScreenKey key, int epoch, ScreenInput input) {
        ClientScreen s = screens.get(key);
        BrowserHost host = access.started();
        if (s == null || host == null || s.hostScreenId == 0 || epoch != s.state().epoch) return;
        InputEvent e = InputTranslator.toHost(input, s.requestedW, s.requestedH);
        if (e != null) host.input(s.hostScreenId, e);
    }

    public void onMediaClock(ScreenKey key, int epoch, double mediaTime, float rate, boolean paused,
        long serverTimeMs) {
        ClientScreen s = screens.get(key);
        if (s == null || epoch != s.state().epoch || Double.isNaN(mediaTime) || Double.isInfinite(mediaTime)) return;
        s.mediaClock = new MediaClock(epoch, mediaTime, rate, paused, serverTimeMs);
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
        ClientScreen s = byHostId.get(hostId);
        if (s == null) return;
        ClientReason reason = ClientReason.fromEventKind(kind);
        if (reason != null) s.failure = reason;
        if ("blocked-url".equals(kind) && isController(s)) notifier.notice(ReasonCode.URL_BLOCKED.langKey());
        LOG.debug("AkashicWeb: {} host event {}", s.key, kind);
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
        in.hasUrl = s.state().url != null && !s.state().url.isEmpty();
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

    public UUID player() {
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
     * A click from the crosshair: becomes a page left click on the controller's own browser after the server checks
     * rights, reach and the controller lock. A free screen is taken first so the click is not rejected.
     */
    public boolean click(ClientScreen s, double u, double v, int button, int modifiers) {
        if (!can(s, Rights.CLICK_TYPE)) return false;
        takeIfFree(s);
        int count = clicks.click(s.key, u, v, timeMs.getAsLong());
        ServerBound out = server.get();
        out.pointer(s.key, ScreenInput.pointer(ScreenInput.Kind.DOWN, (float) u, (float) v, button, count, modifiers));
        out.pointer(s.key, ScreenInput.pointer(ScreenInput.Kind.UP, (float) u, (float) v, button, count, modifiers));
        return true;
    }

    public boolean wheel(ClientScreen s, double u, double v, float notches, int modifiers) {
        if (!can(s, Rights.CLICK_TYPE) || notches == 0) return false;
        takeIfFree(s);
        server.get()
            .pointer(s.key, ScreenInput.wheel((float) u, (float) v, 0, notches, modifiers));
        return true;
    }

    /** Keyboard GUI output (C2SKey). */
    public boolean type(ClientScreen s, ScreenInput input) {
        if (input == null || !can(s, Rights.CLICK_TYPE)) return false;
        takeIfFree(s);
        server.get()
            .key(s.key, input);
        return true;
    }

    public void setUrl(ClientScreen s, String url) {
        server.get()
            .setUrl(s.key, s.state().epoch, url);
    }

    /**
     * Address bar navigation. The server validates and audits every action. Back, forward and stop have no
     * server-to-client form in protocol v1, so the controller also applies them to its own browser at once; the
     * resulting page is then reported like any controller navigation, and followers converge through the new epoch.
     */
    public void nav(ClientScreen s, String action) {
        server.get()
            .nav(s.key, s.state().epoch, action);
        BrowserHost host = access.started();
        boolean local = "back".equals(action) || "forward".equals(action) || "stop".equals(action);
        if (local && host != null && s.hostScreenId != 0 && isController(s)) host.action(s.hostScreenId, action);
    }

    public void control(ClientScreen s, boolean take) {
        server.get()
            .control(s.key, take);
    }

    private void takeIfFree(ClientScreen s) {
        if (s.state().controller == null && s.state().mode != ScreenMode.PRIVATE) control(s, true);
    }

    // ------------------------------------------------------------------ internals

    private boolean browsersAllowed() {
        EngineState e = access.engine();
        if (!session.allowsBrowsers() || e == null || e.kind != EngineState.Kind.READY || !access.available())
            return false;
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
                s.inView = false;
                s.priority = 0;
                continue;
            }
            double lx = v.eyeX - s.key.x, ly = v.eyeY - s.key.y, lz = v.eyeZ - s.key.z;
            s.distance = g.distanceTo(lx, ly, lz);
            double[] c = g.center();
            double[] center = { c[0] + s.key.x, c[1] + s.key.y, c[2] + s.key.z };
            s.inView = ScreenLod.inView(eye, look, center, g.boundingRadius());
            s.priority = BrowserBudget.score(s.state().widthBlocks, s.state().heightBlocks, s.distance);
        }
    }

    private Set<ScreenKey> grant() {
        List<BrowserBudget.Candidate<ScreenKey>> candidates = new ArrayList<>();
        for (ClientScreen s : screens.values()) {
            if (s.geometry() == null || Double.isInfinite(s.distance)) continue;
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
        BrowserHost host = access.obtain();
        if (host == null) return;
        attach(host);
        int[] size = pageSize(s.state());
        ScreenLod lod = ScreenLod.of(s.distance, s.inView, s.maxDimBlocks());
        CreateSpec spec = new CreateSpec(
            size[0],
            size[1],
            lod.fps,
            s.state().url,
            sessionOf(s.state().mode),
            CreateSpec.Audio.OS,
            0f);
        int id;
        try {
            id = host.createScreen(spec);
        } catch (RuntimeException e) {
            // Documented boundary: a host bug must leave the screen on its placeholder, not break the tick.
            LOG.error("AkashicWeb: createScreen failed for {}", s.key, e);
            s.failure = ClientReason.HOST_UNAVAILABLE;
            return;
        }
        if (id <= 0) return;
        s.hostScreenId = id;
        byHostId.put(id, s);
        s.requestedW = size[0];
        s.requestedH = size[1];
        s.navigatedEpoch = s.state().epoch;
        s.lastNavigateMs = timeMs.getAsLong();
        s.failure = null;
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
        s.failure = null;
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
        SyncDecision d = s.sync.update(mc, clock.serverNowMs(now), s.localMedia, now, Double.NaN);
        s.lastDecision = d;
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
        s.reportedUrl = true;
        s.lastUrlReportMs = now;
        server.get()
            .setUrl(s.key, s.state().epoch, p.url);
    }

    private void handleStatus(HostStatus status) {
        HostStatus previous = lastStatus;
        lastStatus = status;
        if (status == null || (previous != null && previous.kind == status.kind)) return;
        BrowserHost host = access.started();
        if (status.kind == HostStatus.Kind.DOWN || status.kind == HostStatus.Kind.DISABLED) {
            // The process is gone: every browser died with it. Forget the ids; the tick recreates when READY again.
            LOG.warn("AkashicWeb: browser host {}; {} browser(s) lost", status, byHostId.size());
            for (ClientScreen s : screens.values()) forgetBrowser(s, host);
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

    private void forgetBrowser(ClientScreen s, BrowserHost host) {
        if (s.hostScreenId == 0) return;
        if (s.heldSlot != null && host != null) {
            FrameRing ring = host.frames(s.hostScreenId);
            if (ring != null) ring.release(s.heldSlot);
        }
        s.heldSlot = null;
        byHostId.remove(s.hostScreenId);
        s.hostScreenId = 0;
        s.lastLodSent = null;
        s.lastSyncSent = null;
        s.localMedia = null;
        s.page = PageState.EMPTY;
        s.audio.invalidate();
        resetSync(s);
    }

    private void dispose(ClientScreen s) {
        destroyBrowser(s, access.started());
        if (s.texture != null) s.texture.delete();
        s.texture = null;
        s.hasFrame = false;
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

    /** Page size in CSS pixels, clamped to what we accept; a missing size falls back to 1280x720 (ADR default). */
    static int[] pageSize(ScreenState s) {
        int w = s.resolutionW > 0 ? s.resolutionW : 1280;
        int h = s.resolutionH > 0 ? s.resolutionH : 720;
        return new int[] { Math.max(MIN_PAGE, Math.min(MAX_PAGE_W, w)), Math.max(MIN_PAGE, Math.min(MAX_PAGE_H, h)) };
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
                    "%s %dx%d d=%.1f %s id=%d lod=%s vol=%s sync=%s %s",
                    s.key,
                    s.state().widthBlocks,
                    s.state().heightBlocks,
                    s.distance,
                    s.granted ? "granted" : "denied",
                    s.hostScreenId,
                    s.lastLodSent,
                    s.audio.lastMuted() ? "muted" : String.format(java.util.Locale.ROOT, "%.2f", s.audio.lastVolume()),
                    s.lastDecision,
                    s.failure == null ? "" : s.failure.name()));
        }
        return lines;
    }
}
