package com.akashiic.web.common.net;

import java.util.LinkedHashMap;
import java.util.Map;

import io.netty.buffer.ByteBuf;

/**
 * C2S 24: opt-in diagnostics (os, arch, java, launcher, gpu, ram, engine state) for {@code /webadmin doctor}. Kept in
 * memory only, never persisted (P-09: LGPD text and retention pending with the owner).
 */
public final class C2SPreflight extends AkwMessage {

    public Map<String, String> info = new LinkedHashMap<>();

    public C2SPreflight() {}

    public C2SPreflight(Map<String, String> info) {
        this.info = new LinkedHashMap<>(info);
    }

    @Override
    protected void read(ByteBuf b) {
        info = NetCodec.readMap(b);
    }

    @Override
    protected void write(ByteBuf b) {
        NetCodec.writeMap(b, info);
    }
}
