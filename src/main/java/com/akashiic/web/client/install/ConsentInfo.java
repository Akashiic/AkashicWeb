package com.akashiic.web.client.install;

import java.io.File;

/**
 * The exact facts the pt-BR consent screen shows (ADR-006 "Consent screen"): which component, from which publisher
 * and host, how many bytes are downloaded and how many stay on disk, and where; plus what the manual-import hint
 * needs (the official zip's file name and URL, and the folder to save it in). No legal claims. Immutable.
 * <p>
 * Paths come twice: as files (for an "open folder" button) and as display text without the expanded profile path
 * ({@code %LOCALAPPDATA%\AkashicWeb\import}), which players can type into Explorer and paste into a support chat
 * without revealing their Windows user name.
 */
public final class ConsentInfo {

    /** e.g. "Electron 44.7.0 (Chromium 152.0.7977.130)". */
    public final String component;
    public final String publisher;
    /** Host the bytes come from, e.g. "github.com". */
    public final String sourceHost;
    public final long downloadBytes;
    /** Bytes left on disk after install (the zip is deleted). */
    public final long diskBytes;
    /** Peak free space required while installing ({@link EngineManifest#MIN_FREE_BYTES}). */
    public final long requiredFreeBytes;
    /** The whole AkashicWeb folder (engine, downloads, import, browser profiles); null when not resolved yet. */
    public final File installDir;
    /** {@link #installDir} as players see it, e.g. {@code %LOCALAPPDATA%\AkashicWeb}; null when not resolved. */
    public final String installDirDisplay;
    /** File name of the official zip, e.g. {@code electron-v44.7.0-win32-x64.zip}. */
    public final String zipName;
    /** The canonical download URL of that zip (for "download it in your browser"). */
    public final String downloadUrl;
    /** Where a manually downloaded zip goes; null when not resolved yet. */
    public final File importDir;
    /** {@link #importDir} as players see it; null when not resolved. */
    public final String importDirDisplay;

    public ConsentInfo(String component, String publisher, String sourceHost, long downloadBytes, long diskBytes,
        long requiredFreeBytes, File installDir) {
        this(
            component,
            publisher,
            sourceHost,
            downloadBytes,
            diskBytes,
            requiredFreeBytes,
            installDir,
            installDir == null ? null : installDir.getPath(),
            null,
            null,
            null,
            null);
    }

    public ConsentInfo(String component, String publisher, String sourceHost, long downloadBytes, long diskBytes,
        long requiredFreeBytes, File installDir, String installDirDisplay, String zipName, String downloadUrl,
        File importDir, String importDirDisplay) {
        this.component = component;
        this.publisher = publisher;
        this.sourceHost = sourceHost;
        this.downloadBytes = downloadBytes;
        this.diskBytes = diskBytes;
        this.requiredFreeBytes = requiredFreeBytes;
        this.installDir = installDir;
        this.installDirDisplay = installDirDisplay;
        this.zipName = zipName;
        this.downloadUrl = downloadUrl;
        this.importDir = importDir;
        this.importDirDisplay = importDirDisplay;
    }

    static ConsentInfo of(EngineTarget t, InstallPaths paths) {
        String host;
        try {
            host = new java.net.URL(t.url).getHost();
        } catch (java.net.MalformedURLException e) {
            host = t.url;
        }
        File importDir = paths == null ? null : paths.importDir();
        return new ConsentInfo(
            "Electron " + t.version + " (Chromium " + EngineManifest.CHROMIUM_VERSION + ")",
            "Electron project / OpenJS Foundation (official release build)",
            host,
            t.size,
            EngineManifest.keptBytes(t),
            EngineManifest.MIN_FREE_BYTES,
            paths == null ? null : paths.base,
            paths == null ? null : paths.display,
            t.assetName,
            t.url,
            importDir,
            importDir == null ? null : paths.display(importDir));
    }
}
