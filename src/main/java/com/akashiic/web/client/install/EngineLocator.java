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
     * FAILED with the reason. Smart App Control is checked again too.
     */
    default void verifyAfterLoadFailure() {}

    // ---- Added by M3 in wave 2 (default methods, as above) ----

    /**
     * As {@link #verifyAfterLoadFailure()}, with the launch error text when there is one (the IOException message of
     * the failed process start, or the host's last failure detail). A Windows refusal in it ({@code CreateProcess
     * error=4551} and the other App Control codes, {@code error=225} antivirus) turns an otherwise intact engine into
     * FAILED with SAC_BLOCKED or AV_BLOCKED, so the placeholder names the real cause.
     */
    default void verifyAfterLoadFailure(String launchError) {
        verifyAfterLoadFailure();
    }

    /**
     * The player declined or stopped the install ("Cancel" while downloading, "Not now" on the consent screen): a
     * download in progress stops at the next chunk, the partial download and the consent record are deleted, and the
     * state returns to CONSENT_NEEDED. Nothing is fetched again until {@link #requestInstall()}. Safe from any thread.
     */
    default void cancelInstall() {}

    /**
     * The "Repair" button (FAILED placeholder, /web doctor): re-checks the platform, the install folder and Smart App
     * Control, fully re-hashes the installed engine, rewrites a damaged host app from the jar and re-downloads a
     * damaged
     * engine. The button sits next to the consent facts ({@link #consentInfo()}), so pressing it is consent to
     * download. Runs in the background like {@link #requestInstall()}.
     */
    default void repair() {
        requestInstall();
    }
}
