package com.akashiic.web.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akashiic.web.client.host.CreateSpec;
import com.akashiic.web.client.host.HostStatus;
import com.akashiic.web.client.host.InputEvent;
import com.akashiic.web.client.host.SyncCommand;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.client.sync.LocalMedia;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.Rights;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenMode;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.common.net.ScreenInput;
import com.akashiic.web.protocol.Protocol;

class ClientScreensTest {

    private static final UUID ME = new UUID(10, 10);
    private static final UUID OTHER = new UUID(20, 20);
    private static final int SOUTH = 3;
    private static final SitePolicy POLICY = SitePolicy.blocklist(Collections.<String>emptyList());

    private FakeHost host;
    private RecordingServer server;
    private long[] now;
    private List<Runnable> clientQueue;
    private List<String> notices;
    private ClientScreens screens;

    @BeforeEach
    void setUp() {
        host = new FakeHost();
        server = new RecordingServer();
        now = new long[] { 1_000 };
        clientQueue = new ArrayList<>();
        notices = new ArrayList<>();
        screens = new ClientScreens(
            host,
            () -> server,
            () -> now[0],
            clientQueue::add,
            (k, a) -> notices.add(k),
            2,
            1L);
    }

    private static ScreenState screen(int x, int w, int h, String url, int epoch) {
        ScreenState s = new ScreenState();
        s.key = new ScreenKey(0, x, 64, 0, SOUTH);
        s.widthBlocks = w;
        s.heightBlocks = h;
        s.resolutionW = 1280;
        s.resolutionH = 720;
        s.url = url;
        s.epoch = epoch;
        s.owner = ME;
        return s;
    }

    /** Standing in front of the south faces, looking north. */
    private static PlayerView view(double x, double z) {
        return new PlayerView(0, x, 65, z, 0, 0, -1, ME, false, 1f, 1f);
    }

    private void tick() {
        screens.tick(view(1, 5));
        now[0] += 50;
    }

    private void hello() {
        screens.onHello(Protocol.MAJOR, Protocol.MINOR, false, POLICY);
    }

    private void syncClock() {
        for (int i = 0; i < 3; i++) {
            now[0] += 300;
            long nonce = screens.clock()
                .pollPing(now[0]);
            screens.clock()
                .onPong(nonce, now[0], now[0] + 50_000, now[0] + 10);
        }
    }

    private ClientScreen first() {
        return screens.all()
            .iterator()
            .next();
    }

    @Test
    void noBrowserBeforeTheServerHello() {
        screens.onScreenState(screen(0, 2, 2, "https://a.example/", 1));
        tick();
        assertFalse(host.started, "the host is not even started");
        assertEquals(0, server.count("ping"), "no clock pings to a server without AkashicWeb");
        hello();
        tick();
        assertEquals(1, host.created.size());
        CreateSpec spec = host.created.get(0);
        assertEquals("https://a.example/", spec.url);
        assertEquals(1280, spec.width);
        assertEquals(CreateSpec.Session.SHARED, spec.session);
        assertEquals(CreateSpec.Audio.OS, spec.audio);
        assertTrue(host.saw("listener"));
        assertTrue(host.saw("policy BLOCKLIST"));
        assertTrue(server.count("ping") >= 1);
    }

    @Test
    void aNewBrowserIsSilentUntilDistanceIsApplied() {
        hello();
        screens.onScreenState(screen(0, 2, 2, "https://a.example/", 1));
        tick();
        int create = host.indexOf("create 1");
        int mute = host.indexOf("audio 1 muted");
        int on = host.indexOf("audio 1 on");
        assertTrue(create >= 0 && mute > create && on > mute, host.calls.toString());
        assertTrue(host.saw("render 1 30 true"), "near and in view: 30 fps");
    }

    @Test
    void noBrowserWithoutAReadyEngineOrAHost() {
        hello();
        screens.onScreenState(screen(0, 2, 2, "https://a.example/", 1));
        host.engine = new EngineState(EngineState.Kind.CONSENT_NEEDED, 0, ReasonCode.OK);
        tick();
        assertTrue(host.created.isEmpty());
        assertEquals("akashicweb.placeholder.consent_needed", screens.placeholder(first()).key);
        host.engine = null;
        tick();
        assertTrue(host.created.isEmpty());
        host.engine = new EngineState(EngineState.Kind.READY, 1, ReasonCode.OK);
        host.available = false;
        tick();
        assertTrue(host.created.isEmpty());
        assertEquals(ReasonCode.HOST_UNAVAILABLE.langKey(), screens.placeholder(first()).detailKey);
    }

