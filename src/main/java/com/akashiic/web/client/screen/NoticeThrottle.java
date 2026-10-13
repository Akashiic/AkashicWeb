package com.akashiic.web.client.screen;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * At most one chat notice per key (screen and reason) per interval. Bounded: entries older than the interval are
 * dropped once the map grows, so a page cycling through many hosts cannot grow it without limit. Client thread only.
 * Pure; tested in ClientReasonTest.
 */
final class NoticeThrottle {

    private static final int PRUNE_ABOVE = 64;

    private final long intervalMs;
    private final Map<String, Long> last = new HashMap<>();

    NoticeThrottle(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    boolean allow(String key, long nowMs) {
        Long previous = last.get(key);
        if (previous != null && nowMs - previous < intervalMs) return false;
        if (last.size() >= PRUNE_ABOVE) prune(nowMs);
        last.put(key, nowMs);
        return true;
    }

    private void prune(long nowMs) {
        for (Iterator<Long> it = last.values()
            .iterator(); it.hasNext();) {
            if (nowMs - it.next() >= intervalMs) it.remove();
        }
    }

    int size() {
        return last.size();
    }
}
