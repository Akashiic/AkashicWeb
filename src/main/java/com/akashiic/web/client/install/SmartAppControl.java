package com.akashiic.web.client.install;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows Smart App Control state, read before the consent screen (ADR-011 reviewer amendment): the official
 * electron.exe and ffmpeg.dll are unsigned, and under SAC enforcement an unsigned, low-reputation exe is exactly what
 * gets blocked. Learning that after a 158 MB download would waste the player's time, so enforce mode is reported as
 * {@link InstallFailure#SAC_BLOCKED} up front.
 */
public final class SmartAppControl {

    public enum State {
        /** Value 0, or the value does not exist (Windows 10 and older Windows 11 builds have no SAC). */
        OFF,
        /** Value 1: unsigned code without cloud reputation is blocked. */
        ENFORCE,
        /** Value 2: SAC watches and may switch itself to enforce later; we do not block, but /web doctor shows it. */
        EVALUATION,
        /** reg.exe could not run or printed something unexpected; we do not block on a guess. */
        UNKNOWN
    }

    static final String KEY = "HKLM\\SYSTEM\\CurrentControlSet\\Control\\CI\\Policy";
    static final String VALUE = "VerifiedAndReputablePolicyState";
    private static final Pattern DWORD = Pattern
        .compile(VALUE + "\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)", Pattern.CASE_INSENSITIVE);

    private SmartAppControl() {}

    /** Runs {@code %SystemRoot%\System32\reg.exe query ...} (absolute path: no PATH lookup). */
    public static State query(CommandRunner runner, String systemRoot) {
        String root = systemRoot == null ? "C:\\Windows" : systemRoot;
        String reg = root + File.separator + "System32" + File.separator + "reg.exe";
        try {
            CommandRunner.Result r = runner.run(Arrays.asList(reg, "query", KEY, "/v", VALUE), null, 5000);
            return parse(r.exitCode, r.output);
        } catch (IOException e) {
            return State.UNKNOWN;
        }
    }

    /** Pure: maps reg.exe's exit code and output to a state. */
    static State parse(int exitCode, String output) {
        Matcher m = DWORD.matcher(output == null ? "" : output);
        if (exitCode == 0 && m.find()) {
            long v = Long.parseLong(m.group(1), 16);
            if (v == 0) return State.OFF;
            if (v == 1) return State.ENFORCE;
            if (v == 2) return State.EVALUATION;
            return State.UNKNOWN;
        }
        // reg.exe exits 1 when the key or value is absent; its message is localized, so only the code is trusted.
        if (exitCode == 1 && !(output != null && output.toLowerCase(Locale.ROOT)
            .contains("reg_dword"))) {
            return State.OFF;
        }
        return State.UNKNOWN;
    }
}
