package com.akashiic.web.client.install;

import com.akashiic.web.common.ReasonCode;

/**
 * Why the engine is not READY. Every installer failure carries exactly one of these, and each maps 1:1 to a
 * {@link ReasonCode} (owned by M4) so the placeholder and {@code /web doctor} show a translated text. Nothing in this
 * package fails without one (ADR-011 "New reason codes").
 */
public enum InstallFailure {

    /** Network/HTTP failure after every retry, a missing release asset, or an install folder that cannot be written. */
    DOWNLOAD_FAILED(ReasonCode.DOWNLOAD_FAILED),
    /**
     * The bytes are not the pinned ones: SHA-256 or size of the zip, a malformed or unsafe archive (zip-slip), a host
     * app in the jar that does not match its manifest, or installed files that changed since the install.
     */
    HASH_MISMATCH(ReasonCode.HASH_MISMATCH),
    /** Less than {@link EngineManifest#MIN_FREE_BYTES} free, or a write failed for lack of space. */
    DISK_FULL(ReasonCode.DISK_FULL),
    /** HTTPS could not be established with the JRE trust store nor with the installer's bundled roots. */
    TLS_FAILED(ReasonCode.TLS_FAILED),
    /** A file we wrote vanished, shrank or could not be written/renamed: the usual signature of an antivirus. */
    AV_BLOCKED(ReasonCode.AV_BLOCKED),
    /** Windows Smart App Control is in enforce mode, so the unsigned vendor exe would be blocked (ADR-011). */
    SAC_BLOCKED(ReasonCode.SAC_BLOCKED),
    /** macOS, Android/ForbiddenMobile/Pojav, Linux without a desktop session, or any other OS (ADR-014). */
    UNSUPPORTED_OS(ReasonCode.UNSUPPORTED_OS),
    /** arm64, 32-bit, or a 32-bit JVM on 64-bit Windows (ADR-014). */
    UNSUPPORTED_ARCH(ReasonCode.UNSUPPORTED_ARCH);

    public final ReasonCode reason;

    InstallFailure(ReasonCode reason) {
        this.reason = reason;
    }
}
