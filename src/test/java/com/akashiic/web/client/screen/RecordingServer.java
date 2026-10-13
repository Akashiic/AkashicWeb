package com.akashiic.web.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.net.MediaSample;
import com.akashiic.web.common.net.RightsChange;
import com.akashiic.web.common.net.ServerBound;

/** A ServerBound that records what the client would send. */
public final class RecordingServer implements ServerBound {

    final List<String> sent = new ArrayList<>();
    final List<MediaSample> samples = new ArrayList<>();

    @Override
    public void setUrl(ScreenKey key, int epoch, String url) {
        sent.add("setUrl " + epoch + " " + url);
    }

    @Override
    public void nav(ScreenKey key, int epoch, String action) {
        sent.add("nav " + epoch + " " + action);
    }

    @Override
    public void mediaSample(ScreenKey key, MediaSample sample) {
        samples.add(sample);
        sent.add("media");
    }

    @Override
    public void control(ScreenKey key, boolean take) {
        sent.add("control " + take);
    }

    @Override
    public void rights(ScreenKey key, RightsChange change) {
        sent.add("rights");
    }

    @Override
    public void clockPing(long nonce, long clientSendMs) {
        sent.add("ping");
    }

    @Override
    public void preflight(Map<String, String> info) {
        sent.add("preflight");
    }

    int count(String prefix) {
        int n = 0;
        for (String s : sent) if (s.startsWith(prefix)) n++;
        return n;
    }
}
