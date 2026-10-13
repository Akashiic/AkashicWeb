package com.akashiic.web.common.net;

import java.util.UUID;

import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.SitePolicy;

/**
 * Server→client packets and client-side tile lifecycle, as calls. M4's packet handlers call it on the thread FML
 * delivers them on (the client thread in 1.7.10: FMLProxyPacket has no priority, so NetworkManager queues it for
 * processReceivedPackets) and its tile entity on the client thread; M5's implementation hops through its own client
 * queue anyway, so it does not depend on that. M4's handler classes stay free of client classes because the dedicated
 * server instantiates them too. On the dedicated server the proxy returns {@link #NOOP}. Contract type.
 * <p>
 * There is no input callback (D-016): page input never comes back from the server; the client applies its own input
 * to its own browser while {@code ScreenState.controller} equals {@code playerUuid} from {@link #onHello}, or on a
 * PRIVATE screen.
 */
public interface ClientHooks {

    /**
     * @param policy       null when the server speaks another protocol major (then only the version numbers are known)
     * @param serverTimeMs the server clock when the hello was written
     * @param playerUuid   this player's UUID as the server knows it (compare it with ScreenState.controller); null from
     *                     a server built before D-016 or of another major
     */
    void onHello(int protoMajor, int protoMinor, boolean killSwitch, SitePolicy policy, long serverTimeMs,
        UUID playerUuid);

    /** Full record, from S2CScreenState or the tile description packet. */
    void onScreenState(ScreenState state);

    /** The client tile was invalidated or its chunk unloaded. */
    void onScreenRemoved(ScreenKey key);

    void onUrl(ScreenKey key, String url, int epoch);

    /**
     * The server's media clock (S2CMediaClock): at server time {@code serverTimeMs} the position is {@code mediaTime}.
     *
     * @param live      the controller's page plays a live stream
     * @param liveLagMs how far the controller is behind the live edge (0 for VOD), for the followers' live-edge rule
     */
    void onMediaClock(ScreenKey key, int epoch, double mediaTime, float rate, boolean paused, long serverTimeMs,
        boolean live, int liveLagMs);

    void onClockPong(long nonce, long clientSendMs, long serverTimeMs);

    void onNotice(ReasonCode code, String[] args);

    /**
     * Added by M4: a player right-clicked a formed screen with an empty hand, on the client thread (from
     * Block.onBlockActivated, which on Crucible runs only if no region plugin cancelled the interaction). (u, v) is the
     * page position of the hit, or NaN when the frame was hit. M5 opens its screen GUI here; the server does nothing
     * for this click.
     */
    default void onScreenActivated(ScreenKey key, double u, double v, boolean sneaking) {}

    ClientHooks NOOP = new ClientHooks() {

        @Override
        public void onHello(int protoMajor, int protoMinor, boolean killSwitch, SitePolicy policy, long serverTimeMs,
            UUID playerUuid) {}

        @Override
        public void onScreenState(ScreenState state) {}

        @Override
        public void onScreenRemoved(ScreenKey key) {}

        @Override
        public void onUrl(ScreenKey key, String url, int epoch) {}

        @Override
        public void onMediaClock(ScreenKey key, int epoch, double mediaTime, float rate, boolean paused,
            long serverTimeMs, boolean live, int liveLagMs) {}

        @Override
        public void onClockPong(long nonce, long clientSendMs, long serverTimeMs) {}

        @Override
        public void onNotice(ReasonCode code, String[] args) {}
    };
}
