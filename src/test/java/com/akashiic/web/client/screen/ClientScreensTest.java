package com.akashiic.web.client.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

import com.akashiic.web.client.gui.LangKeys;
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

    /** Review low finding: a telão 150 blocks away (beyond the 128-block tile render distance) outbid a monitor. */
    @Test
    void screensBeyondTheTileRenderDistanceGetNoBrowser() {
        hello();
        ScreenState telao = screen(0, 64, 36, "https://telao.example/", 1);
        telao.key = new ScreenKey(0, 0, 64, -150, SOUTH);
        screens.onScreenState(telao);
        ScreenState monitor = screen(10, 4, 3, "https://monitor.example/", 1);
        screens.onScreenState(monitor);
        screens.tick(new PlayerView(0, 1, 65, 5, 0, 0, -1, ME, false, 1f, 1f));
        List<String> urls = new ArrayList<>();
        for (CreateSpec c : host.created) urls.add(c.url);
        assertEquals(Collections.singletonList("https://monitor.example/"), urls, "the invisible telão gets nothing");
        ClientScreen far = screens.get(telao.key);
        assertEquals(0, far.hostScreenId());
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

    /** D-016: input goes straight to our own browser, never to the server, and only while we hold the lock. */
    @Test
    void inputGoesStraightToOurOwnBrowserWhileWeAreTheController() {
        hello();
        ScreenState s = screen(0, 2, 2, "https://a.example/", 4);
        s.controller = OTHER;
        screens.onScreenState(s);
        tick();
        ClientScreen cs = first();
        assertTrue(screens.type(cs, ScreenInput.key(true, "a", 0)), "the owner may type");
        assertTrue(host.inputs.isEmpty(), "someone else holds the lock: nothing reaches our browser");

        ScreenState mine = screen(0, 2, 2, "https://a.example/", 4);
        mine.controller = ME;
        screens.onScreenState(mine);
        now[0] += ClientScreens.KEEPALIVE_MS;
        assertTrue(screens.click(cs, 0.5, 0.5, ScreenInput.BUTTON_LEFT, 0));
        assertEquals(2, host.inputs.size(), "down and up, applied locally at once");
        InputEvent e = host.inputs.get(0);
        assertEquals(640, e.x);
        assertEquals(360, e.y);
        assertEquals(1, server.count("control true"), "the first local input is also the lock keepalive");
        screens.type(cs, ScreenInput.key(true, "x", ScreenInput.MOD_CTRL));
        assertEquals(2, host.inputs.size(), "cut is never applied");
        screens.type(cs, ScreenInput.chars("ç"));
        assertEquals(3, host.inputs.size());
        assertEquals(1, server.count("control true"), "keepalive at most every " + ClientScreens.KEEPALIVE_MS + " ms");
        now[0] += ClientScreens.KEEPALIVE_MS;
        screens.wheel(cs, 0.5, 0.5, 1f, 0);
        assertEquals(4, host.inputs.size());
        assertEquals(2, server.count("control true"));
        assertFalse(
            server.sent.stream()
                .anyMatch(m -> m.startsWith("pointer") || m.startsWith("key")));
    }

    /** The server-side UUID from S2CHello decides who the controller is (offline-mode and proxied servers). */
    @Test
    void theHelloUuidIdentifiesUsAsTheController() {
        UUID serverSide = new UUID(77, 77);
        screens.onHello(Protocol.MAJOR, Protocol.MINOR, false, POLICY, serverSide);
        ScreenState s = screen(0, 2, 2, "https://a.example/", 1);
        s.controller = serverSide;
        screens.onScreenState(s);
        tick();
        assertTrue(screens.isController(first()), "the session UUID (ME) differs, the hello's UUID matches");
        assertEquals(serverSide, screens.player());
        screens.onDisconnect();
        assertNull(
            screens.session()
                .playerUuid());
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
        assertTrue(
            String.join("\n", screens.diagnostics(now[0]))
                .contains("err p50=100ms"),
            "the follower's error is in /awdoctor");
        screens.onMediaClock(s.key, 2, 99.0, 1f, false, (long) serverNow);
        now[0] += 300;
        screens.onHostMedia(id, media(19.9 + 0.35, Double.NaN));
        tick();
        assertEquals(1, host.syncs.size(), "a clock for another epoch is ignored and an unchanged rate is not resent");
    }

    /** Item 3 of the wave-2 task: S2CMediaClock.live/liveLagMs reach the followers' live-edge rule. */
    @Test
    void followersAimBehindTheControllersReportedLiveLag() {
        hello();
        ScreenState s = screen(0, 2, 2, "https://live.example/", 1);
        s.controller = OTHER;
        screens.onScreenState(s);
        tick();
        syncClock();
        int id = first().hostScreenId();
        double serverNow = screens.clock()
            .serverNowMs(now[0]);
        screens.onMediaClock(s.key, 1, 101.0, 1f, false, (long) serverNow, true, 3_000);
        // MEDIA from our own (follower) browser: M1 samples followers too. Our edge is at 102.
        screens
            .onHostMedia(id, new LocalMedia(101.0, false, 1f, Double.NaN, true, 1.0, false, false, now[0], Double.NaN));
        tick();
        assertEquals(1, host.syncs.size(), host.calls.toString());
        assertEquals(98.5, host.syncs.get(0).targetMediaTime, 0.01, "edge 102 - (3.0 s controller lag + 0.5 s)");
        assertTrue(host.syncs.get(0).rate <= 1.0f, "never forward toward the edge");
    }

    /** Review low finding: a refused report was resent every second for as long as the page stayed there. */
    @Test
    void aReportIsSentOncePerPageAndEpochAndNeverForABlockedSite() {
        screens.onHello(
            Protocol.MAJOR,
            Protocol.MINOR,
            false,
            SitePolicy.blocklist(Collections.singletonList("blocked.example")));
        ScreenState s = screen(0, 2, 2, "https://a.example/", 1);
        s.controller = ME;
        screens.onScreenState(s);
        tick();
        int id = first().hostScreenId();
        now[0] += UrlConvergence.NAVIGATE_SETTLE_MS;
        PageState refusedByServer = new PageState("https://a.example/too-long", "", false, true, false, false);
        for (int i = 0; i < 10; i++) {
            screens.onHostState(id, refusedByServer);
            now[0] += 1_000;
            tick();
        }
        assertEquals(1, server.count("setUrl"), "the server refused it (epoch unchanged): not resent");
        screens.onHostState(id, new PageState("https://blocked.example/", "", false, true, false, false));
        now[0] += 1_000;
        tick();
        assertEquals(1, server.count("setUrl"), "the site policy would refuse it");
        screens.onHostState(id, new PageState("https://a.example/next", "", false, true, false, false));
        assertEquals(1, server.count("setUrl 1 https://a.example/next"), "a new page is reported");
    }

    @Test
    void aHelloAfterAWorldChangeComesFromANewServerAndResetsTheClock() {
        hello();
        syncClock();
        assertTrue(
            screens.clock()
                .ready());
        hello(); // policy reload or kill switch: same server
        assertTrue(
            screens.clock()
                .ready(),
            "a re-sent hello keeps the clock");
        screens.teardownAll(); // proxy transfer: world unload, no disconnect
        hello();
        assertEquals(
            0,
            screens.clock()
                .samples(),
            "the old server's samples are gone");
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
        host.calls.clear();
        host.status = HostStatus.down(ReasonCode.HOST_EXITED);
        tick();
        assertEquals(0, first().hostScreenId());
        // Destroyed, not just forgotten: M2 replays every record it still holds (CREATE plus the last AUDIO), which
        // would leave an orphan browser playing at its last volume (review mutation survivor).
        int mute = host.indexOf("audio 1 muted");
        assertTrue(mute >= 0 && host.indexOf("destroy 1") > mute, host.calls.toString());
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
        assertEquals(0, server.count("control"));
        assertTrue(host.inputs.isEmpty());
        ScreenState mine = screen(0, 2, 2, "https://a.example/", 1);
        screens.onScreenState(mine);
        assertTrue(screens.click(cs, 0.25, 0.75, ScreenInput.BUTTON_LEFT, 0));
        assertEquals("control true", server.sent.get(server.sent.size() - 1), "a free screen is taken first");
        assertEquals(2, host.inputs.size(), "D-016: the click that takes a free screen is applied at once");
        mine.controller = ME;
        screens.onScreenState(mine);
        screens.click(cs, 0.25, 0.75, ScreenInput.BUTTON_LEFT, 0);
        assertEquals(InputEvent.Type.MOUSE_DOWN, host.inputs.get(2).type);
        assertEquals(InputEvent.Type.MOUSE_UP, host.inputs.get(3).type);
        assertEquals(320, host.inputs.get(2).x);
        assertEquals(2, host.inputs.get(2).clickCount, "double click");
        assertTrue(screens.can(cs, Rights.CHANGE_URL));
    }

    @Test
    void backAndForwardGoOnlyThroughTheServerSoTheControllerNavigatesOnce() {
        hello();
        ScreenState s = screen(0, 2, 2, "https://a.example/", 1);
        s.controller = ME;
        screens.onScreenState(s);
        tick();
        host.calls.clear();
        screens.nav(first(), "back");
        assertEquals(1, server.count("nav 1 back"));
        assertFalse(host.saw("action 1 back"), "M4 turns back into a URL change for everyone");
        screens.onUrl(s.key, "https://a.example/previous", 2); // the server's answer
        assertEquals(Collections.singletonList("navigate 1 https://a.example/previous"), host.calls, "exactly once");
        screens.nav(first(), "reload");
        assertEquals(1, server.count("nav 2 reload"));
        assertFalse(host.saw("action 1 reload"), "reload goes through the server for everyone");
        screens.nav(first(), "stop");
        assertTrue(host.saw("action 1 stop"), "the server ignores stop; the driver applies it");
    }

    /** Review high finding: the server echoes the controller's own URL report back to it with a new epoch. */
    @Test
    void theEchoOfOurOwnReportDoesNotReloadTheControllersPage() {
        hello();
        ScreenState s = screen(0, 2, 2, "https://www.youtube.com/", 1);
        s.controller = ME;
        screens.onScreenState(s);
        tick();
        int id = first().hostScreenId();
        now[0] += UrlConvergence.NAVIGATE_SETTLE_MS;
        screens.onHostState(id, new PageState("https://www.youtube.com/watch?v=abc", "", false, true, false, true));
        assertEquals(1, server.count("setUrl 1 https://www.youtube.com/watch?v=abc"));
        host.calls.clear();
        screens.onUrl(s.key, "https://www.youtube.com/watch?v=abc", 2);
        assertFalse(host.saw("navigate"), "the echo is adopted, not reloaded: " + host.calls);
        assertEquals(2, first().state().epoch);
        now[0] += 1_000;
        screens.onHostState(id, new PageState("https://www.youtube.com/watch?v=abc", "", false, true, false, true));
        assertEquals(1, server.count("setUrl"), "converged: nothing more to report");

        // The page moved on (autoplay) before the echo of the previous report arrived: still no reload back.
        now[0] += 1_000;
        screens.onHostState(id, new PageState("https://www.youtube.com/watch?v=next", "", false, true, false, true));
        assertEquals(1, server.count("setUrl 2 https://www.youtube.com/watch?v=next"));
        screens.onHostState(id, new PageState("https://www.youtube.com/watch?v=third", "", false, true, false, true));
        screens.onUrl(s.key, "https://www.youtube.com/watch?v=next", 3);
        assertFalse(host.saw("navigate"), host.calls.toString());

        // A reload of the same URL is a real navigation, even for the controller.
        screens.nav(first(), "reload");
        screens.onUrl(s.key, "https://www.youtube.com/watch?v=next", 4);
        assertTrue(host.saw("navigate 1 https://www.youtube.com/watch?v=next"), host.calls.toString());
    }

    @Test
    void followersAlwaysFollowTheServerUrl() {
        hello();
        ScreenState s = screen(0, 2, 2, "https://a.example/", 1);
        s.controller = OTHER;
        screens.onScreenState(s);
        tick();
        screens.onUrl(s.key, "https://a.example/next", 2);
        assertTrue(host.saw("navigate 1 https://a.example/next"));
        host.calls.clear();
        ScreenState snapshot = screen(0, 2, 2, "https://a.example/again", 3);
        snapshot.controller = OTHER;
        screens.onScreenState(snapshot); // a description packet that overtook the S2CUrl
        assertTrue(host.saw("navigate 1 https://a.example/again"));
    }

    /** Review high finding: {@code /webadmin clear} sets the URL to "" and must stop a page already playing. */
    @Test
    void anAdminClearStopsThePagePlayingOnEveryClient() {
        hello();
        ScreenState s = screen(0, 2, 2, "https://bad.example/offensive", 1);
        screens.onScreenState(s);
        tick();
        ClientScreen cs = first();
        RecordingPicture picture = new RecordingPicture();
        cs.texture = picture;
        cs.hasFrame = true;
        host.calls.clear();
        screens.onUrl(s.key, "", 2);
        int mute = host.indexOf("audio 1 muted");
        int destroy = host.indexOf("destroy 1");
        assertTrue(mute >= 0 && destroy > mute, "muted first, then destroyed: " + host.calls);
        assertFalse(host.saw("navigate"), "M2 refuses an empty URL and would keep the old page");
        assertTrue(picture.deleted, "the last frame of the cleared page is not drawn any more");
        assertFalse(cs.hasFrame);
        assertEquals(LangKeys.NO_URL, screens.placeholder(cs).key);
        tick();
        tick();
        assertEquals(1, host.created.size(), "no browser for a screen without a page");
        screens.onUrl(s.key, "https://good.example/", 3);
        tick();
        assertEquals(2, host.created.size());
        assertEquals("https://good.example/", host.created.get(1).url);
    }

    @Test
    void aFreshScreenWithoutAnAddressGetsNoBrowser() {
        hello();
        screens.onScreenState(screen(0, 2, 2, "", 0));
        tick();
        assertTrue(host.created.isEmpty());
        assertFalse(host.started, "nothing even starts the host");
        assertEquals(LangKeys.NO_URL, screens.placeholder(first()).key);
    }

    /** A GL-free texture that records its deletion. */
    static final class RecordingPicture implements com.akashiic.web.client.render.TextureTarget {

        boolean deleted;

        @Override
        public int width() {
            return 1;
        }

        @Override
        public int height() {
            return 1;
        }

        @Override
        public boolean isAllocated() {
            return !deleted;
        }

        @Override
        public void allocate(int width, int height) {}

        @Override
        public void upload(java.nio.ByteBuffer canvas, int canvasW, int canvasH,
            com.akashiic.web.client.render.Rect r) {}

        @Override
        public void bind() {}

        @Override
        public void delete() {
            deleted = true;
        }
    }

    /** Review medium finding: we must request exactly the size M2 lets the host paint (one frame per FRAME). */
    @Test
    void requestedPageSizesFitOneFrameLikeM2Clamps() {
        assertArrayEquals(new int[] { 3840, 1092 }, ClientScreens.pageSize(res(3840, 2160)));
        assertArrayEquals(new int[] { 2048, 2047 }, ClientScreens.pageSize(res(2048, 2048)));
        assertArrayEquals(new int[] { 2560, 1638 }, ClientScreens.pageSize(res(2560, 1640)));
        assertArrayEquals(new int[] { 1920, 1080 }, ClientScreens.pageSize(res(1920, 1080)));
        assertArrayEquals(new int[] { 1280, 720 }, ClientScreens.pageSize(res(0, 0)));
        java.util.Random r = new java.util.Random(3);
        for (int i = 0; i < 1_000; i++) {
            int[] p = ClientScreens.pageSize(res(r.nextInt(5000) - 100, r.nextInt(5000) - 100));
            assertTrue(com.akashiic.web.client.host.FrameCanvas.fits(p[0], p[1]), p[0] + "x" + p[1]);
        }
    }

    private static ScreenState res(int w, int h) {
        ScreenState s = screen(0, 2, 2, "https://a.example/", 1);
        s.resolutionW = w;
        s.resolutionH = h;
        return s;
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
    void localEventsUseTheirCodeAndNoticesCarryOneArgumentForTheDriverOnly() {
        List<Object[]> args = new ArrayList<>();
        screens = new ClientScreens(host, () -> server, () -> now[0], clientQueue::add, (k, a) -> {
            notices.add(k);
            args.add(a);
        }, 2, 1L);
        hello();
        ScreenState s = screen(0, 2, 2, "https://a.example/", 1);
        s.controller = ME;
        screens.onScreenState(s);
        tick();
        int id = first().hostScreenId();
        host.listeners.get(0)
            .onEvent(
                id,
                "{\"kind\":\"blocked-url\",\"detail\":{\"reason\":\"internal\",\"code\":\"URL_INTERNAL\","
                    + "\"host\":\"10.0.0.1\",\"via\":\"client\"}}");
        clientQueue.remove(0)
            .run();
        assertEquals(ReasonCode.URL_INTERNAL.langKey(), screens.placeholder(first()).detailKey);
        assertEquals(Collections.singletonList(ReasonCode.URL_INTERNAL.langKey()), notices);
        assertEquals(1, args.get(0).length, "reason texts take exactly one argument");
        assertEquals("10.0.0.1", args.get(0)[0]);
        screens.onHostEvent(id, HostMessages.event("{\"kind\":\"blocked-url\",\"detail\":{\"reason\":\"popup\"}}"));
        screens.onHostEvent(id, HostMessages.event("{\"kind\":\"blocked-url\",\"detail\":{\"reason\":\"popup\"}}"));
        assertEquals(2, notices.size(), "the same notice at most once per " + ClientScreens.NOTICE_INTERVAL_MS + " ms");
        assertEquals(ReasonCode.POPUP_BLOCKED.langKey(), notices.get(1));
        assertEquals(1, args.get(1).length);

        ScreenState follower = screen(0, 2, 2, "https://a.example/", 1);
        follower.controller = OTHER;
        screens.onScreenState(follower);
        now[0] += ClientScreens.NOTICE_INTERVAL_MS;
        screens
            .onHostEvent(id, HostMessages.event("{\"kind\":\"permission-denied\",\"detail\":{\"permission\":\"x\"}}"));
        assertEquals(2, notices.size(), "a follower is not told about what the controller's page tried");
    }

    @Test
    void hostLevelEventsGoToTheLogAndTheDoctor() {
        hello();
        screens.onScreenState(screen(0, 2, 2, "https://a.example/", 1));
        tick();
        screens.onHostEvent(
            0,
            HostMessages.event("{\"kind\":\"load-error\",\"detail\":{\"description\":\"host-wide hiccup\"}}"));
        assertNull(first().failure(), "another host-level event is not a screen failure");
        // W3-0: the host fell back to deny-all, so every page it shows is blocked; the screen says why.
        screens.onHostEvent(
            0,
            HostMessages
                .event("{\"kind\":\"policy-error\",\"detail\":{\"error\":\"bad mode\",\"applied\":\"deny-all\"}}"));
        assertEquals(ReasonCode.POLICY_INVALID, first().failure());
        assertEquals(ReasonCode.POLICY_INVALID.langKey(), screens.placeholder(first()).detailKey);
        assertTrue(notices.isEmpty(), "no chat notice");
        String doctor = String.join("\n", screens.diagnostics(now[0]));
        assertTrue(
            doctor.contains("host event:") && doctor.contains("policy-error")
                && doctor.contains("bad mode")
                && doctor.contains("POLICY_INVALID"),
            doctor);
    }

    @Test
    void responsiveClearsUnresponsiveAndACrashedPageIsRecreated() {
        hello();
        screens.onScreenState(screen(0, 2, 2, "https://a.example/", 1));
        tick();
        int id = first().hostScreenId();
        screens.onHostEvent(id, HostMessages.event("{\"kind\":\"unresponsive\",\"detail\":{}}"));
        assertEquals(ReasonCode.PAGE_UNRESPONSIVE, first().failure());
        screens.onHostEvent(id, HostMessages.event("{\"kind\":\"responsive\",\"detail\":{}}"));
        assertNull(first().failure(), "a static page never repaints, so the event must clear the banner");

        screens.onHostEvent(id, HostMessages.event("{\"kind\":\"renderer-gone\",\"detail\":{\"reason\":\"crashed\"}}"));
        assertEquals(ReasonCode.PAGE_CRASHED, first().failure());
        tick();
        assertEquals(1, host.created.size(), "not at once");
        now[0] += ClientScreens.RECREATE_BASE_MS;
        tick();
        int mute = host.calls.lastIndexOf("audio 1 muted 0.00");
        assertTrue(mute >= 0 && host.indexOf("destroy 1") > mute, host.calls.toString());
        assertEquals(2, host.created.size(), "a fresh browser for the same page");
        assertNull(first().failure());
        int second = first().hostScreenId();
        screens.onHostEvent(second, HostMessages.event("{\"kind\":\"renderer-gone\",\"detail\":{}}"));
        now[0] += ClientScreens.RECREATE_BASE_MS;
        tick();
        assertEquals(2, host.created.size(), "the second crash of the same page waits twice as long");
        now[0] += ClientScreens.RECREATE_BASE_MS;
        tick();
        assertEquals(3, host.created.size());
    }

    @Test
    void noBrowserBeforeTheSitePolicyIsKnownAndThePolicyGoesFirst() {
        screens.onHello(Protocol.MAJOR, Protocol.MINOR, false, null);
        screens.onScreenState(screen(0, 2, 2, "https://a.example/", 1));
        tick();
        assertTrue(host.created.isEmpty(), "M2 denies every URL until policy(): the page would start blank");
        hello();
        tick();
        assertEquals(1, host.created.size());
        assertTrue(host.indexOf("policy BLOCKLIST") < host.indexOf("create 1"), host.calls.toString());
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
