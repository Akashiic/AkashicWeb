package com.akashiic.web.client.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.akashiic.web.common.SitePolicy;

/**
 * {@link BrowserHost} over {@link HostSupervisor} and {@link HostConnection} (docs/CONTRACTS.md, M2 to M5).
 *
 * <p>
 * Every method is non-blocking and safe from the client thread: commands only update the screen's
 * {@link ScreenRecord} and, while READY, queue one message. Before READY (and while the host is down) the records
 * are the queue: on every (re)connect WELCOME goes first, then each screen is re-created with its current size, URL,
 * audio and render settings. Transient commands (input, back/forward/reload/stop, SYNC) are dropped while not READY:
 * replaying stale clicks or keystrokes into a freshly loaded page would type into the wrong place, and SYNC is resent
 * at 4 Hz anyway.
 *
 * <p>
 * URLs. Every URL is checked by {@link ClientUrlCheck} (the server's own UrlGate: internal addresses, site policy)
 * before it leaves the JVM, in CREATE, NAVIGATE and the CREATE of a replay. A refused URL never reaches the host: the
 * screen keeps its page (NAVIGATE) or starts blank (CREATE), and the listeners get a local {@code blocked-url} EVENT
 * whose detail carries the ReasonCode (URL_INVALID, URL_INTERNAL or URL_BLOCKED). Until {@link #policy} is called the
 * site policy denies everything, as it does for a policy with a null or unknown mode.
 *
 * <p>
 * Listener callbacks arrive on the reader thread (onStatus on the supervisor thread, and the "blocked-url" EVENT for a
 * URL refused here synchronously on the caller's thread); each listener is isolated so one that throws, even an Error,
 * cannot break the others, the connection or the supervisor. Implementations hop to the client thread in every case.
 */
public final class BrowserHostImpl implements BrowserHost {

    private static final Logger LOG = LogManager.getLogger("AkashicWeb/Host");

    private final HostLaunchConfig config;
    private final HostSupervisor supervisor;
    private final CopyOnWriteArrayList<HostListener> listeners = new CopyOnWriteArrayList<>();
    /** Sorted by id, which is creation order, so replay re-creates screens in the order the game made them. */
    private final ConcurrentSkipListMap<Integer, ScreenRecord> screens = new ConcurrentSkipListMap<>();
    private final AtomicInteger lastScreenId = new AtomicInteger();
    /** Test seam: see {@link ClientUrlCheck#ClientUrlCheck(SitePolicy, boolean)}. False for the game. */
    private final boolean allowFileUrls;

    private final Object lock = new Object();
    // Guarded by lock.
    private HostConnection connection;
    private SitePolicy policy;
    private ClientUrlCheck urls;
    private boolean shutDown;

    public BrowserHostImpl(HostLaunchConfig config) {
        this(config, HostClock.SYSTEM, ProcessControl.forThisPlatform());
    }

    BrowserHostImpl(HostLaunchConfig config, HostClock clock, ProcessControl processes) {
        this(config, clock, processes, false);
    }

    /**
     * Test seam: {@code allowFileUrlsForTesting} lets {@code file:///} URLs pass the client-side check. Only the
     * end-to-end test sets it, and it also starts its development host with the host's own test-only switch. The
     * constructor is package-private so game code cannot reach it.
     */
    BrowserHostImpl(HostLaunchConfig config, HostClock clock, ProcessControl processes,
        boolean allowFileUrlsForTesting) {
        this.config = config;
        this.allowFileUrls = allowFileUrlsForTesting;
        this.urls = new ClientUrlCheck(null, allowFileUrlsForTesting);
        this.supervisor = new HostSupervisor(config, clock, processes, new Inbound(), new SupervisorEvents());
    }

    /** Starts the supervisor (spawns the host in the background). Non-blocking; call once the engine is READY. */
    public void start() {
        supervisor.start();
    }

    /** The supervisor, for /web doctor details and tests. */
    HostSupervisor supervisor() {
        return supervisor;
    }

    @Override
    public void reenable() {
        supervisor.reenable();
    }

    @Override
    public HostStatus status() {
        return supervisor.status();
    }

    @Override
    public int createScreen(CreateSpec spec) {
        if (spec == null) throw new NullPointerException("spec");
        int id = lastScreenId.updateAndGet(v -> v >= Integer.MAX_VALUE ? 1 : v + 1);
        ClientUrlCheck.Decision d;
        synchronized (lock) {
            if (shutDown) return id;
            d = urls.check(spec.url);
            ScreenRecord r = new ScreenRecord(id, spec, d.ok() ? d.url : ClientUrlCheck.BLANK);
            screens.put(id, r);
            if (connection != null) connection.sendCreate(id, r.currentSpec());
        }
        if (!d.ok()) refuse(id, d);
        return id;
    }

