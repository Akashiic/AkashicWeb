package com.akashiic.web.client.net;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

import com.akashiic.web.client.screen.ClientScreens;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.common.net.ClientHooks;

/**
 * M5's side of the network seam (docs/CONTRACTS.md): M4's packet handlers call this from the Netty thread and its tile
 * entity from the client thread; everything hops to the client thread through {@link ClientTaskQueue} before touching
 * screens. The one exception is the clock pong, which is stamped and fed to the thread-safe ServerClock right here on
 * the Netty thread, so the queue delay does not bias the clock offset (ADR-012 amendment: no Netty mixin).
 */
public final class ClientNetHooks implements ClientHooks {

    private final ClientScreens screens;
    private final LongSupplier timeMs;
    private final Executor clientThread;
    private final ClientScreens.Notifier notifier;

    public ClientNetHooks(ClientScreens screens, LongSupplier timeMs, Executor clientThread,
        ClientScreens.Notifier notifier) {
        this.screens = screens;
        this.timeMs = timeMs;
        this.clientThread = clientThread;
        this.notifier = notifier;
    }

    @Override
    public void onHello(int protoMajor, int protoMinor, boolean killSwitch, SitePolicy policy, long serverTimeMs,
        UUID playerUuid) {
        // serverTimeMs alone (no round trip) cannot correct the clock; ServerClock's ping exchange does that.
        clientThread.execute(() -> screens.onHello(protoMajor, protoMinor, killSwitch, policy, playerUuid));
    }

    @Override
    public void onScreenState(ScreenState state) {
        if (state != null) clientThread.execute(() -> screens.onScreenState(state));
    }

    @Override
    public void onScreenRemoved(ScreenKey key) {
        if (key != null) clientThread.execute(() -> screens.onScreenRemoved(key));
    }

    @Override
    public void onUrl(ScreenKey key, String url, int epoch) {
        if (key != null && url != null) clientThread.execute(() -> screens.onUrl(key, url, epoch));
    }

    @Override
    public void onMediaClock(ScreenKey key, int epoch, double mediaTime, float rate, boolean paused, long serverTimeMs,
        boolean live, int liveLagMs) {
        // live and liveLagMs (D-016) are not used yet: the followers' live-edge rule will take them (M5 follow-up).
        if (key != null)
            clientThread.execute(() -> screens.onMediaClock(key, epoch, mediaTime, rate, paused, serverTimeMs));
    }

    @Override
    public void onClockPong(long nonce, long clientSendMs, long serverTimeMs) {
        long arrival = timeMs.getAsLong(); // stamped on arrival, before any queueing
        screens.clock()
            .onPong(nonce, clientSendMs, serverTimeMs, arrival);
    }

    @Override
    public void onNotice(ReasonCode code, String[] args) {
        if (code == null) return; // a code this client does not know yet (newer server)
        Object[] copy = args == null ? new Object[0] : args.clone();
        clientThread.execute(() -> notifier.notice(code.langKey(), copy));
    }
}
