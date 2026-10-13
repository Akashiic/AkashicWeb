package com.akashiic.web.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.akashiic.web.common.ReasonCode;

/**
 * Keeps rejected-packet audit lines readable: at most one line per player and reason per window, with the number of
 * suppressed repeats carried into the next line. A client stuck in a loop (or a flood) cannot fill the audit file.
 * Pure; main thread only.
 */
public final class AuditThrottle {

    public static final long WINDOW_MS = 10_000;

    private static final class Slot {

        long lastMs;
        int suppressed;
    }

    private final Map<String, Slot> slots = new HashMap<>();

    /**
     * Returns -1 if this rejection should not be logged now; otherwise the number of identical rejections suppressed
     * since the last logged line (0 for the first).
     */
    public int admit(UUID player, ReasonCode reason, long nowMs) {
        String k = player + "/" + reason.name();
        Slot s = slots.get(k);
        if (s == null) {
            s = new Slot();
            s.lastMs = nowMs;
            slots.put(k, s);
            return 0;
        }
        if (nowMs - s.lastMs < WINDOW_MS) {
            s.suppressed++;
            return -1;
        }
        int n = s.suppressed;
        s.suppressed = 0;
        s.lastMs = nowMs;
        return n;
    }

    public void forget(UUID player) {
        String prefix = player + "/";
        slots.keySet()
            .removeIf(k -> k.startsWith(prefix));
    }

    public void clear() {
        slots.clear();
    }
}
