package com.akashiic.web.common.net;

import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.SitePolicy;

/**
 * Server→client packets and client-side tile lifecycle, as calls. M4's packet handlers call it straight from the
 * Netty thread (FML custom packets) and its tile entity from the client thread, so M5's implementation must hop to
 * the client thread itself before touching Minecraft. M4's handler classes stay free of client classes because the
 * dedicated server instantiates them too. On the dedicated server the proxy returns {@link #NOOP}. Contract type.
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

    /**
     * Added by M4: a player right-clicked a formed screen with an empty hand, on the client thread (from
     * Block.onBlockActivated, which on Crucible runs only if no region plugin cancelled the interaction). (u, v) is the
     * page position of the hit, or NaN when the frame was hit. M5 opens its screen GUI here; the server does nothing
     * for this click (input travels in C2SPointer/C2SKey).
     */
    default void onScreenActivated(ScreenKey key, double u, double v, boolean sneaking) {}

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
