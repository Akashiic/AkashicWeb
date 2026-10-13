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

    // ---- Added by M3 (default methods, so existing implementations and fakes keep compiling) ----

    /** One line for {@code /web doctor} and the log: what the installer did or why it failed. Never null. */
    default String detail() {
        return "";
    }

    /** The numbers the consent screen shows, or null when nothing can be downloaded (UNSUPPORTED). */
    default ConsentInfo consentInfo() {
        return null;
    }

    /**
     * M2 calls this when the host failed to start or load. Runs a full re-hash in the background; a damaged host app
     * is rewritten from the jar, a damaged engine falls back to the previous intact one, else the state becomes
     * FAILED with the reason.
     */
    default void verifyAfterLoadFailure() {}
}
