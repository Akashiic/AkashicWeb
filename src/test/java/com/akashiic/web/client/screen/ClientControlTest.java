package com.akashiic.web.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akashiic.web.client.host.CreateSpec;
import com.akashiic.web.client.host.InputEvent;
import com.akashiic.web.common.Rights;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenMode;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.common.net.ScreenInput;
import com.akashiic.web.protocol.Protocol;

/**
 * D-016 on the client: input stays local and reaches our own browser only while we drive the page; the identity is
 * the server-side UUID from S2CHello; the first gesture on a free screen takes it and is applied at once; the lock is
 * kept alive while we interact and handed back when we leave.
 */
class ClientControlTest {

    /** The UUID Minecraft reports for the local player (offline mode: differs from the server's). */
    private static final UUID SESSION = new UUID(10, 10);
    /** The local player as the server knows it (S2CHello.playerUuid). */
    private static final UUID ME = new UUID(77, 77);
    private static final UUID OTHER = new UUID(20, 20);
    private static final int SOUTH = 3;

    private FakeHost host;
    private RecordingServer server;
    private long[] now;
    private ClientScreens screens;
    private ScreenKey key;

    @BeforeEach
    void setUp() {
        host = new FakeHost();
        server = new RecordingServer();
        now = new long[] { 1_000 };
        screens = new ClientScreens(
            host,
            () -> server,
            () -> now[0],
            Runnable::run,
            ClientScreens.Notifier.NONE,
            2,
            1L);
        screens
            .onHello(Protocol.MAJOR, Protocol.MINOR, false, SitePolicy.blocklist(Collections.<String>emptyList()), ME);
        key = new ScreenKey(0, 0, 64, 0, SOUTH);
    }

    private ScreenState state(UUID controller) {
        ScreenState s = new ScreenState();
        s.key = key;
        s.widthBlocks = 2;
        s.heightBlocks = 2;
        s.resolutionW = 1280;
        s.resolutionH = 720;
        s.url = "https://a.example/";
        s.epoch = 1;
        s.owner = ME;
        s.ownerName = "Gabriel";
        s.controller = controller;
        s.controllerName = controller == null ? "" : controller.equals(ME) ? "Gabriel" : "Visitor";
        return s;
    }

    private void tick(double z) {
        screens.tick(new PlayerView(0, 1, 65, z, 0, 0, -1, SESSION, false, 1f, 1f));
        now[0] += 50;
    }

    private void tick() {
        tick(5);
    }

    private ClientScreen screen() {
        return screens.get(key);
    }

    @Test
    void theFirstClickOnAFreeScreenTakesItAndIsAppliedAtOnce() {
        screens.onScreenState(state(null));
        tick();
        assertTrue(screens.click(screen(), 0.5, 0.5, ScreenInput.BUTTON_LEFT, 0));
        assertEquals(1, server.count("control true"));
        assertEquals(2, host.inputs.size(), "down and up reach the page now, not after a round trip");
        screens.click(screen(), 0.5, 0.5, ScreenInput.BUTTON_LEFT, 0);
        assertEquals(1, server.count("control true"), "no second take while the first is pending");
        assertEquals(4, host.inputs.size());
        screens.onScreenState(state(ME)); // the server granted it
        now[0] += ClientScreens.TAKE_PENDING_MS;
        tick();
        screens.type(screen(), ScreenInput.chars("a"));
        assertEquals(5, host.inputs.size(), "ours now, by the hello's UUID");
        assertFalse(host.saw("navigate"), "nothing to undo");
    }

    /** System.nanoTime's origin is arbitrary: a negative clock must not read as "a take was just sent". */
    @Test
    void aNegativeMonotonicClockStillTakesAndReleases() {
        now[0] = -9_000_000_000L;
        screens.onScreenState(state(null));
        tick();
        screens.click(screen(), 0.5, 0.5, ScreenInput.BUTTON_LEFT, 0);
        assertEquals(1, server.count("control true"));
        assertEquals(2, host.inputs.size());
        screens.onScreenState(state(ME));
        tick(40);
        assertEquals(1, server.count("control false"));
    }

