package com.akashiic.web.client.host;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.akashiic.web.client.install.EngineLocator;
import com.akashiic.web.client.install.EngineState;
import com.akashiic.web.common.ReasonCode;

/**
 * What to run for the host. Asked again before every launch, so an engine installed (or removed) mid-session is
 * picked up by the next {@link HostSupervisor#reenable()}.
 */
public interface HostExecutable {

    /** Executable plus fixed arguments, or null when the engine is not usable now. */
    List<String> command();

    /** Why {@link #command()} is null. */
    ReasonCode unavailableReason();

    /** The host's {@code --akw-user-data} directory. */
    File userDataDir();

    /** The engine from the installer (M3). Usable only in state READY with an existing executable file. */
    static HostExecutable fromEngine(EngineLocator engine) {
        return new HostExecutable() {

            @Override
            public List<String> command() {
                EngineState s = engine.state();
                File exe = engine.hostExecutable();
                if (s == null || s.kind != EngineState.Kind.READY || exe == null || !exe.isFile()) return null;
                return Collections.singletonList(exe.getAbsolutePath());
            }

            @Override
            public ReasonCode unavailableReason() {
                EngineState s = engine.state();
                return s == null || s.reason == ReasonCode.OK ? ReasonCode.ENGINE_ABSENT : s.reason;
            }

            @Override
            public File userDataDir() {
                return engine.userDataDir();
            }
        };
    }

    /** A fixed command, e.g. a development host or the test fake. */
    static HostExecutable fixed(List<String> command, File userDataDir) {
        List<String> copy = Collections.unmodifiableList(new ArrayList<>(command));
        return new HostExecutable() {

            @Override
            public List<String> command() {
                return copy;
            }

            @Override
            public ReasonCode unavailableReason() {
                return ReasonCode.OK;
            }

            @Override
            public File userDataDir() {
                return userDataDir;
            }
        };
    }
}
