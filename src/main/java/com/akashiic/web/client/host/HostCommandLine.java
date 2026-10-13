package com.akashiic.web.client.host;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Argument list of one host launch (docs/PROTOCOL.md section 1, step 2). The token is never an argument: argv is
 * visible to every local user in the process list, so it goes through stdin (or the child-only environment).
 */
public final class HostCommandLine {

    private HostCommandLine() {}

    /**
     * {@code prefix} is the executable (plus fixed arguments, used by tests to run a fake host on the JVM). No
     * {@code --inspect} or sandbox switches are ever added here.
     */
    public static List<String> build(List<String> prefix, int port, long parentPid, File userData, File log) {
        List<String> args = new ArrayList<>(prefix.size() + 4);
        args.addAll(prefix);
        args.add("--akw-port=" + port);
        args.add("--akw-parent-pid=" + Math.max(0, parentPid));
        args.add("--akw-user-data=" + userData.getAbsolutePath());
        args.add("--akw-log=" + log.getAbsolutePath());
        return args;
    }

    /**
     * This JVM's pid, which the host polls to quit when the game dies; 0 when unknown (the host then skips polling).
     */
    public static long currentPid() {
        try {
            return parsePid(
                ManagementFactory.getRuntimeMXBean()
                    .getName());
        } catch (RuntimeException e) {
            // Boundary: a locked-down JVM may refuse the management bean; the socket EOF still ends the host.
            return 0;
        }
    }

    /** Parses the leading digits of RuntimeMXBean#getName ("12345@hostname" on HotSpot and OpenJ9); 0 if none. */
    static long parsePid(String runtimeName) {
        if (runtimeName == null) return 0;
        int at = runtimeName.indexOf('@');
        String digits = at < 0 ? runtimeName : runtimeName.substring(0, at);
        if (digits.isEmpty() || digits.length() > 18) return 0;
        for (int i = 0; i < digits.length(); i++) if (!Character.isDigit(digits.charAt(i))) return 0;
        return Long.parseLong(digits);
    }
}
