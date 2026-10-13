package com.akashiic.web.client.install;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes the Windows refusals behind a failed host launch from the launch error text. Java reports a
 * CreateProcess failure as {@code Cannot run program "...": CreateProcess error=<code>, <message>}; the code is
 * language-independent, the message is not. Codes from winerror.h:
 * <ul>
 * <li>App Control (Smart App Control, WDAC): 4550-4559 ({@code ERROR_SYSTEM_INTEGRITY_*}, e.g. 4551 POLICY_VIOLATION,
 * 4556 REPUTATION_MALICIOUS, 4559 REPUTATION_OFFLINE) and 4580-4583 (more reputation verdicts); Software Restriction
 * Policies / AppLocker: 1260 {@code ERROR_ACCESS_DISABLED_BY_POLICY} and 786
 * {@code ERROR_ACCESS_DISABLED_NO_SAFER_UI_BY_POLICY}. All become {@link InstallFailure#SAC_BLOCKED}.</li>
 * <li>Antivirus: 225 {@code ERROR_VIRUS_INFECTED}, 226 {@code ERROR_VIRUS_DELETED}:
 * {@link InstallFailure#AV_BLOCKED}.</li>
 * </ul>
 * Mapping INFERRED from the Windows SDK definitions; not observed on a real Windows machine yet.
 */
final class LaunchFailures {

    private static final Pattern CODE = Pattern.compile("error=(\\d{1,5})");

    private LaunchFailures() {}

    /** The installer failure a launch error stands for, or null when it is not a Windows block. */
    static InstallFailure classify(String launchError) {
        if (launchError == null) return null;
        Matcher m = CODE.matcher(launchError);
        while (m.find()) {
            int code = Integer.parseInt(m.group(1));
            if ((code >= 4550 && code <= 4559) || (code >= 4580 && code <= 4583) || code == 1260 || code == 786) {
                return InstallFailure.SAC_BLOCKED;
            }
            if (code == 225 || code == 226) return InstallFailure.AV_BLOCKED;
        }
        return null;
    }

    /** A short detail for the placeholder and /web doctor. */
    static String describe(InstallFailure f, String launchError) {
        Matcher m = CODE.matcher(launchError == null ? "" : launchError);
        String code = m.find() ? " (CreateProcess error=" + m.group(1) + ")" : "";
        return f == InstallFailure.SAC_BLOCKED ? "Windows App Control blocked the browser engine" + code
            : "an antivirus blocked the browser engine" + code;
    }
}
