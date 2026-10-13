package com.akashiic.web.common.net;

import com.akashiic.web.common.ModContent;
import com.akashiic.web.server.net.ServerPackets;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * The {@code akashicweb} SimpleNetworkWrapper channel with the packet ids of docs/PROTOCOL.md section 2. Ids are part
 * of the wire format: never renumber, never reuse a retired id. Server-to-client handlers live next to their messages
 * and only call {@link ClientHooks}; client-to-server handlers live in {@code server.net}. FML instantiates the
 * handlers of both directions on both sides, which is safe because none of them references client classes.
 * <p>
 * Threads (1.7.10 and Crucible): FMLProxyPacket does not override hasPriority, so NetworkManager hands our packets to
 * the server main thread (processReceivedPackets) and, on the client, to the client thread; handlers do not run on
 * Netty threads. Nothing relies on that: the server queues its work and M5 hops to its own queue anyway.
 * <p>
 * Sending rule (from AkashicFM FmNetwork): only the server main thread sends. The 1.7.10 wrapper keeps the target
 * player in attributes of a shared channel, so two threads sending at once can swap recipients.
 */
public final class WebNetwork {

    public static final String CHANNEL_NAME = "akashicweb";

    public static final int S2C_HELLO = 0;
    public static final int S2C_SCREEN_STATE = 1;
    public static final int S2C_URL = 2;
    /** Retired by D-016 (was S2CInput); reserved, registered as a tombstone. */
    public static final int RETIRED_S2C_INPUT = 3;
    public static final int S2C_MEDIA_CLOCK = 4;
    public static final int S2C_CLOCK_PONG = 5;
    public static final int S2C_NOTICE = 6;
    public static final int C2S_SET_URL = 16;
    public static final int C2S_NAV = 17;
    /** Retired by D-016 (was C2SPointer); reserved, registered as a tombstone. */
    public static final int RETIRED_C2S_POINTER = 18;
    /** Retired by D-016 (was C2SKey); reserved, registered as a tombstone. */
    public static final int RETIRED_C2S_KEY = 19;
    public static final int C2S_MEDIA_SAMPLE = 20;
    public static final int C2S_CONTROL = 21;
    public static final int C2S_RIGHTS = 22;
    public static final int C2S_CLOCK_PING = 23;
    public static final int C2S_PREFLIGHT = 24;

    private static SimpleNetworkWrapper channel;

    private WebNetwork() {}

    /** init: registers every packet and publishes the client sender in {@link ModContent#toServer}. */
    public static void init() {
        SimpleNetworkWrapper c = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL_NAME);
        c.registerMessage(S2CHello.Handler.class, S2CHello.class, S2C_HELLO, Side.CLIENT);
        c.registerMessage(S2CScreenState.Handler.class, S2CScreenState.class, S2C_SCREEN_STATE, Side.CLIENT);
        c.registerMessage(S2CUrl.Handler.class, S2CUrl.class, S2C_URL, Side.CLIENT);
        c.registerMessage(
            RetiredMessage.Input3.Handler.class,
            RetiredMessage.Input3.class,
            RETIRED_S2C_INPUT,
            Side.CLIENT);
        c.registerMessage(S2CMediaClock.Handler.class, S2CMediaClock.class, S2C_MEDIA_CLOCK, Side.CLIENT);
        c.registerMessage(S2CClockPong.Handler.class, S2CClockPong.class, S2C_CLOCK_PONG, Side.CLIENT);
        c.registerMessage(S2CNotice.Handler.class, S2CNotice.class, S2C_NOTICE, Side.CLIENT);
        c.registerMessage(ServerPackets.SetUrl.class, C2SSetUrl.class, C2S_SET_URL, Side.SERVER);
        c.registerMessage(ServerPackets.Nav.class, C2SNav.class, C2S_NAV, Side.SERVER);
        c.registerMessage(
            RetiredMessage.Pointer18.Handler.class,
            RetiredMessage.Pointer18.class,
            RETIRED_C2S_POINTER,
            Side.SERVER);
        c.registerMessage(RetiredMessage.Key19.Handler.class, RetiredMessage.Key19.class, RETIRED_C2S_KEY, Side.SERVER);
        c.registerMessage(ServerPackets.Media.class, C2SMediaSample.class, C2S_MEDIA_SAMPLE, Side.SERVER);
        c.registerMessage(ServerPackets.Control.class, C2SControl.class, C2S_CONTROL, Side.SERVER);
        c.registerMessage(ServerPackets.RightsChanged.class, C2SRights.class, C2S_RIGHTS, Side.SERVER);
        c.registerMessage(ServerPackets.ClockPing.class, C2SClockPing.class, C2S_CLOCK_PING, Side.SERVER);
        c.registerMessage(ServerPackets.Preflight.class, C2SPreflight.class, C2S_PREFLIGHT, Side.SERVER);
        channel = c;
        ModContent.toServer = new ChannelServerBound(c);
    }

    /** Null before init. */
    public static SimpleNetworkWrapper channel() {
        return channel;
    }
}
