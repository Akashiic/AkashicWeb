package com.akashiic.web.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cpw.mods.fml.relauncher.FMLInjectionData;

/**
 * Review finding: Forge's Configuration catches every load error, renames the file to *.errored and loads defaults,
 * so a typo followed by /webadmin policy reload turned the kill switch off and lost the site lists, silently. Runs
 * the real Forge Configuration on a temporary directory.
 */
class WebConfigTest {

    @TempDir
    File dir;

    private File cfg;
    private Object oldHome;

    @BeforeEach
    void setUp() throws Exception {
        Field home = FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true);
        oldHome = home.get(null);
        home.set(null, dir); // Configuration's constructor reads the game directory from FML
        cfg = new File(dir, "akashicweb.cfg");
    }

    @AfterEach
    void tearDown() throws Exception {
        WebConfig.forget();
        Field home = FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true);
        home.set(null, oldHome);
    }

    private String text() throws Exception {
        return new String(Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8);
    }

    private void write(String s) throws Exception {
        Files.write(cfg.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    private void assertNoErroredCopy() {
        for (String name : dir.list()) assertFalse(name.endsWith(".errored"), "renamed copy: " + name);
    }

    @Test
    void aTypoNeverTurnsTheKillSwitchOffOrTouchesTheFile() throws Exception {
        WebConfig.init(cfg);
        assertFalse(WebConfig.get().killSwitch);
        String on = text().replace("B:killSwitch=false", "B:killSwitch=true")
            .replace("S:mode=ALLOWLIST", "S:mode=BLOCKLIST");
        write(on);
        assertTrue(WebConfig.reload());
        assertTrue(WebConfig.get().killSwitch);
        assertEquals(SitePolicy.Mode.BLOCKLIST, WebConfig.get().sitePolicy.mode);

        String typo = on + "\n@ oops\n";
        write(typo);
        assertFalse(WebConfig.reload(), "the reload is refused");
        assertTrue(WebConfig.get().killSwitch, "the kill switch stays on");
        assertEquals(SitePolicy.Mode.BLOCKLIST, WebConfig.get().sitePolicy.mode, "the previous settings stay");
        assertEquals(typo, text(), "the admin's file is left exactly as it was");
        assertNoErroredCopy();
        assertNotNull(WebConfig.problem());

        assertFalse(WebConfig.setKillSwitch(false), "not saved while the file is broken");
        assertFalse(WebConfig.get().killSwitch, "but it changes for this run");
        assertEquals(typo, text());

        write(on);
        assertTrue(WebConfig.reload());
        assertNull(WebConfig.problem());
        assertTrue(WebConfig.get().killSwitch);
    }

    @Test
    void aBrokenFileAtStartUpFailsClosed() throws Exception {
        byte[] broken = "server {\n    B:killSwitch=false\n    @@@\n".getBytes(StandardCharsets.UTF_8);
        Files.write(cfg.toPath(), broken);
        WebConfig.init(cfg);
        assertTrue(WebConfig.get().killSwitch, "no previous settings to keep: every browser stays off");
        assertArrayEquals(broken, Files.readAllBytes(cfg.toPath()));
        assertNoErroredCopy();
    }

    @Test
    void theKillSwitchKeepsManualEditsMadeSinceTheLastReload() throws Exception {
        WebConfig.init(cfg);
        write(text().replace("S:mode=ALLOWLIST", "S:mode=BLOCKLIST")); // edited, not reloaded
        assertTrue(WebConfig.setKillSwitch(true));
        String saved = text();
        assertTrue(saved.contains("B:killSwitch=true"));
        assertTrue(saved.contains("S:mode=BLOCKLIST"), "the edit is not overwritten by an old copy");
        assertEquals(SitePolicy.Mode.BLOCKLIST, WebConfig.get().sitePolicy.mode);
    }
}
