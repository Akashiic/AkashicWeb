package com.akashiic.web.client.install;

import java.io.File;

/**
 * The exact facts the pt-BR consent screen shows (ADR-006 "Consent screen"): which component, from which publisher
 * and host, how many bytes are downloaded and how many stay on disk, and where. No legal claims. Immutable.
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
    /** Install folder, for display only; null when not resolved yet. */
    public final File installDir;

    public ConsentInfo(String component, String publisher, String sourceHost, long downloadBytes, long diskBytes,
        long requiredFreeBytes, File installDir) {
        this.component = component;
        this.publisher = publisher;
        this.sourceHost = sourceHost;
        this.downloadBytes = downloadBytes;
        this.diskBytes = diskBytes;
        this.requiredFreeBytes = requiredFreeBytes;
        this.installDir = installDir;
    }

    static ConsentInfo of(EngineTarget t, File installDir) {
        String host;
        try {
            host = new java.net.URL(t.url).getHost();
        } catch (java.net.MalformedURLException e) {
            host = t.url;
        }
        return new ConsentInfo(
            "Electron " + t.version + " (Chromium " + EngineManifest.CHROMIUM_VERSION + ")",
            "Electron project / OpenJS Foundation (official release build)",
            host,
            t.size,
            EngineManifest.keptBytes(t),
            EngineManifest.MIN_FREE_BYTES,
            installDir);
    }
}
