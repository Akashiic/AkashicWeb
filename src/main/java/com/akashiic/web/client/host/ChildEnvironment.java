package com.akashiic.web.client.host;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The host's environment, built explicitly (ADR-001 reviewer amendment). Stock Electron honours NODE_OPTIONS,
 * ELECTRON_RUN_AS_NODE and friends, so a player with a developer setup or another launcher's variables would get a
 * broken or debuggable host. We copy the parent's environment minus every {@code NODE_*} and {@code ELECTRON_*}
 * variable, compared case-insensitively because Windows treats {@code Node_Options} as {@code NODE_OPTIONS}.
 */
public final class ChildEnvironment {

    /** Token fallback for hosts that cannot read stdin; set only in the child's environment. */
    public static final String TOKEN_VARIABLE = "AKW_TOKEN";

    private ChildEnvironment() {}

    public static Map<String, String> build(Map<String, String> parent, String token) {
        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : parent.entrySet()) {
            String key = e.getKey();
            if (key == null || e.getValue() == null || isRemoved(key)) continue;
            env.put(key, e.getValue());
        }
        env.put(TOKEN_VARIABLE, token);
        return env;
    }

    /** True for names the host must never inherit, including a stale AKW_TOKEN of a parent launched by a host. */
    public static boolean isRemoved(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("NODE_") || upper.startsWith("ELECTRON_") || upper.equals(TOKEN_VARIABLE);
    }
}
