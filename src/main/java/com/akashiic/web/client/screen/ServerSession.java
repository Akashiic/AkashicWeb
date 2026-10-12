package com.akashiic.web.client.screen;

import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.protocol.Protocol;

/**
 * What the server told this client in S2CHello. No browser is created before the hello arrives (the site policy and
 * the kill switch come with it), nor when the protocol major differs or the kill switch is on. The hello survives
 * world changes (a dimension hop does not resend it) and is cleared on disconnect. After a proxy transfer the old
 * server's policy stays in force until the new server's hello replaces it; the screens themselves are torn down with
 * the old world. Client thread only. Pure; tested in ServerSessionTest.
 */
public final class ServerSession {

    private boolean helloReceived;
    private boolean killSwitch;
    private boolean protocolOk = true;
    private int protoMajor;
    private int protoMinor;
    private SitePolicy policy;

    public void onHello(int protoMajor, int protoMinor, boolean killSwitch, SitePolicy policy) {
        this.helloReceived = true;
        this.protoMajor = protoMajor;
        this.protoMinor = protoMinor;
        this.protocolOk = Protocol.compatible(protoMajor);
        this.killSwitch = killSwitch;
        this.policy = policy;
    }

    public void reset() {
        helloReceived = false;
        killSwitch = false;
        protocolOk = true;
        policy = null;
    }

    public boolean allowsBrowsers() {
        return helloReceived && protocolOk && !killSwitch;
    }

    public boolean helloReceived() {
        return helloReceived;
    }

    public boolean killSwitch() {
        return killSwitch;
    }

    public boolean protocolOk() {
        return protocolOk;
    }

    /** Null until the hello arrives. */
    public SitePolicy policy() {
        return policy;
    }

    @Override
    public String toString() {
        if (!helloReceived) return "no hello";
        return "protocol " + protoMajor
            + "."
            + protoMinor
            + (protocolOk ? "" : " (INCOMPATIBLE)")
            + (killSwitch ? " KILL SWITCH" : "")
            + (policy == null ? "" : " policy " + policy.mode);
    }
}
