package com.akashiic.web.client.install;

import java.io.IOException;

/**
 * A classified installer failure. {@link #retryable} tells the download loop whether another attempt can help (a
 * dropped connection can, a hash mismatch cannot). The message is a short English detail for the log and
 * {@code /web doctor}; it never contains signed URLs.
 */
public final class InstallException extends IOException {

    private static final long serialVersionUID = 1L;

    public final InstallFailure failure;
    /** The detail without the failure prefix that {@link #getMessage()} carries. */
    public final String detail;
    public final boolean retryable;

    public InstallException(InstallFailure failure, String detail) {
        this(failure, detail, false, null);
    }

    public InstallException(InstallFailure failure, String detail, Throwable cause) {
        this(failure, detail, false, cause);
    }

    public InstallException(InstallFailure failure, String detail, boolean retryable, Throwable cause) {
        super(failure + ": " + detail, cause);
        this.failure = failure;
        this.detail = detail;
        this.retryable = retryable;
    }
}
