package com.akashiic.web.client.install;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The pinned engine (ADR-002, ADR-006, ADR-011): the official Electron 44.7.0 release zips, fetched from the
 * publisher's own server and accepted only if the whole file matches the SHA-256 pinned here.
 * <p>
 * Provenance (AUTOMATED, Linux container, 2026-10-12): both hashes were compared with the official
 * {@code https://github.com/electron/electron/releases/download/v44.7.0/SHASUMS256.txt}; both sizes are the
 * {@code Content-Length} served after the github.com 302 redirect. Kept bytes were summed from the real zips' central
 * directories after pruning. Re-pin at the engine freeze by updating all of these values together.
 */
public final class EngineManifest {

    public static final String ELECTRON_VERSION = "44.7.0";
    /** Chromium version inside Electron 44.7.0 (releases.json), shown on the consent screen. */
    public static final String CHROMIUM_VERSION = "152.0.7977.130";
    static final String RELEASE_BASE = "https://github.com/electron/electron/releases/download/v" + ELECTRON_VERSION
        + "/";

    public static final EngineTarget WIN32_X64 = new EngineTarget(
        "win32-x64",
        ELECTRON_VERSION,
        "electron-v44.7.0-win32-x64.zip",
        RELEASE_BASE + "electron-v44.7.0-win32-x64.zip",
        158_293_649L,
        "eee30dc8fa1f5ea95490e59f44e46ea68dd24c6e93d22facf70fe5c2d4c2665c",
        "electron.exe",
        "AkashicWebHost.exe",
        true,
        Collections.<String>emptyList());

    public static final EngineTarget LINUX_X64 = new EngineTarget(
        "linux-x64",
        ELECTRON_VERSION,
        "electron-v44.7.0-linux-x64.zip",
        RELEASE_BASE + "electron-v44.7.0-linux-x64.zip",
        122_998_223L,
        "3ae7d5bdad61c664486c6ab61361dd084d064c8c08af8d13a687096e18ce555a",
        "electron",
        "AkashicWebHost",
        false,
        Arrays.asList("AkashicWebHost", "chrome_crashpad_handler", "chrome-sandbox"));

    /** Bytes on disk after pruning (win32: 19 files; linux: 18 files), plus our host app (a few KB). */
    public static final long WIN32_X64_KEPT_BYTES = 336_044_091L;
    public static final long LINUX_X64_KEPT_BYTES = 287_441_210L;

    /** Free space required before a download or an import starts (ADR-011): zip + unpacked copy + margin. */
    public static final long MIN_FREE_BYTES = 1024L * 1024 * 1024;

    /** Only these UI locales are kept; the other 53 .pak files are never written (about 40 MB on Windows). */
    public static final List<String> KEPT_LOCALES = Collections.unmodifiableList(
        Arrays.asList("locales/en-US.pak", "locales/pt-BR.pak"));
    static final String LOCALES_PREFIX = "locales/";

    /**
     * Vendor entries that are never installed. Without default_app.asar the exe cannot be pointed at another app
     * folder from its command line, and Electron loads only our {@code resources/app} (ADR-001 amendment).
     */
    public static final List<String> REMOVED_ENTRIES = Collections.unmodifiableList(
        Collections.singletonList("resources/default_app.asar"));

    /** Where our host app goes inside the engine folder (Electron's app search path). */
    public static final String APP_DIR = "resources/app";

    /** Zip-bomb guards: the real win32 zip has 73 entries that unpack to 385,514,847 B. */
    static final int MAX_ENTRIES = 512;
    static final long MAX_WRITTEN_BYTES = 640L * 1024 * 1024;

    private EngineManifest() {}

    /** Bytes the install occupies once done, for the consent screen. */
    public static long keptBytes(EngineTarget t) {
        if (t == WIN32_X64) return WIN32_X64_KEPT_BYTES;
        if (t == LINUX_X64) return LINUX_X64_KEPT_BYTES;
        return t.size * 2; // test targets: a safe over-estimate
    }
}
