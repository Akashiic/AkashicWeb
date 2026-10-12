package com.akashiic.web.protocol;

/**
 * Version of the AkashicWeb network protocol between server and clients. The major number changes only on
 * incompatible packet changes; Forge accepts any remote AkashicWeb with the same major, so server-only or
 * client-only fixes do not force everyone to update (ADR-009, ADR-010).
 */
public final class Protocol {

    public static final int MAJOR = 1;
    public static final int MINOR = 0;

    /**
     * Value for {@code @Mod(acceptableRemoteVersions)}. Forge's own version check is disabled ("*") because our
     * version strings come from git and do not fit a Maven range; compatibility is decided by {@link #MAJOR} in the
     * S2CHello handshake instead. A client without the mod is still refused by Forge's block/item registry sync.
     */
    public static final String ACCEPTABLE_REMOTE_VERSIONS = "*";

    private Protocol() {}

    /** True when a remote side speaking {@code remoteMajor} can talk to us. */
    public static boolean compatible(int remoteMajor) {
        return remoteMajor == MAJOR;
    }
}
