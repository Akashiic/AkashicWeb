package com.akashiic.web.client;

import java.util.EnumSet;
import java.util.Set;

import com.akashiic.web.client.host.HostStatus;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.common.ReasonCode;

/**
 * Glue between M3's engine installer and M2's host, decided once per client tick from two cheap reads (M3's
 * recommended sequence, wave 2):
 * <ul>
 * <li>The host stopped with HOST_LAUNCH_FAILED or HOST_EXITED (DOWN or DISABLED): ask the installer to verify the
 * engine with the host's failure text ({@code verifyAfterLoadFailure}), once per episode; an episode ends when the
 * host is READY again.</li>
 * <li>The installer publishes a new READY state (an install, a repair, or a verify that rewrote something) while the
 * host is DOWN or DISABLED: {@code reenable()} it, so it relaunches at once instead of waiting for its backoff or
 * staying disabled. A verify that finds the engine intact publishes nothing new, so the supervisor's own backoff and
 * crash-loop guard keep their meaning; the player's Repair button then decides.</li>
 * </ul>
 * Pure; tested in ServiceWatcherTest.
 */
final class ServiceWatcher {

    enum Action {
        VERIFY,
        REENABLE
    }

    private EngineState lastEngine;
    private boolean verifiedThisEpisode;

    /**
     * @param engine the installer's state now (M3 publishes a new object on every change), or null when not wired
     * @param host   the host's status, or null while no host was started
     */
    Set<Action> update(EngineState engine, HostStatus host) {
        Set<Action> out = EnumSet.noneOf(Action.class);
        boolean stopped = host != null && (host.kind == HostStatus.Kind.DOWN || host.kind == HostStatus.Kind.DISABLED);
        if (host != null && host.kind == HostStatus.Kind.READY) verifiedThisEpisode = false;
        if (stopped && !verifiedThisEpisode
            && (host.reason == ReasonCode.HOST_LAUNCH_FAILED || host.reason == ReasonCode.HOST_EXITED)
            && engine != null
            && engine.kind == EngineState.Kind.READY) {
            verifiedThisEpisode = true;
            out.add(Action.VERIFY);
        }
        boolean freshReady = engine != null && engine.kind == EngineState.Kind.READY
            && lastEngine != null
            && engine != lastEngine;
        if (freshReady && stopped) out.add(Action.REENABLE);
        if (engine != null) lastEngine = engine;
        return out;
    }
}
