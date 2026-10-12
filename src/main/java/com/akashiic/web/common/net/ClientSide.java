package com.akashiic.web.common.net;

import com.akashiic.web.AkashicWeb;

/**
 * Where server-to-client handlers deliver. They run on the client's network thread; on the dedicated server they are
 * instantiated by FML but never called. They reach the client only through the proxy's {@link ClientHooks} (M5),
 * never through client classes.
 */
final class ClientSide {

    private ClientSide() {}

    static ClientHooks hooks() {
        return AkashicWeb.proxy == null ? ClientHooks.NOOP : AkashicWeb.proxy.clientHooks();
    }

    /** True if the message decoded; a bad one is logged and dropped (the server is not trusted blindly either). */
    static boolean accept(AkwMessage m) {
        if (m.ok()) return true;
        AkashicWeb.LOG.warn(
            "AkashicWeb: dropped malformed {} from server: {}",
            m.getClass()
                .getSimpleName(),
            m.malformed);
        return false;
    }
}