    @Test
    void budgetGoesToTheBiggestClosestScreens() {
        hello();
        screens.onScreenState(screen(0, 2, 2, "https://small.example/", 1));
        screens.onScreenState(screen(40, 16, 9, "https://telao.example/", 1));
        screens.onScreenState(screen(-20, 3, 2, "https://mid.example/", 1));
        tick();
        assertEquals(2, host.created.size());
        List<String> urls = new ArrayList<>();
        for (CreateSpec c : host.created) urls.add(c.url);
        assertTrue(urls.contains("https://telao.example/"), urls.toString());
        assertTrue(urls.contains("https://small.example/"), urls.toString());
        ClientScreen denied = screens.get(new ScreenKey(0, -20, 64, 0, SOUTH));
        assertEquals(0, denied.hostScreenId());
        Placeholder p = screens.placeholder(denied);
        assertEquals(ReasonCode.BROWSER_BUDGET.langKey(), p.detailKey);
    }

    @Test
    void killSwitchMutesThenDestroys() {
        hello();
        screens.onScreenState(screen(0, 2, 2, "https://a.example/", 1));
        tick();
        host.calls.clear();
        screens.onHello(Protocol.MAJOR, Protocol.MINOR, true, POLICY);
        int mute = host.indexOf("audio 1 muted");
        int destroy = host.indexOf("destroy 1");
        assertTrue(mute >= 0 && destroy > mute, host.calls.toString());
        tick();
        assertEquals(1, host.created.size(), "no new browser under the kill switch");
        assertEquals(ReasonCode.DISABLED_BY_SERVER.langKey(), screens.placeholder(first()).detailKey);
    }

    @Test
    void worldUnloadAndDisconnectTearEverythingDown() {
        hello();
        screens.onScreenState(screen(0, 2, 2, "https://a.example/", 1));
        tick();
        screens.tick(null); // no world
        assertTrue(host.indexOf("audio 1 muted") < host.indexOf("destroy 1"));
        assertTrue(
            screens.all()
                .isEmpty());
        screens.onScreenState(screen(0, 2, 2, "https://a.example/", 1));
        tick();
        assertEquals(2, host.created.size(), "the hello survives a world change");
        screens.onDisconnect();
        assertTrue(host.saw("destroy 2"));
        screens.onScreenState(screen(0, 2, 2, "https://a.example/", 1));
        tick();
        assertEquals(2, host.created.size(), "after a disconnect a new hello is required");
        assertFalse(
            screens.session()
                .helloReceived());
    }

    @Test
    void urlAndResolutionChangesFollowTheServer() {
        hello();
        ScreenState s = screen(0, 2, 2, "https://a.example/", 1);
        screens.onScreenState(s);
        tick();
        ScreenKey key = s.key;
        screens.onUrl(key, "https://b.example/", 2);
        assertTrue(host.saw("navigate 1 https://b.example/"));
        host.calls.clear();
        screens.onUrl(key, "https://old.example/", 1);
        assertFalse(host.saw("navigate"), "older epoch dropped");
        screens.onUrl(key, "https://b.example/", 2);
        assertFalse(host.saw("navigate"), "same epoch and URL: no reload");
        ScreenState resized = screen(0, 2, 2, "https://b.example/", 2);
        resized.resolutionW = 1920;
        resized.resolutionH = 1080;
        screens.onScreenState(resized);
        assertTrue(host.saw("resize 1 1920x1080"));
        assertFalse(host.saw("navigate"));
        ScreenState newEpoch = screen(0, 2, 2, "https://c.example/", 3);
        screens.onScreenState(newEpoch);
        assertTrue(host.saw("navigate 1 https://c.example/"));
        assertEquals(
            "https://c.example/",
            screens.get(key)
                .state().url);
        host.calls.clear();
        ScreenState stale = screen(0, 3, 2, "https://b.example/", 2);
        screens.onScreenState(stale);
        assertFalse(host.saw("navigate"), "a stale snapshot never rolls the URL back");
        assertEquals(
            "https://c.example/",
            screens.get(key)
                .state().url);
        assertEquals(
            3,
            screens.get(key)
                .state().widthBlocks,
            "the rest of the record is still taken");
    }

    @Test
    void relayedInputReachesOnlyTheCurrentEpoch() {
        hello();
        ScreenState s = screen(0, 2, 2, "https://a.example/", 4);
        screens.onScreenState(s);
        tick();
        screens.onInput(s.key, 4, ScreenInput.pointer(ScreenInput.Kind.DOWN, 0.5f, 0.5f, 0, 1, 0));
        assertEquals(1, host.inputs.size());
        InputEvent e = host.inputs.get(0);
        assertEquals(640, e.x);
        assertEquals(360, e.y);
        screens.onInput(s.key, 3, ScreenInput.pointer(ScreenInput.Kind.DOWN, 0.5f, 0.5f, 0, 1, 0));
        assertEquals(1, host.inputs.size(), "stale epoch");
        screens.onInput(s.key, 4, ScreenInput.key(true, "x", ScreenInput.MOD_CTRL));
        assertEquals(1, host.inputs.size(), "cut is never applied");
    }

