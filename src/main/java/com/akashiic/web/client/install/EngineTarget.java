package com.akashiic.web.client.install;

import java.util.Collections;
import java.util.List;

/**
 * One pinned vendor engine asset for one platform: where it is published, its exact size and SHA-256, and how its
 * files are laid out. Immutable. The production values live in {@link EngineManifest}; tests build their own targets
 * pointing at a local server.
 */
public final class EngineTarget {

    /** Electron's platform tag, e.g. {@code win32-x64}. */
    public final String platform;
    public final String version;
    /** File name of the release asset, e.g. {@code electron-v44.7.0-win32-x64.zip}. */
    public final String assetName;
    /**
     * Canonical download URL. The installer stores only this (never the redirected, signed asset URL, which expires)
     * and re-follows redirects on every attempt (ADR-011 amendment).
     */
    public final String url;
    public final long size;
    /** Lower-case hex SHA-256 of the whole zip. */
    public final String sha256;
    /** Entry name of the vendor executable in the zip ({@code electron.exe} / {@code electron}). */
    public final String vendorExecutable;
    /** Name we give it ({@code AkashicWebHost.exe} / {@code AkashicWebHost}); a rename only, bytes untouched. */
    public final String hostExecutable;
    public final boolean windows;
    /** Installed paths that need the POSIX execute bit (ZipFile does not expose Unix modes on Java 8). */
    public final List<String> unixExecutables;

    public EngineTarget(String platform, String version, String assetName, String url, long size, String sha256,
        String vendorExecutable, String hostExecutable, boolean windows, List<String> unixExecutables) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be 64 lower-case hex chars");
        }
        if (size <= 0) throw new IllegalArgumentException("size must be positive");
        this.platform = platform;
        this.version = version;
        this.assetName = assetName;
        this.url = url;
        this.size = size;
        this.sha256 = sha256;
        this.vendorExecutable = vendorExecutable;
        this.hostExecutable = hostExecutable;
        this.windows = windows;
        this.unixExecutables = Collections.unmodifiableList(unixExecutables);
    }

    /** e.g. {@code electron-44.7.0-win32-x64} (the {@link EngineLocator#engineId()} value). */
    public String engineId() {
        return "electron-" + version + "-" + platform;
    }

    /** First 8 hex chars of the pinned SHA-256; part of the install folder name so a re-pin never reuses a folder. */
    public String hash8() {
        return sha256.substring(0, 8);
    }

    /** Install folder name under {@code <base>/engine/}: {@code <engineId>-<hash8>}. */
    public String dirName() {
        return engineId() + "-" + hash8();
    }

    @Override
    public String toString() {
        return engineId() + " (" + size + " B, sha256 " + hash8() + "...)";
    }
}
