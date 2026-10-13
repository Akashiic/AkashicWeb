package com.akashiic.web.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;

/** The in-world pointer's pure parts: the long-reach eye ray and the press that owns its release. */
class PointerTest {

    private static final int SOUTH = 3;

    private static ScreenState screen(int x, int z, int w, int h) {
        ScreenState s = new ScreenState();
        s.key = new ScreenKey(0, x, 64, z, SOUTH);
        s.widthBlocks = w;
        s.heightBlocks = h;
        s.resolutionW = 1280;
        s.resolutionH = 720;
        s.url = "https://a.example/";
        s.owner = new UUID(1, 1);
        return s;
    }

    private static ClientScreens screens() {
        return new ClientScreens(
            new FakeHost(),
            RecordingServer::new,
            () -> 0L,
            Runnable::run,
            ClientScreens.Notifier.NONE,
            2,
            1L);
    }

    /** Review medium finding: vanilla's crosshair stops at 4.5 blocks; the top of a tall screen was unreachable. */
    @Test
    void theTopOfATallScreenIsReachableFromTheGround() {
        ClientScreens screens = screens();
        screens.onScreenState(screen(0, 0, 16, 9)); // a telão: x 0..16, y 64..73, face plane z = 1
        // Standing 10 blocks in front at eye height 65.6, looking up at the top row.
        double ex = 8, ey = 65.6, ez = 11;
        double tx = 8, ty = 72.5, tz = 1;
        double[] d = { tx - ex, ty - ey, tz - ez };
        ClientScreens.Hit hit = screens.aim(0, ex, ey, ez, d[0], d[1], d[2], ClientScreens.POINTER_REACH);
        assertNotNull(hit);
        assertTrue(hit.distance > 4.5 && hit.distance < ClientScreens.POINTER_REACH, "distance " + hit.distance);
        assertTrue(hit.v < 0.1, "near the top: v=" + hit.v);
        assertEquals(0.5, hit.u, 0.01);
        assertNull(
            screens.aim(0, 8, 65.6, 40, 0, 0, -1, ClientScreens.POINTER_REACH),
            "39 blocks away: beyond the reach");
        assertNull(screens.aim(0, 8, 65.6, -5, 0, 0, 1, ClientScreens.POINTER_REACH), "from behind the screen");
        assertNull(screens.aim(-1, 8, 65.6, 11, 0, 0, -1, ClientScreens.POINTER_REACH), "another dimension");
    }

    @Test
    void theNearestPictureWins() {
        ClientScreens screens = screens();
        screens.onScreenState(screen(0, 0, 2, 2));
        screens.onScreenState(screen(0, 5, 2, 2)); // in front of the first one, same line of sight
        ClientScreens.Hit hit = screens.aim(0, 1, 65, 10, 0, 0, -1, ClientScreens.POINTER_REACH);
        assertNotNull(hit);
        assertEquals(5, hit.screen.key.z);
        assertEquals(4.0, hit.distance, 1e-9);
    }

    @Test
    void aPressOwnsItsReleaseWhereverTheCrosshairGoes() {
        ClientScreens screens = screens();
        screens.onScreenState(screen(0, 0, 2, 2));
        screens.onScreenState(screen(10, 0, 2, 2));
        ClientScreen a = screens.get(new ScreenKey(0, 0, 64, 0, SOUTH));
        ClientScreen b = screens.get(new ScreenKey(0, 10, 64, 0, SOUTH));
        PointerPress p = new PointerPress();
        assertFalse(p.isReleaseOf(0, false), "no press taken: a release is vanilla's");
        p.begin(a, 0, 0.2, 0.3);
        assertFalse(p.isReleaseOf(1, false), "another button");
        assertFalse(p.isReleaseOf(0, true), "a press, not a release");
        assertTrue(p.isReleaseOf(0, false));
        assertTrue(p.follow(new ClientScreens.Hit(a, 0.6, 0.7)));
        assertEquals(0.6, p.u(), 0);
        assertFalse(p.follow(new ClientScreens.Hit(b, 0.9, 0.9)), "dragging off the page keeps the last position");
        assertFalse(p.follow(null));
        assertEquals(0.6, p.u(), 0);
        assertSame(a, p.screen());
        p.end();
        assertFalse(p.active());
    }
}