    @Test
    void controllerSendsMediaSamplesStampedInServerTime() {
        hello();
        ScreenState s = screen(0, 2, 2, "https://a.example/", 1);
        s.controller = ME;
        screens.onScreenState(s);
        tick();
        int id = first().hostScreenId();
        screens.onHostMedia(id, media(10, Double.NaN));
        assertEquals(0, server.samples.size(), "no sample before the clock is synced");
        syncClock();
        screens.onHostMedia(id, media(10, now[0] + 50_000.0));
        assertEquals(1, server.samples.size());
        assertEquals(1, server.samples.get(0).epoch);
        screens.onHostMedia(id, media(10.1, now[0] + 50_100.0));
        assertEquals(1, server.samples.size(), "4 Hz throttle");
        now[0] += 250;
        screens.onHostMedia(id, media(10.3, now[0] + 50_000.0));
        assertEquals(2, server.samples.size());
    }

    private LocalMedia media(double t, double stamp) {
        return new LocalMedia(t, false, 1f, 600, false, 0, false, false, now[0], stamp);
    }

    @Test
    void followerSteersTowardTheServerClock() {
        hello();
        ScreenState s = screen(0, 2, 2, "https://a.example/", 1);
        s.controller = OTHER;
        screens.onScreenState(s);
        tick();
        syncClock();
        int id = first().hostScreenId();
        double serverNow = screens.clock()
            .serverNowMs(now[0]);
        screens.onMediaClock(s.key, 1, 20.0, 1f, false, (long) serverNow);
        screens.onHostMedia(id, media(19.9, Double.NaN)); // 100 ms behind
        tick();
        assertEquals(1, host.syncs.size(), host.calls.toString());
        SyncCommand c = host.syncs.get(0);
        assertTrue(c.rate > 1.0f && c.rate <= 1.05f, "speeds up: " + c.rate);
        assertFalse(c.seek);
        screens.onMediaClock(s.key, 2, 99.0, 1f, false, (long) serverNow);
        now[0] += 300;
        screens.onHostMedia(id, media(19.9 + 0.35, Double.NaN));
        tick();
        assertEquals(1, host.syncs.size(), "a clock for another epoch is ignored and an unchanged rate is not resent");
    }

    @Test
    void controllerNavigationIsReportedOnceSettled() {
        hello();
        ScreenState s = screen(0, 2, 2, "https://a.example/", 1);
        s.controller = ME;
        screens.onScreenState(s);
        tick();
        int id = first().hostScreenId();
        screens.onHostState(id, new PageState("https://a.example/other", "", false, true, false, false));
        assertEquals(0, server.count("setUrl"), "inside the settle window after our own navigate");
        now[0] += UrlConvergence.NAVIGATE_SETTLE_MS;
        screens.onHostState(id, new PageState("https://a.example/other", "", true, true, false, false));
        assertEquals(0, server.count("setUrl"), "still loading");
        screens.onHostState(id, new PageState("https://a.example/other", "", false, true, false, false));
        assertEquals(1, server.count("setUrl 1 https://a.example/other"));
    }

    @Test
    void hostRestartForgetsBrowsersAndRecreatesThem() {
        hello();
        screens.onScreenState(screen(0, 2, 2, "https://a.example/", 1));
        tick();
        host.status = HostStatus.down(ReasonCode.HOST_EXITED);
        tick();
        assertEquals(0, first().hostScreenId());
        assertEquals(ReasonCode.HOST_EXITED.langKey(), screens.placeholder(first()).detailKey);
        assertEquals(1, host.created.size(), "nothing is created while the host is down");
        host.status = HostStatus.READY;
        tick();
        assertEquals(2, host.created.size());
        assertTrue(host.calls.lastIndexOf("policy BLOCKLIST") > 0, "policy pushed again after the restart");
    }

    @Test
    void screensWhoseTileVanishedArePrunedAfterAGracePeriod() {
        hello();
        screens.onScreenState(screen(0, 2, 2, "https://a.example/", 1));
        tick();
        screens.prune(k -> false);
        assertEquals(
            1,
            screens.all()
                .size());
        now[0] += ClientScreens.PRUNE_GRACE_MS;
        screens.prune(k -> false);
        assertTrue(
            screens.all()
                .isEmpty());
        assertTrue(host.saw("destroy 1"));
    }

