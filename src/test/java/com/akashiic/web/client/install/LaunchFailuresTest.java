package com.akashiic.web.client.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Windows CreateProcess refusals behind a failed host launch (review finding 0, codes from winerror.h). */
class LaunchFailuresTest {

    private static String createProcess(int code) {
        return "java.io.IOException: Cannot run program \"C:\\x\\AkashicWebHost.exe\": CreateProcess error=" + code
            + ", localized text";
    }

    @Test
    void appControlCodesAreSacBlocked() {
        for (int code : new int[] { 4550, 4551, 4556, 4559, 4580, 4583, 1260, 786 }) {
            assertEquals(InstallFailure.SAC_BLOCKED, LaunchFailures.classify(createProcess(code)), "error=" + code);
        }
    }

    @Test
    void virusCodesAreAvBlocked() {
        assertEquals(InstallFailure.AV_BLOCKED, LaunchFailures.classify(createProcess(225)));
        assertEquals(InstallFailure.AV_BLOCKED, LaunchFailures.classify(createProcess(226)));
    }

    @Test
    void otherFailuresAreNotClassified() {
        for (int code : new int[] { 2, 5, 193, 740, 4549, 4560, 4579, 4584 }) {
            assertNull(LaunchFailures.classify(createProcess(code)), "error=" + code);
        }
        assertNull(LaunchFailures.classify(null));
        assertNull(LaunchFailures.classify("exited before connecting"));
        assertEquals(
            InstallFailure.SAC_BLOCKED,
            LaunchFailures.classify("first error=5, then CreateProcess error=4551"),
            "any code in the text counts");
    }

    @Test
    void theDetailNamesTheCode() {
        assertTrue(
            LaunchFailures.describe(InstallFailure.SAC_BLOCKED, createProcess(4551))
                .contains("error=4551"));
        assertTrue(
            LaunchFailures.describe(InstallFailure.AV_BLOCKED, null)
                .contains("antivirus"));
    }
}
