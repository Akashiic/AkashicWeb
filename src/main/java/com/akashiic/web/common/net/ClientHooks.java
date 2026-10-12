package com.akashiic.web.common.net;

import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.SitePolicy;

/**
 * Server→client packets and client-side tile lifecycle, as calls. M4's packet handlers and tile entity call it on the
 * CLIENT MAIN THREAD (they hop there first); M5 implements it and returns it from ClientProxy. On the dedicated
 * server the proxy returns {@link #NOOP}. Contract type.
 */
public interface ClientHooks {

    void onHello(int protoMajor, int protoMinor, boolean killSwitch, SitePolicy policy, long serverTimeMs);

    /** Full record, from S2CScreenState or the tile description packet. */
    void onScreenState(ScreenState state);

    /** The client tile was invalidated or its chunk unloaded. */
    void onScreenRemoved(ScreenKey key);

    void onUrl(ScreenKey key, String url, int epoch);

    void onInput(ScreenKey key, int epoch, ScreenInput input);

    void onMediaClock(ScreenKey key, int epoch, double mediaTime, float rate, boolean paused, long serverTimeMs);

    void onClockPong(long nonce, long clientSendMs, long serverTimeMs);

    void onNotice(ReasonCode code, String[] args);

    ClientHooks NOOP = new ClientHooks() {

        @Override
        public void onHello(int protoMajor, int protoMinor, boolean killSwitch, SitePolicy policy, long serverTimeMs) {}

        @Override
        public void onScreenState(ScreenState state) {}

        @Override
        public void onScreenRemoved(ScreenKey key) {}

        @Override
        public void onUrl(ScreenKey key, String url, int epoch) {}

        @Override
        public void onInput(ScreenKey key, int epoch, ScreenInput input) {}

        @Override
        public void onMediaClock(ScreenKey key, int epoch, double mediaTime, float rate, boolean paused,
            long serverTimeMs) {}

        @Override
        public void onClockPong(long nonce, long clientSendMs, long serverTimeMs) {}

        @Override
        public void onNotice(ReasonCode code, String[] args) {}
    };
}