    @Test
    void clicksNeedRightsAndTakeAFreeScreen() {
        hello();
        ScreenState s = screen(0, 2, 2, "https://a.example/", 1);
        s.owner = OTHER;
        screens.onScreenState(s);
        tick();
        ClientScreen cs = first();
        assertFalse(screens.click(cs, 0.5, 0.5, ScreenInput.BUTTON_LEFT, 0), "visitors only watch");
        assertTrue(server.pointers.isEmpty());
        ScreenState mine = screen(0, 2, 2, "https://a.example/", 1);
        screens.onScreenState(mine);
        assertTrue(screens.click(cs, 0.25, 0.75, ScreenInput.BUTTON_LEFT, 0));
        assertEquals("control true", server.sent.get(server.sent.size() - 3));
        assertEquals(ScreenInput.Kind.DOWN, server.pointers.get(0).kind);
        assertEquals(ScreenInput.Kind.UP, server.pointers.get(1).kind);
        assertEquals(0.25f, server.pointers.get(0).u, 1e-6);
        screens.click(cs, 0.25, 0.75, ScreenInput.BUTTON_LEFT, 0);
        assertEquals(2, server.pointers.get(2).clickCount, "double click");
        assertTrue(screens.can(cs, Rights.CHANGE_URL));
    }

    @Test
    void backAndForwardAlsoApplyLocallyOnTheController() {
        hello();
        ScreenState s = screen(0, 2, 2, "https://a.example/", 1);
        s.controller = ME;
        screens.onScreenState(s);
        tick();
        screens.nav(first(), "back");
        assertEquals(1, server.count("nav 1 back"));
        assertTrue(host.saw("action 1 back"));
        screens.nav(first(), "reload");
        assertEquals(1, server.count("nav 1 reload"));
        assertFalse(host.saw("action 1 reload"), "reload goes through the server for everyone");
    }

    @Test
    void crosshairHitFindsThePicture() {
        screens.onScreenState(screen(0, 2, 2, "https://a.example/", 1));
        ClientScreens.Hit hit = screens.hit(0, 1.0, 65.0, 1.0, SOUTH);
        assertNotNull(hit);
        assertEquals(0.5, hit.u, 1e-9);
        assertEquals(0.5, hit.v, 1e-9);
        assertNull(screens.hit(0, 1.0, 65.0, 1.0, 2), "other side");
        assertNull(screens.hit(-1, 1.0, 65.0, 1.0, SOUTH), "other dimension");
        assertNull(screens.hit(0, 5.0, 65.0, 1.0, SOUTH), "beside the screen");
    }

    @Test
    void blockedUrlEventsNotifyTheControllerAndCrashesShowAReason() {
        hello();
        ScreenState s = screen(0, 2, 2, "https://a.example/", 1);
        s.controller = ME;
        screens.onScreenState(s);
        tick();
        int id = first().hostScreenId();
        screens.onHostEvent(id, "blocked-url");
        assertEquals(Collections.singletonList(ReasonCode.URL_BLOCKED.langKey()), notices);
        screens.onHostEvent(id, "renderer-gone");
        assertEquals(ReasonCode.PAGE_CRASHED.langKey(), screens.placeholder(first()).detailKey);
    }

    @Test
    void hostCallbacksHopThroughTheClientQueue() {
        hello();
        screens.onScreenState(screen(0, 2, 2, "https://a.example/", 1));
        tick();
        host.listeners.get(0)
            .onState(1, "{\"url\":\"https://a.example/\",\"title\":\"T\",\"canGoBack\":true}");
        assertEquals("", first().page().title, "nothing applied on the reader thread");
        assertEquals(1, clientQueue.size());
        clientQueue.remove(0)
            .run();
        assertEquals("T", first().page().title);
        host.listeners.get(0)
            .onState(1, "garbage");
        assertTrue(clientQueue.isEmpty(), "malformed messages are dropped before the hop");
    }

    @Test
    void privateScreensGetAPrivateSessionAndNoSync() {
        hello();
        ScreenState s = screen(0, 2, 2, "https://a.example/", 1);
        s.mode = ScreenMode.PRIVATE;
        screens.onScreenState(s);
        tick();
        assertEquals(CreateSpec.Session.PRIVATE, host.created.get(0).session);
        ScreenState shared = screen(0, 2, 2, "https://a.example/", 1);
        screens.onScreenState(shared);
        assertTrue(host.saw("destroy 1"), "a session change needs a new browser");
        tick();
        assertEquals(CreateSpec.Session.SHARED, host.created.get(1).session);
    }
}
