package com.akashiic.web.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.akashiic.web.client.host.BrowserHost;
import com.akashiic.web.client.host.CreateSpec;
import com.akashiic.web.client.host.FrameRing;
import com.akashiic.web.client.host.HostLaunchConfig;
import com.akashiic.web.client.host.HostListener;
import com.akashiic.web.client.host.HostStatus;
import com.akashiic.web.client.host.InputEvent;
import com.akashiic.web.client.host.SyncCommand;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.client.screen.ClientServices;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.SitePolicy;

/** The review's blocker: the game must wire M3's locator and M2's host; and the glue between them. */
class ClientWiringTest {

    private static final EngineState READY = new EngineState(EngineState.Kind.READY, 1f, null);

    @AfterEach
    void unwire() {
        ClientServices.setEngineLocator(null);
        ClientServices.setHostFactory(null);
        ClientServices.setConsentOpener(null);
        ClientServices.setConsentPostponed(false);
    }

    @Test
    void wiringPublishesTheLocatorTheHostFactoryAndTheConsentOpener() {
        FakeLocator locator = new FakeLocator();
        ClientWiring w = new ClientWiring(locator, new File("/games/forbidden"));
        w.publish();
        assertSame(locator, ClientServices.engineLocator());
        assertNotNull(ClientServices.consentOpener());
        assertTrue(ClientServices.hostAvailable(), "a host factory is wired (it is only called once READY)");
        assertNull(ClientServices.hostIfStarted(), "nothing starts at game start");
    }

    @Test
    void theHostLogsUnderTheGameFolderAndKeepsStateInTheEngineProfile() {
        FakeLocator locator = new FakeLocator();
        locator.userData = new File("/home/p/AkashicWeb/profiles/0123456789ab");
        ClientWiring w = new ClientWiring(locator, new File("/games/forbidden"));
        HostLaunchConfig c = w.launchConfig();
        assertEquals(new File("/games/forbidden/logs/akashicweb"), c.logDir);
        assertEquals(locator.userData, c.stateDir, "never the instance root");
        assertEquals(locator.userData, c.executable.userDataDir());
        assertNull(c.executable.command(), "no command until the engine is READY with its executable");
        assertEquals(ReasonCode.ENGINE_ABSENT, c.executable.unavailableReason());
    }

    @Test
    void consentDecisionsReachTheSession() {
        FakeLocator locator = new FakeLocator();
        ClientWiring w = new ClientWiring(locator, new File("/games/forbidden"));
        w.consent()
            .press(com.akashiic.web.client.gui.ConsentController.Button.NOT_NOW);
        assertTrue(ClientServices.consentPostponed());
        assertEquals(Arrays.asList("cancelInstall"), locator.calls);
    }

    @Test
    void aLaunchFailureVerifiesTheEngineOnceWithTheHostsDiagnostics() {
        FakeLocator locator = new FakeLocator();
        locator.state = READY;
        ClientWiring w = new ClientWiring(locator, new File("/games/forbidden"));
        StubHost host = new StubHost();
        host.status = HostStatus.down(ReasonCode.HOST_LAUNCH_FAILED);
        w.tick(locator.state, host);
        w.tick(locator.state, host);
        assertEquals(Arrays.asList("verify CreateProcess error=4551"), locator.calls);
        assertTrue(host.calls.isEmpty(), "an intact engine: the supervisor's backoff decides");
        // The installer rewrote something and is READY again: the host relaunches now.
        locator.state = new EngineState(EngineState.Kind.READY, 1f, null);
        w.tick(locator.state, host);
        assertEquals(Arrays.asList("reenable"), host.calls);
    }

