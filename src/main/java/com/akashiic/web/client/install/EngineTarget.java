package com.akashiic.web.client.install;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
    /**
     * Every vendor file an install keeps, by installed path, with its size and SHA-256: pinned like the zip itself,
     * so an engine folder is trusted only if its files are exactly these (review finding 1: a folder with a
     * self-consistent manifest and marker but foreign files was accepted).
     */
    public final Map<String, LocalManifest.Entry> vendorFiles;

    public EngineTarget(String platform, String version, String assetName, String url, long size, String sha256,
        String vendorExecutable, String hostExecutable, boolean windows, List<String> unixExecutables,
        List<LocalManifest.Entry> vendorFiles) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be 64 lower-case hex chars");
        }
        if (size <= 0) throw new IllegalArgumentException("size must be positive");
        Map<String, LocalManifest.Entry> pins = new TreeMap<>();
        for (LocalManifest.Entry e : vendorFiles) {
            if (!e.sha256.matches("[0-9a-f]{64}") || e.size < 0 || pins.put(e.path, e) != null) {
                throw new IllegalArgumentException("bad or duplicate vendor pin " + e.path);
            }
        }
        if (!pins.containsKey(hostExecutable)) throw new IllegalArgumentException("no pin for " + hostExecutable);
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
        this.vendorFiles = Collections.unmodifiableMap(pins);
    }

    /**
     * Null when {@code entries} (a local manifest's vendor part) are exactly the pinned vendor files, else what
     * differs first.
     */
    String vendorMismatch(List<LocalManifest.Entry> entries) {
        if (entries.size() != vendorFiles.size()) {
            return entries.size() + " vendor files listed, " + vendorFiles.size() + " pinned";
        }
        for (LocalManifest.Entry e : entries) {
            LocalManifest.Entry pin = vendorFiles.get(e.path);
            if (pin == null) return e.path + " is not a pinned file";
            if (pin.size != e.size || !pin.sha256.equals(e.sha256)) return e.path + " is not the pinned file";
        }
        return null;
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
