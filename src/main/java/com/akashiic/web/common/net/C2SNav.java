package com.akashiic.web.common.net;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.akashiic.web.common.ScreenKey;

import io.netty.buffer.ByteBuf;

/**
 * C2S 17: a navigation button. The server owns the URL history, so back/forward/home/reload become URL changes it
 * broadcasts; "stop" is accepted and only affects the sender's own browser.
 */
public final class C2SNav extends AkwMessage {

    public static final List<String> ACTIONS = Collections
        .unmodifiableList(Arrays.asList("back", "forward", "reload", "stop", "home"));

    public ScreenKey key;
    public int epoch;
    public String action;

    public C2SNav() {}

    public C2SNav(ScreenKey key, int epoch, String action) {
        this.key = key;
        this.epoch = epoch;
        this.action = action;
    }

    @Override
    protected void read(ByteBuf b) {
        key = NetCodec.readKey(b);
        epoch = b.readInt();
        action = NetCodec.readString(b, NetCodec.MAX_ACTION)
            .toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) throw new CodecException("action " + action);
    }

    @Override
    protected void write(ByteBuf b) {
        NetCodec.writeKey(b, key);
        b.writeInt(epoch);
        NetCodec.writeString(b, action, NetCodec.MAX_ACTION);
    }
}