    @Test
    void aTakeGivenToSomeoneElseStopsInputAndBringsOurPageBack() {
        screens.onScreenState(state(null));
        tick();
        int id = screen().hostScreenId();
        screens.click(screen(), 0.2, 0.2, ScreenInput.BUTTON_LEFT, 0);
        // Our optimistic click followed a link before the server answered.
        screens.onHostState(id, new PageState("https://a.example/clicked", "", false, true, false, false));
        screens.onScreenState(state(OTHER));
        tick();
        assertTrue(host.saw("navigate 1 https://a.example/"), "converged back to the server's page: " + host.calls);
        int before = host.inputs.size();
        screens.click(screen(), 0.2, 0.2, ScreenInput.BUTTON_LEFT, 0);
        screens.type(screen(), ScreenInput.chars("x"));
        assertEquals(before, host.inputs.size(), "someone else controls: we only watch");
        assertEquals(1, server.count("control true"), "the lock is not ours to take");
        assertFalse(
            server.sent.stream()
                .anyMatch(m -> m.startsWith("pointer") || m.startsWith("key")),
            "input never goes to the server");
    }

    @Test
    void anUnansweredTakeExpiresAndIsRetriedAtTheServersRate() {
        screens.onScreenState(state(null));
        tick();
        screens.click(screen(), 0.5, 0.5, ScreenInput.BUTTON_LEFT, 0);
        now[0] += ClientScreens.TAKE_PENDING_MS;
        tick();
        assertFalse(screens.drivesInput(screen()));
        int before = host.inputs.size();
        screens.wheel(screen(), 0.5, 0.5, 1f, 0);
        assertEquals(2, server.count("control true"), "asked again");
        assertEquals(before + 1, host.inputs.size(), "and applied again while pending");
        screens.wheel(screen(), 0.5, 0.5, 1f, 0);
        assertEquals(2, server.count("control true"), "at most one take per " + ClientScreens.TAKE_RETRY_MS + " ms");
    }

    @Test
    void theControllerReleasesWhenItWalksAwayOrClosesTheKeyboardForGood() {
        screens.onScreenState(state(ME));
        tick();
        tick(40); // beyond the server's reach
        assertEquals(1, server.count("control false"));
        tick(40);
        assertEquals(1, server.count("control false"), "not every tick");

        setUp();
        screens.onScreenState(state(ME));
        tick();
        screens.leaveSoon(screen()); // keyboard closed
        now[0] += ClientScreens.LEAVE_GRACE_MS / 2;
        screens.click(screen(), 0.5, 0.5, ScreenInput.BUTTON_LEFT, 0); // clicking a search result in the world
        now[0] += ClientScreens.LEAVE_GRACE_MS;
        tick();
        assertEquals(0, server.count("control false"), "still using the screen");
        screens.leaveSoon(screen());
        now[0] += ClientScreens.LEAVE_GRACE_MS;
        tick();
        assertEquals(1, server.count("control false"), "done with it");
    }

    @Test
    void theControllerKeepsItsLockAliveWhileItInteracts() {
        screens.onScreenState(state(ME));
        tick();
        for (int i = 0; i < 40; i++) {
            screens.type(screen(), ScreenInput.chars("a"));
            now[0] += 500;
        }
        // 20 s of typing: a take at most every 5 s, well inside the server's idle limit (60 s).
        assertTrue(server.count("control true") >= 3 && server.count("control true") <= 5, server.sent.toString());
    }

    /** Dev-client run 2026-10-13: the owner had no rights because the client compared its session UUID. */
    @Test
    void ownerFriendAndControllerChecksUseTheHelloUuid() {
        ScreenState s = state(ME);
        screens.onScreenState(s);
        tick();
        assertTrue(screens.can(screen(), Rights.CHANGE_URL), "owner by the server-side UUID");
        assertTrue(screens.isController(screen()));
        ScreenState friendOf = state(null);
        friendOf.owner = OTHER;
        friendOf.friends.add(ME);
        friendOf.friendRights = Rights.CLICK_TYPE;
        friendOf.otherRights = 0;
        screens.onScreenState(friendOf);
        assertTrue(screens.can(screen(), Rights.CLICK_TYPE), "friend by the server-side UUID");
        assertFalse(screens.can(screen(), Rights.CHANGE_URL));
        friendOf.friends.clear();
        friendOf.friends.add(SESSION);
        screens.onScreenState(friendOf);
        assertFalse(screens.can(screen(), Rights.CLICK_TYPE), "the session UUID is not who the server knows");

        // An older server sends no UUID: the session UUID is the fallback.
        screens.onDisconnect();
        screens.onHello(Protocol.MAJOR, Protocol.MINOR, false, SitePolicy.blocklist(Collections.<String>emptyList()));
        ScreenState old = state(SESSION);
        old.owner = SESSION;
        screens.onScreenState(old);
        tick();
        assertTrue(screens.isController(screen()));
    }