    @Override
    public void destroyScreen(int screenId) {
        synchronized (lock) {
            if (screens.remove(screenId) != null && connection != null) connection.sendDestroy(screenId);
        }
    }

    @Override
    public void navigate(int screenId, String url) {
        ClientUrlCheck.Decision d;
        synchronized (lock) {
            ScreenRecord r = screens.get(screenId);
            if (r == null) return;
            d = urls.check(url);
            if (d.ok()) {
                r.url = d.url;
                if (connection != null) connection.sendNavigate(screenId, d.url);
            }
        }
        if (!d.ok()) refuse(screenId, d); // the screen keeps its current page
    }

    @Override
    public void action(int screenId, String action) {
        if (!HostMessages.isAction(action)) {
            LOG.warn("AkashicWeb: ignoring unknown navigation action '{}'", action);
            return;
        }
        synchronized (lock) {
            if (connection != null && screens.containsKey(screenId)) connection.sendAction(screenId, action);
        }
    }

    @Override
    public void input(int screenId, InputEvent event) {
        if (event == null) return;
        synchronized (lock) {
            ScreenRecord r = screens.get(screenId);
            if (r == null || connection == null) return;
            connection.sendInput(screenId, clampToPage(event, r.width, r.height));
        }
    }

    @Override
    public void audio(int screenId, boolean muted, float volume) {
        synchronized (lock) {
            ScreenRecord r = screens.get(screenId);
            if (r == null) return;
            r.audioSet = true;
            r.muted = muted;
            r.volume = HostMessages.clampVolume(volume);
            if (connection != null) connection.sendAudio(screenId, muted, r.volume);
        }
    }

    @Override
    public void render(int screenId, int fps, boolean painting) {
        synchronized (lock) {
            ScreenRecord r = screens.get(screenId);
            if (r == null) return;
            r.renderSet = true;
            r.fps = HostMessages.clampFps(fps);
            r.painting = painting;
            if (connection != null) connection.sendRender(screenId, r.fps, painting);
        }
    }

    @Override
    public void resize(int screenId, int width, int height) {
        synchronized (lock) {
            ScreenRecord r = screens.get(screenId);
            if (r == null) return;
            int[] size = HostMessages.clampSize(width, height);
            r.width = size[0];
            r.height = size[1];
            if (connection != null) connection.sendResize(screenId, r.width, r.height);
        }
    }

    @Override
    public void sync(int screenId, SyncCommand cmd) {
        if (cmd == null) return;
        synchronized (lock) {
            if (connection != null && screens.containsKey(screenId)) connection.sendSync(screenId, cmd);
        }
    }

    @Override
    public void policy(SitePolicy policy) {
        synchronized (lock) {
            this.policy = policy;
            this.urls = new ClientUrlCheck(policy, allowFileUrls);
            // The host re-checks what each screen shows against the new policy itself (POLICY handling).
            if (connection != null) connection.sendPolicy(policy);
        }
    }

    @Override
    public FrameRing frames(int screenId) {
        ScreenRecord r = screens.get(screenId);
        return r == null ? null : r.frames.ring();
    }

