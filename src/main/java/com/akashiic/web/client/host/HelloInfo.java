package com.akashiic.web.client.host;

import java.util.Map;

/**
 * Parsed HELLO from the host (docs/PROTOCOL.md section 1). Version fields are kept for /web doctor; the token is
 * checked by the supervisor and deliberately not kept here so it cannot end up in logs or diagnostics.
 */
public final class HelloInfo {

    /** Longest version/platform string we keep; anything longer is cut so a bad host cannot flood diagnostics. */
    static final int MAX_FIELD = 64;

    public final int protoMajor;
    public final int protoMinor;
    public final String hostVersion;
    public final String engine;
    public final String engineVersion;
    public final String chromeVersion;
    public final String platform;
    /**
     * The host's own process id when it reports one (optional field), else -1. Needed on Java 8 for Windows, where
     * {@link Process} has no portable pid, so the stale-host sweep can still record the PID it may kill later.
     */
    public final long pid;

    HelloInfo(int protoMajor, int protoMinor, String hostVersion, String engine, String engineVersion,
        String chromeVersion, String platform, long pid) {
        this.protoMajor = protoMajor;
        this.protoMinor = protoMinor;
        this.hostVersion = hostVersion;
        this.engine = engine;
        this.engineVersion = engineVersion;
        this.chromeVersion = chromeVersion;
        this.platform = platform;
        this.pid = pid;
    }

    /**
     * Parsed and validated HELLO. {@code tokenOut[0]} receives the presented token (it is compared by the caller in
     * constant time and then dropped).
     */
    static HelloInfo parse(String json, String[] tokenOut) throws HostProtocol.ProtocolException {
        Map<String, Object> o;
        try {
            o = JsonParser.parseObject(json);
        } catch (JsonParser.JsonException e) {
            throw new HostProtocol.ProtocolException("HELLO is not valid JSON: " + e.getMessage());
        }
        String token = JsonParser.string(o, "token");
        if (token == null) throw new HostProtocol.ProtocolException("HELLO without token");
        long major = JsonParser.integer(o, "protoMajor", Long.MIN_VALUE);
        if (major < 0 || major > 0xFFFF) throw new HostProtocol.ProtocolException("HELLO without valid protoMajor");
        long minor = JsonParser.integer(o, "protoMinor", 0);
        long pid = JsonParser.integer(o, "pid", -1);
        tokenOut[0] = token;
        return new HelloInfo(
            (int) major,
            (int) Math.max(0, Math.min(0xFFFF, minor)),
            field(o, "hostVersion"),
            field(o, "engine"),
            field(o, "engineVersion"),
            field(o, "chromeVersion"),
            field(o, "platform"),
            pid > 0 ? pid : -1);
    }

    private static String field(Map<String, Object> o, String key) {
        String v = JsonParser.string(o, key);
        return v == null ? "?" : clean(v, MAX_FIELD);
    }

    /** Host-supplied text made safe for one log line: control characters become '?', length is capped. */
    static String clean(String v, int max) {
        StringBuilder sb = new StringBuilder(Math.min(v.length(), max));
        for (int i = 0; i < v.length() && sb.length() < max; i++) {
            char c = v.charAt(i);
            sb.append(c < 0x20 || c == 0x7F ? '?' : c);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "host " + hostVersion
            + " ("
            + engine
            + " "
            + engineVersion
            + ", Chrome "
            + chromeVersion
            + ", "
            + platform
            + ", protocol "
            + protoMajor
            + "."
            + protoMinor
            + (pid > 0 ? ", pid " + pid : "")
            + ")";
    }
}
