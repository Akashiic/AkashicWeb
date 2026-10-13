package com.akashiic.web.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.akashiic.web.common.ReasonCode;

/**
 * What the server knows about one player's AkashicWeb session, for {@code /web doctor} and
 * {@code /webadmin doctor <player>}: the opt-in preflight report, the last reason codes the server decided for them
 * and a count per code. In memory only and dropped at logout (P-09: no telemetry retention decided yet). Main thread.
 */
public final class PlayerDiagnostics {

    public static final int RECENT = 8;

    public static final class Event {

        public final long timeMs;
        public final ReasonCode code;
        public final String detail;

        Event(long timeMs, ReasonCode code, String detail) {
            this.timeMs = timeMs;
            this.code = code;
            this.detail = detail == null ? "" : detail;
        }
    }

    public static final class Record {

        public long helloSentMs;
        public Map<String, String> preflight = Collections.emptyMap();
        public long preflightMs;
        final Deque<Event> recent = new ArrayDeque<>();
        final Map<ReasonCode, Integer> counts = new EnumMap<>(ReasonCode.class);

        public List<Event> recent() {
            return new ArrayList<>(recent);
        }

        public Map<ReasonCode, Integer> counts() {
            return Collections.unmodifiableMap(counts);
        }
    }

    private final Map<UUID, Record> records = new HashMap<>();

    public Record of(UUID player) {
        Record r = records.get(player);
        if (r == null) {
            r = new Record();
            records.put(player, r);
        }
        return r;
    }

    /** Null when the server has nothing about that player. */
    public Record peek(UUID player) {
        return records.get(player);
    }

    public void record(UUID player, ReasonCode code, String detail, long nowMs) {
        if (player == null || code == null) return;
        Record r = of(player);
        r.recent.addLast(new Event(nowMs, code, detail));
        while (r.recent.size() > RECENT) r.recent.removeFirst();
        Integer n = r.counts.get(code);
        r.counts.put(code, n == null ? 1 : n + 1);
    }

    public void preflight(UUID player, Map<String, String> info, long nowMs) {
        Record r = of(player);
        r.preflight = Collections.unmodifiableMap(new LinkedHashMap<>(info));
        r.preflightMs = nowMs;
    }

    public void forget(UUID player) {
        records.remove(player);
    }

    public void clear() {
        records.clear();
    }
}
