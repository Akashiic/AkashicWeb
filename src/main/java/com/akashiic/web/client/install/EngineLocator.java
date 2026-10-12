package com.akashiic.web.client.install;

import java.io.File;

/** Where the browser engine is and whether it can run (docs/CONTRACTS.md, M3→M2/M5). Contract type. */
public interface EngineLocator {

    EngineState state();

    /** AkashicWebHost(.exe) when READY, else null. */
    File hostExecutable();

    /** Per-instance profile root for the host's --akw-user-data. */
    File userDataDir();

    /** e.g. "electron-44.7.0-win32-x64". */
    String engineId();

    /** Starts the download/install on a background thread; call only after the player consented. */
    void requestInstall();
}
