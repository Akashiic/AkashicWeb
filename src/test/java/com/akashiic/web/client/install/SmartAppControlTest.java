package com.akashiic.web.client.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Smart App Control state from reg.exe output (ADR-011 reviewer amendment). Canned outputs; no Windows needed. */
class SmartAppControlTest {

    private static String regOutput(String hex) {
        return "\r\nHKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\CI\\Policy\r\n"
            + "    VerifiedAndReputablePolicyState    REG_DWORD    0x"
            + hex
            + "\r\n\r\n";
    }

    @Test
    void parsesTheThreeDocumentedValues() {
        assertEquals(SmartAppControl.State.OFF, SmartAppControl.parse(0, regOutput("0")));
        assertEquals(SmartAppControl.State.ENFORCE, SmartAppControl.parse(0, regOutput("1")));
        assertEquals(SmartAppControl.State.EVALUATION, SmartAppControl.parse(0, regOutput("2")));
        assertEquals(SmartAppControl.State.UNKNOWN, SmartAppControl.parse(0, regOutput("7")));
    }

    @Test
    void missingValueMeansNoSmartAppControl() {
        // Windows 10: reg.exe exits 1 with a localized message (pt-BR here).
        assertEquals(
            SmartAppControl.State.OFF,
            SmartAppControl.parse(1, "ERRO: o sistema n\u00e3o conseguiu localizar a chave ou o valor.\r\n"));
    }

    @Test
    void anythingElseIsUnknownAndDoesNotBlock() {
        assertEquals(SmartAppControl.State.UNKNOWN, SmartAppControl.parse(0, "garbage"));
        assertEquals(SmartAppControl.State.UNKNOWN, SmartAppControl.parse(5, ""));
        assertEquals(
            SmartAppControl.State.UNKNOWN,
            SmartAppControl.query((c, d, t) -> { throw new IOException("reg.exe missing"); }, "C:\\Windows"));
    }

    @Test
    void queriesRegExeByAbsolutePath() {
        List<List<String>> seen = new ArrayList<>();
        SmartAppControl.State s = SmartAppControl.query((cmd, dir, timeout) -> {
            seen.add(cmd);
            return new CommandRunner.Result(0, regOutput("1"));
        }, "D:\\Win");
        assertEquals(SmartAppControl.State.ENFORCE, s);
        List<String> cmd = seen.get(0);
        assertTrue(
            cmd.get(0)
                .startsWith("D:\\Win")
                && cmd.get(0)
                    .endsWith("reg.exe"),
            cmd.get(0));
        assertTrue(cmd.contains("HKLM\\SYSTEM\\CurrentControlSet\\Control\\CI\\Policy"));
        assertTrue(cmd.contains("VerifiedAndReputablePolicyState"));
    }
}
