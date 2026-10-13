package com.akashiic.web.client.install;

import com.akashiic.web.common.ReasonCode;

/**
 * Why the engine is not READY. Every installer failure carries exactly one of these, and each maps to a
 * {@link ReasonCode} (owned by M4) so the placeholder and {@code /web doctor} show a translated text. Nothing in this
 * package fails without one (ADR-011 "New reason codes").
 */
public enum InstallFailure {

    /** Network/HTTP failure after every retry, or a missing release asset. */
    DOWNLOAD_FAILED(ReasonCode.DOWNLOAD_FAILED),
    /**
     * The bytes are not the pinned ones: SHA-256 or size of the zip or of an installed file, a malformed or unsafe
     * archive (zip-slip), installed files that changed since the install, or a damaged mod jar (the detail then starts
     * with "mod jar").
     */
    HASH_MISMATCH(ReasonCode.HASH_MISMATCH),
    /** Less than {@link EngineManifest#MIN_FREE_BYTES} free, or a write failed for lack of space. */
    DISK_FULL(ReasonCode.DISK_FULL),
    /** HTTPS could not be established: no trust layer accepted the certificate, or the TLS handshake failed. */
    TLS_FAILED(ReasonCode.TLS_FAILED),
    /** A file we wrote vanished, shrank or could not be written/renamed: the usual signature of an antivirus. */
    AV_BLOCKED(ReasonCode.AV_BLOCKED),
    /** Windows Smart App Control (or another App Control policy) blocks the unsigned vendor exe (ADR-011). */
    SAC_BLOCKED(ReasonCode.SAC_BLOCKED),
    /** macOS, Android/ForbiddenMobile/Pojav, Linux without a desktop session, or any other OS (ADR-014). */
    UNSUPPORTED_OS(ReasonCode.UNSUPPORTED_OS),
    /** arm64, 32-bit, or a 32-bit JVM on 64-bit Windows (ADR-014). */
    UNSUPPORTED_ARCH(ReasonCode.UNSUPPORTED_ARCH),
    /**
     * No install folder could be created, or a file in it could not be created, written or renamed while space was
     * left (permissions, Controlled Folder Access, a read-only profile).
     */
    INSTALL_DIR_NOT_WRITABLE(ReasonCode.INSTALL_DIR_NOT_WRITABLE),
    /**
     * A folder that must be replaced is held open by another process, usually the host of another game instance
     * (Windows refuses to rename a folder with open files).
     */
    ENGINE_IN_USE(ReasonCode.ENGINE_IN_USE);

    public final ReasonCode reason;

    InstallFailure(ReasonCode reason) {
        this.reason = reason;
    }
}
