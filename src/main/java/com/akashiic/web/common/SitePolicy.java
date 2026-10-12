package com.akashiic.web.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server site policy sent to clients in S2CHello and to the host in WELCOME/POLICY. Immutable data; the matching
 * logic (subdomains, internal addresses) is added by M4 next to it and mirrored by host/src/policy.js. Contract type.
 */
public final class SitePolicy {

    public enum Mode {
        ALLOWLIST,
        BLOCKLIST
    }

    public final Mode mode;
    /** Lower-case host names; an entry also matches its subdomains. */
    public final List<String> allow;

    public final List<String> block;

    public SitePolicy(Mode mode, List<String> allow, List<String> block) {
        this.mode = mode;
        this.allow = Collections.unmodifiableList(new ArrayList<>(allow));
        this.block = Collections.unmodifiableList(new ArrayList<>(block));
    }

    public static SitePolicy blocklist(List<String> block) {
        return new SitePolicy(Mode.BLOCKLIST, Collections.<String>emptyList(), block);
    }
}