    /**
     * The installer gets the host's last failure detail, the launch error itself, not the whole diagnostics (status,
     * paths and per-screen lines, where an unrelated "error=NNNN" could be misread as a Windows block).
     */
    @Test
    void aLaunchFailureVerifiesTheEngineWithTheHostsLastFailureDetail() {
        FakeLocator locator = new FakeLocator();
        locator.state = READY;
        ClientWiring w = new ClientWiring(locator, new File("/games/forbidden"));
        StubHost host = new StubHost();
        host.detail = "LAUNCH_FAILED: java.io.IOException: Cannot run program \"AkashicWebHost.exe\": "
            + "CreateProcess error=225, Operation did not complete successfully";
        host.status = HostStatus.disabled(ReasonCode.HOST_LAUNCH_FAILED);
        w.tick(locator.state, host);
        assertEquals(Arrays.asList("verify " + host.detail), locator.calls);
    }

    @Test
    void watcherRules() {
        ServiceWatcher sw = new ServiceWatcher();
        EngineState consent = new EngineState(EngineState.Kind.CONSENT_NEEDED, 0, null);
        EngineState downloading = new EngineState(EngineState.Kind.DOWNLOADING, 0.5f, null);
        assertTrue(
            sw.update(consent, null)
                .isEmpty());
        assertTrue(
            sw.update(downloading, null)
                .isEmpty());
        assertTrue(
            sw.update(READY, null)
                .isEmpty(),
            "no host yet: the factory creates it when needed");
        HostStatus exited = HostStatus.down(ReasonCode.HOST_EXITED);
        assertEquals(EnumSet.of(ServiceWatcher.Action.VERIFY), sw.update(READY, exited));
        assertTrue(
            sw.update(READY, exited)
                .isEmpty(),
            "once per episode");
        assertTrue(
            sw.update(READY, HostStatus.READY)
                .isEmpty());
        assertEquals(EnumSet.of(ServiceWatcher.Action.VERIFY), sw.update(READY, exited), "a new episode");
        HostStatus crashLoop = HostStatus.disabled(ReasonCode.HOST_CRASH_LOOP);
        EngineState repaired = new EngineState(EngineState.Kind.READY, 1f, null);
        assertEquals(EnumSet.of(ServiceWatcher.Action.REENABLE), sw.update(repaired, crashLoop));
        assertTrue(
            sw.update(repaired, crashLoop)
                .isEmpty(),
            "the same READY: no second reenable");
        assertTrue(
            sw.update(new EngineState(EngineState.Kind.READY, 1f, null), HostStatus.READY)
                .isEmpty(),
            "a running host is left alone");
        assertTrue(
            sw.update(new EngineState(EngineState.Kind.FAILED, 0, ReasonCode.HASH_MISMATCH), exited)
                .isEmpty(),
            "no verify of an engine that is not READY");
    }

    /**
     * A BrowserHost that records reenable() and reports a launch error in its diagnostics, and {@link #detail} as its
     * last failure detail ("" by default, so the diagnostics are the fallback).
     */
    static class StubHost implements BrowserHost {

        final List<String> calls = new ArrayList<>();
        HostStatus status = HostStatus.READY;
        String detail = "";

        @Override
        public String lastFailureDetail() {
            return detail;
        }

        @Override
        public HostStatus status() {
            return status;
        }

        @Override
        public List<String> diagnostics() {
            return Arrays.asList("CreateProcess error=4551");
        }

        @Override
        public void reenable() {
            calls.add("reenable");
        }

        @Override
        public int createScreen(CreateSpec spec) {
            return 1;
        }

        @Override
        public void destroyScreen(int screenId) {}

        @Override
        public void navigate(int screenId, String url) {}

        @Override
        public void action(int screenId, String action) {}

        @Override
        public void input(int screenId, InputEvent event) {}

        @Override
        public void audio(int screenId, boolean muted, float volume) {}

        @Override
        public void render(int screenId, int fps, boolean painting) {}

        @Override
        public void resize(int screenId, int width, int height) {}

        @Override
        public void sync(int screenId, SyncCommand cmd) {}

        @Override
        public void policy(SitePolicy policy) {}

        @Override
        public FrameRing frames(int screenId) {
            return null;
        }

        @Override
        public void addListener(HostListener listener) {}

        @Override
        public void removeListener(HostListener listener) {}

        @Override
        public void shutdown() {}
    }
}
