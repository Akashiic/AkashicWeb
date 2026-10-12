package com.akashiic.web.common.net;

import java.util.Map;

import com.akashiic.web.common.ScreenKey;

/**
 * Client→server packets (docs/PROTOCOL.md section 2) as calls. M4 implements it over the SimpleNetworkWrapper and
 * stores it in {@code ModContent.toServer}; M5 only calls it. Contract type.
 */
public interface ServerBound {

    void setUrl(ScreenKey key, int epoch, String url);

    /** action: back, forward, reload, stop, home. */
    void nav(ScreenKey key, int epoch, String action);

    void pointer(ScreenKey key, ScreenInput input);

    void key(ScreenKey key, ScreenInput input);

    void mediaSample(ScreenKey key, MediaSample sample);

    void control(ScreenKey key, boolean take);

    void rights(ScreenKey key, RightsChange change);

    void clockPing(long nonce, long clientSendMs);

    /** Opt-in diagnostics (os, arch, java, gpu, ram, engine state). */
    void preflight(Map<String, String> info);

    ServerBound NOOP = new ServerBound() {

        @Override
        public void setUrl(ScreenKey key, int epoch, String url) {}

        @Override
        public void nav(ScreenKey key, int epoch, String action) {}

        @Override
        public void pointer(ScreenKey key, ScreenInput input) {}

        @Override
        public void key(ScreenKey key, ScreenInput input) {}

        @Override
        public void mediaSample(ScreenKey key, MediaSample sample) {}

        @Override
        public void control(ScreenKey key, boolean take) {}

        @Override
        public void rights(ScreenKey key, RightsChange change) {}

        @Override
        public void clockPing(long nonce, long clientSendMs) {}

        @Override
        public void preflight(Map<String, String> info) {}
    };
}
