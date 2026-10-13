package com.akashiic.web.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.Rights;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.protocol.Protocol;

/** ServerSession, LocalRights and ClickCounter. */
class SmallPartsTest {

    private static final UUID OWNER = new UUID(1, 1);
    private static final UUID FRIEND = new UUID(2, 2);
    private static final UUID STRANGER = new UUID(3, 3);

    @Test
    void sessionAllowsBrowsersOnlyAfterACompatibleHelloWithoutKillSwitch() {
        ServerSession s = new ServerSession();
        assertFalse(s.allowsBrowsers());
        assertNull(s.policy());
        SitePolicy p = SitePolicy.blocklist(Collections.singletonList("bad.example"));
        s.onHello(Protocol.MAJOR, 3, false, p);
        assertTrue(s.allowsBrowsers());
        assertEquals(p, s.policy());
        s.onHello(Protocol.MAJOR, 3, true, p);
        assertFalse(s.allowsBrowsers(), "kill switch");
        s.onHello(Protocol.MAJOR + 1, 0, false, p);
        assertFalse(s.allowsBrowsers(), "incompatible major");
        assertFalse(s.protocolOk());
        s.reset();
        assertFalse(s.helloReceived());
        assertTrue(s.protocolOk());
    }

    private static ScreenState screen() {
        ScreenState s = new ScreenState();
        s.key = new ScreenKey(0, 1, 2, 3, 3);
        s.owner = OWNER;
        s.friends.add(FRIEND);
        return s;
    }

    @Test
    void rightsFollowOwnerFriendsAndOthers() {
        ScreenState s = screen();
        assertEquals(Rights.ALL, LocalRights.rightsFor(s, OWNER));
        assertTrue(LocalRights.can(s, FRIEND, Rights.CLICK_TYPE));
        assertTrue(LocalRights.can(s, FRIEND, Rights.CHANGE_URL));
        assertFalse(LocalRights.can(s, FRIEND, Rights.MANAGE_RIGHTS));
        assertFalse(LocalRights.can(s, STRANGER, Rights.CLICK_TYPE), "visitors only watch by default");
        s.otherRights = Rights.CLICK_TYPE;
        assertTrue(LocalRights.can(s, STRANGER, Rights.CLICK_TYPE));
        s.locked = true;
        assertFalse(LocalRights.can(s, FRIEND, Rights.CLICK_TYPE));
        assertTrue(LocalRights.can(s, OWNER, Rights.CLICK_TYPE));
        assertEquals(0, LocalRights.rightsFor(s, null));
        assertFalse(LocalRights.isController(s, OWNER));
        s.controller = OWNER;
        assertTrue(LocalRights.isController(s, OWNER));
    }

    @Test
    void clicksCountUpOnTheSameSpotQuickly() {
        ClickCounter c = new ClickCounter();
        Object a = "a", b = "b";
        assertEquals(1, c.click(a, 0.5, 0.5, 0));
        assertEquals(2, c.click(a, 0.505, 0.5, 200));
        assertEquals(3, c.click(a, 0.5, 0.505, 400));
        assertEquals(3, c.click(a, 0.5, 0.5, 600), "capped at triple");
        assertEquals(1, c.click(a, 0.5, 0.5, 1_200), "too slow");
        assertEquals(1, c.click(a, 0.6, 0.5, 1_300), "moved");
        assertEquals(1, c.click(b, 0.6, 0.5, 1_400), "another screen");
    }
}
