package com.akashiic.web.common.net;

import java.util.Map;

import com.akashiic.web.common.ScreenKey;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;

/**
 * {@link ServerBound} over the {@code akashicweb} channel (client side; M5 calls it through
 * {@code ModContent.toServer}). Call from the client thread: FMLProxyPacket sends are not safe from two threads.
 */
final class ChannelServerBound implements ServerBound {

    private final SimpleNetworkWrapper channel;

    ChannelServerBound(SimpleNetworkWrapper channel) {
        this.channel = channel;
    }

    private void send(IMessage m) {
        channel.sendToServer(m);
    }

    @Override
    public void setUrl(ScreenKey key, int epoch, String url) {
        send(new C2SSetUrl(key, epoch, url));
    }

    @Override
    public void nav(ScreenKey key, int epoch, String action) {
        send(new C2SNav(key, epoch, action));
    }

    @Override
    public void mediaSample(ScreenKey key, MediaSample sample) {
        send(new C2SMediaSample(key, sample));
    }

    @Override
    public void control(ScreenKey key, boolean take) {
        send(new C2SControl(key, take));
    }

    @Override
    public void rights(ScreenKey key, RightsChange change) {
        send(new C2SRights(key, change));
    }

    @Override
    public void clockPing(long nonce, long clientSendMs) {
        send(new C2SClockPing(nonce, clientSendMs));
    }

    @Override
    public void preflight(Map<String, String> info) {
        send(new C2SPreflight(info));
    }
}