    @Override
    public void addListener(HostListener listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    @Override
    public void removeListener(HostListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void shutdown() {
        synchronized (lock) {
            if (shutDown) return;
            shutDown = true;
        }
        supervisor.shutdown();
        synchronized (lock) {
            connection = null;
            screens.clear();
        }
    }

    @Override
    public String lastFailureDetail() {
        String d = supervisor.lastFailureDetail();
        return d == null ? "" : d;
    }

    @Override
    public List<String> diagnostics() {
        List<String> out = new ArrayList<>(supervisor.diagnostics());
        out.add("host log dir: " + config.logDir.getAbsolutePath());
        for (ScreenRecord r : screens.values()) {
            long[] c = r.frames.ring()
                .counters();
            out.add(
                "screen " + r.id
                    + ": "
                    + r.width
                    + "x"
                    + r.height
                    + ", frames in "
                    + r.frames.framesIn()
                    + ", published "
                    + c[0]
                    + ", dropped unseen "
                    + c[1]
                    + ", taken "
                    + c[2]
                    + ", requeued "
                    + c[3]
                    + rejection(r.frames));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static String rejection(FramePipeline p) {
        long n = p.framesRejected();
        if (n == 0) return "";
        String now = p.rejectedSize();
        return ", too large and skipped " + n + (now == null ? "" : " (now " + now + ")");
    }

    /** Pointer coordinates inside the page, so a rounding error at the screen edge never reaches the host as -1. */
    static InputEvent clampToPage(InputEvent e, int width, int height) {
        boolean pointer = e.type == InputEvent.Type.MOUSE_MOVE || e.type == InputEvent.Type.MOUSE_DOWN
            || e.type == InputEvent.Type.MOUSE_UP
            || e.type == InputEvent.Type.WHEEL;
        if (!pointer) return e;
        int x = Math.max(0, Math.min(width - 1, e.x));
        int y = Math.max(0, Math.min(height - 1, e.y));
        if (x == e.x && y == e.y) return e;
        return new InputEvent(e.type, x, y, e.button, e.clickCount, e.deltaX, e.deltaY, e.keyCode, e.modifiers, e.text);
    }

    /** A URL this layer refuses shows up like a host-side block, so the screen's placeholder logic handles both. */
    private void refuse(int screenId, ClientUrlCheck.Decision d) {
        // The URL itself is not logged (it can be long and carry tokens); the host name and the reason are enough.
        LOG.warn(
            "AkashicWeb: refused a URL before it reached the host (screen {}): {} {} {}",
            screenId,
            d.code,
            d.reason,
            d.host);
        String json = d.event();
        deliver("event", l -> l.onEvent(screenId, json));
    }

    /**
     * Calls every listener, each isolated: whatever one throws (a RuntimeException, or an Error such as a
     * NoClassDefFoundError from game code) is logged and the others still run, and neither the reader thread nor the
     * supervisor thread is taken down by it.
     */
    private void deliver(String what, Consumer<HostListener> call) {
        for (HostListener l : listeners) {
            try {
                call.accept(l);
            } catch (Throwable t) {
                LOG.error("AkashicWeb: host listener failed (" + what + ")", t);
            }
        }
    }

    /** Called on the supervisor thread when a connection is ready: WELCOME first, then re-create every screen. */
    private void replay(HostConnection conn) {
        Map<Integer, ClientUrlCheck.Decision> refused = new LinkedHashMap<>();
        synchronized (lock) {
            if (shutDown) return;
            conn.sendWelcome(policy, config.locale, config.userAgent);
            for (ScreenRecord r : screens.values()) {
                // The URL leaves the JVM again: check it against the policy in force now, which may have changed.
                ClientUrlCheck.Decision d = urls.check(r.url);
                if (!d.ok()) {
                    r.url = ClientUrlCheck.BLANK;
                    refused.put(r.id, d);
                }
                conn.sendCreate(r.id, r.currentSpec());
                if (r.audioSet) conn.sendAudio(r.id, r.muted, r.volume);
                if (r.renderSet) conn.sendRender(r.id, r.fps, r.painting);
            }
            connection = conn;
        }
        for (Map.Entry<Integer, ClientUrlCheck.Decision> e : refused.entrySet()) refuse(e.getKey(), e.getValue());
    }

    private final class SupervisorEvents implements HostSupervisor.Callbacks {

        @Override
        public void onConnected(HostConnection conn, HelloInfo hello) {
            replay(conn);
        }

        @Override
        public void onDisconnected(HostConnection conn) {
            synchronized (lock) {
                if (connection == conn) connection = null;
            }
        }

        @Override
        public void onStatus(HostStatus status) {
            deliver("status", l -> l.onStatus(status));
        }
    }

    /** Reader-thread side. Looks screens up without the lock (concurrent map) so frames never wait for commands. */
    private final class Inbound implements HostConnection.Inbound {

        @Override
        public FramePipeline framesFor(int screenId) {
            ScreenRecord r = screens.get(screenId);
            return r == null ? null : r.frames;
        }

        @Override
        public void onPcm(int screenId, PcmChunk c) {
            if (!screens.containsKey(screenId)) return;
            deliver("pcm", l -> l.onPcm(screenId, c.ptsMicros, c.sampleRate, c.channels, c.interleaved, c.frames));
        }

        @Override
        public void onState(int screenId, String json) {
            deliver("state", l -> l.onState(screenId, json));
        }

        @Override
        public void onMedia(int screenId, String json) {
            deliver("media", l -> l.onMedia(screenId, json));
        }

        @Override
        public void onEvent(int screenId, String json) {
            deliver("event", l -> l.onEvent(screenId, json));
        }

        /**
         * A page too large to hold (for example painted at a display scale above 100%): its frames are skipped, the
         * connection and every other screen keep running, and this screen fails visibly with a local
         * {@code load-error} EVENT whose detail names {@code frame-too-large} and TEXTURE_TOO_LARGE.
         */
        @Override
        public void onFrameRejected(int screenId, int width, int height) {
            LOG.warn(
                "AkashicWeb: screen {} sent a {}x{} page, larger than {} per side or {} pixels; its frames are skipped",
                screenId,
                width,
                height,
                FrameCanvas.MAX_DIMENSION,
                FrameCanvas.MAX_PIXELS);
            String json = HostMessages.frameTooLargeEvent(width, height);
            deliver("event", l -> l.onEvent(screenId, json));
        }
    }
}
