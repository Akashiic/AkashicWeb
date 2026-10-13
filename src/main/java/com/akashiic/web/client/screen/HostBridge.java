package com.akashiic.web.client.screen;

import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

import com.akashiic.web.client.host.HostListener;
import com.akashiic.web.client.host.HostStatus;
import com.akashiic.web.client.sync.LocalMedia;
import com.akashiic.web.client.sync.ServerClock;

/**
 * Host callbacks arrive on M2's reader thread. This bridge does only thread-safe work there (parse the JSON, stamp a
 * MEDIA sample with its arrival on our clock and on the server clock) and hops everything else to the client thread,
 * where {@link ClientScreens} lives. Stamping here rather than after the hop keeps the controller's samples free of the
 * up-to-one-tick queue delay.
 */
final class HostBridge implements HostListener {

    private final ClientScreens screens;
    private final ServerClock clock;
    private final LongSupplier timeMs;
    private final Executor clientThread;

    HostBridge(ClientScreens screens, ServerClock clock, LongSupplier timeMs, Executor clientThread) {
        this.screens = screens;
        this.clock = clock;
        this.timeMs = timeMs;
        this.clientThread = clientThread;
    }

    @Override
    public void onStatus(HostStatus status) {
        if (status != null) clientThread.execute(() -> screens.onHostStatus(status));
    }

    @Override
    public void onState(int screenId, String json) {
        PageState page = HostMessages.state(json);
        if (page != null) clientThread.execute(() -> screens.onHostState(screenId, page));
    }

    @Override
    public void onMedia(int screenId, String json) {
        long now = timeMs.getAsLong();
        LocalMedia media = HostMessages.media(json, now, clock.serverNowMs(now));
        if (media != null) clientThread.execute(() -> screens.onHostMedia(screenId, media));
    }

    /** Screen EVENTs and host-level ones (screen 0, e.g. policy-error); M2's two local EVENTs come the same way. */
    @Override
    public void onEvent(int screenId, String json) {
        HostEvent event = HostMessages.event(json);
        if (event != null) clientThread.execute(() -> screens.onHostEvent(screenId, event));
    }

    // onPcm stays the default no-op: OS audio mode never asks for PCM; SPATIAL is the ADR-008 stretch goal.
}
