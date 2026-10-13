package com.akashiic.web.client.sync;

import com.akashiic.web.common.net.MediaSample;

/**
 * Controller side of the media clock (ADR-012 item 3): turns our own MEDIA samples into C2SMediaSample at 4 Hz,
 * stamped with the server time of their arrival from the host. One instance per screen, client thread only. Pure.
 */
public final class MediaSampler {

    /** 4 Hz nominal; a little slack so host jitter does not drop every other sample. */
    public static final long MIN_INTERVAL_MS = 200;

    private boolean sent;
    private long lastSentMs;

    /**
     * Returns the packet to send for {@code m}, or null when it is too soon, the server clock was not synced when the
     * sample arrived, or the sample has no usable position.
     */
    public MediaSample next(int epoch, LocalMedia m, long nowMs) {
        if (m == null || Double.isNaN(m.serverStampMs) || Double.isNaN(m.mediaTime) || m.mediaTime < 0) return null;
        if (sent && nowMs - lastSentMs < MIN_INTERVAL_MS) return null;
        sent = true;
        lastSentMs = nowMs;
        // The ad flag travels with the sample: during an ad the element's currentTime belongs to the ad, so the server
        // must hold its clock instead of following it (reported to M4).
        return new MediaSample(
            epoch,
            m.mediaTime,
            m.rate,
            m.paused,
            m.adShowing,
            m.live,
            m.behindLiveEdge,
            Math.round(m.serverStampMs));
    }

    public void reset() {
        sent = false;
    }
}
