package com.akashiic.web.common.net;

/** Controller's media position (C2SMediaSample), stamped with the client's estimate of server time. Contract type. */
public final class MediaSample {

    public final int epoch;
    public final double mediaTime;
    public final float rate;
    public final boolean paused;
    public final boolean adShowing;
    public final boolean live;
    /** Seconds behind the live edge, or 0 for VOD. */
    public final double behindLiveEdge;

    public final long clientServerTimeMs;

    public MediaSample(int epoch, double mediaTime, float rate, boolean paused, boolean adShowing, boolean live,
        double behindLiveEdge, long clientServerTimeMs) {
        this.epoch = epoch;
        this.mediaTime = mediaTime;
        this.rate = rate;
        this.paused = paused;
        this.adShowing = adShowing;
        this.live = live;
        this.behindLiveEdge = behindLiveEdge;
        this.clientServerTimeMs = clientServerTimeMs;
    }
}
