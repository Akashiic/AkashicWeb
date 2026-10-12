package com.akashiic.web.common.net;

/** A packet broke a length cap or carried an impossible value. The receiver drops it (reason BAD_PACKET). */
public final class CodecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CodecException(String message) {
        super(message);
    }
}
