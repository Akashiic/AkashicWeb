package com.akashiic.web.server;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import com.akashiic.web.common.RateLimiter;
import com.akashiic.web.common.WebSettings;

/**
 * Per-player rate limits, one token bucket per kind of client packet, so a flood of pointer moves never starves URL
 * changes and the other way round. Thread-safe (consulted on the network thread before queueing).
 */
public final class ActionLimits {

    public enum Kind {

        URL,
        NAV,
        POINTER,
        KEY,
        MEDIA,
        CONTROL,
        RIGHTS,
        PING,
        PREFLIGHT,
        /** Right-click formation attempts. */
        FORM;

        int rate(WebSettings s) {
            switch (this) {
                case POINTER:
                    return s.pointerPerSecond;
                case KEY:
                    return s.keysPerSecond;
                case MEDIA:
                    return s.mediaSamplesPerSecond;
                case PING:
                    return s.clockPingsPerSecond;
                case PREFLIGHT:
                    return 1;
                default:
                    return s.actionsPerSecond;
            }
        }
    }

    private final Map<Kind, RateLimiter> limiters = new EnumMap<>(Kind.class);

    public ActionLimits() {
        this(System::nanoTime);
    }

    public ActionLimits(LongSupplier nanoClock) {
        for (Kind k : Kind.values()) limiters.put(k, new RateLimiter(nanoClock));
    }

    public boolean tryAcquire(UUID player, Kind kind, WebSettings settings) {
        return limiters.get(kind)
            .tryAcquire(player, kind.rate(settings));
    }

    public void forget(UUID player) {
        for (RateLimiter l : limiters.values()) l.forget(player);
    }

    public void clear() {
        for (RateLimiter l : limiters.values()) l.clear();
    }
}
