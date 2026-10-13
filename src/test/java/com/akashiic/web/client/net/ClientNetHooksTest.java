package com.akashiic.web.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akashiic.web.client.host.BrowserHost;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.client.screen.ClientScreens;
import com.akashiic.web.client.screen.HostAccess;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.common.net.ServerBound;
import com.akashiic.web.protocol.Protocol;

class ClientNetHooksTest {

    private static final HostAccess NO_HOST = new HostAccess() {

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public BrowserHost started() {
            return null;
        }

        @Override
        public BrowserHost obtain() {
            return null;
        }

        @Override
        public EngineState engine() {
            return null;
        }
    };

    private final List<Runnable> queue = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();
    private final long[] now = { 5_000 };
    private final ClientScreens screens = new ClientScreens(
        NO_HOST,
        () -> ServerBound.NOOP,
        () -> now[0],
        queue::add,
        ClientScreens.Notifier.NONE,
        2,
        9L);
    private final ClientNetHooks hooks = new ClientNetHooks(
        screens,
        () -> now[0],
        queue::add,
        (key, args) -> notices.add(key + " " + args.length));

    private void drain() {
        while (!queue.isEmpty()) queue.remove(0)
            .run();
    }

    @Test
    void packetsHopToTheClientThreadBeforeTouchingScreens() {
        hooks.onHello(Protocol.MAJOR, Protocol.MINOR, false, SitePolicy.blocklist(Collections.<String>emptyList()), 1);
        ScreenState s = new ScreenState();
        s.key = new ScreenKey(0, 1, 2, 3, 3);
        s.widthBlocks = 2;
        s.heightBlocks = 2;
        s.url = "https://a.example/";
        hooks.onScreenState(s);
        assertFalse(
            screens.session()
                .helloReceived(),
            "nothing applied on the Netty thread");
        assertTrue(
            screens.all()
                .isEmpty());
        assertEquals(2, queue.size());
        drain();
        assertTrue(
            screens.session()
                .helloReceived());
        assertEquals(
            1,
            screens.all()
                .size());
        hooks.onUrl(s.key, "https://b.example/", 1);
        drain();
        assertEquals(
            "https://b.example/",
            screens.get(s.key)
                .state().url);
        hooks.onScreenRemoved(s.key);
        drain();
        assertTrue(
            screens.all()
                .isEmpty());
    }

    @Test
    void pongsAreStampedOnArrivalWithoutQueueing() {
        long nonce = screens.clock()
            .pollPing(now[0]);
        now[0] += 20;
        hooks.onClockPong(nonce, now[0] - 20, 100_000);
        assertTrue(queue.isEmpty());
        assertEquals(
            1,
            screens.clock()
                .samples());
        assertEquals(
            20,
            screens.clock()
                .bestRttMs(),
            1e-9);
    }

    @Test
    void noticesAreTranslatedByCodeAndUnknownCodesIgnored() {
        hooks.onNotice(ReasonCode.RATE_LIMITED, new String[] { "x" });
        hooks.onNotice(null, new String[] { "y" });
        drain();
        assertEquals(Collections.singletonList("akashicweb.reason.rate_limited 1"), notices);
    }

    @Test
    void nullsFromTheWireAreIgnored() {
        hooks.onScreenState(null);
        hooks.onScreenRemoved(null);
        hooks.onUrl(null, "x", 1);
        hooks.onInput(null, 1, null);
        hooks.onMediaClock(null, 1, 1, 1, false, 1);
        assertTrue(queue.isEmpty());
    }
}