    @Test
    void theControllerNameAndEveryOtherFieldSurviveTheClientCopy() {
        ScreenState s = state(OTHER);
        s.friendNames.put(OTHER, "Visitor");
        screens.onScreenState(s);
        assertEquals("Visitor", screen().state().controllerName);
        assertEquals("Visitor", screen().state().friendNames.get(OTHER));
        assertEquals("Visitor", LocalRights.controllerDisplayName(screen().state()));
        s.controllerName = "§kHidden";
        assertEquals("Hidden", LocalRights.controllerDisplayName(s), "formatting codes are dropped");
        s.controllerName = "";
        assertEquals("00000000", LocalRights.controllerDisplayName(s), "an older server: a UUID prefix");
        s.controller = ME;
        assertEquals("Gabriel", LocalRights.controllerDisplayName(s), "the owner by name");
        s.controller = null;
        assertEquals("", LocalRights.controllerDisplayName(s));
    }

    @Test
    void hoverMovesOnlyOurOwnPageAndOnlyWhenThePixelChanges() {
        screens.onScreenState(state(OTHER));
        tick();
        screens.pointerMove(screen(), 0.5, 0.5);
        assertTrue(host.inputs.isEmpty(), "hovering someone else's page does nothing");
        assertEquals(0, server.count("control"), "and never takes the lock");
        screens.onScreenState(state(ME));
        screens.pointerMove(screen(), 0.5, 0.5);
        screens.pointerMove(screen(), 0.5000001, 0.5);
        assertEquals(1, host.inputs.size(), "same pixel: one move");
        assertEquals(InputEvent.Type.MOUSE_MOVE, host.inputs.get(0).type);
        assertEquals(0, server.count("control"), "hover is not a keepalive");
        // A drag: press, move, release.
        screens.pointerDown(screen(), 0.5, 0.5, ScreenInput.BUTTON_LEFT, 0);
        screens.pointerMove(screen(), 0.7, 0.5);
        screens.pointerUp(screen(), 0.7, 0.5, ScreenInput.BUTTON_LEFT, 0);
        List<InputEvent.Type> types = new ArrayList<>();
        for (InputEvent e : host.inputs) types.add(e.type);
        assertEquals(
            java.util.Arrays.asList(
                InputEvent.Type.MOUSE_MOVE,
                InputEvent.Type.MOUSE_DOWN,
                InputEvent.Type.MOUSE_MOVE,
                InputEvent.Type.MOUSE_UP),
            types);
        assertEquals(896, host.inputs.get(3).x);
    }

    @Test
    void onlyTheOwnerOfAPrivateScreenGetsThePersistentProfile() {
        ScreenState mine = state(null);
        mine.mode = ScreenMode.PRIVATE;
        screens.onScreenState(mine);
        tick();
        assertEquals(CreateSpec.Session.PRIVATE, host.created.get(0).session);
        ScreenState theirs = state(null);
        theirs.mode = ScreenMode.PRIVATE;
        theirs.owner = OTHER;
        theirs.otherRights = Rights.CLICK_TYPE;
        screens.onScreenState(theirs);
        assertTrue(host.saw("destroy 1"), "a different session kind needs a new browser");
        tick();
        assertEquals(CreateSpec.Session.SHARED, host.created.get(1).session, "a visitor never browses with logins");
        assertTrue(screens.click(screen(), 0.5, 0.5, ScreenInput.BUTTON_LEFT, 0), "PRIVATE: everyone drives their own");
        assertEquals(0, server.count("control"), "and there is no lock");
    }
}
