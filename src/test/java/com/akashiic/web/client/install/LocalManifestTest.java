package com.akashiic.web.client.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Startup (sizes) and full (hash) checks of an installed engine folder. */
class LocalManifestTest {

    @TempDir
    File dir;

    private LocalManifest manifest;

    private void put(String rel, byte[] bytes) throws Exception {
        File f = new File(dir, rel);
        f.getParentFile()
            .mkdirs();
        Files.write(f.toPath(), bytes);
    }

    @BeforeEach
    void install() throws Exception {
        put("AkashicWebHost.exe", TestSupport.random(5000, 1));
        put("ffmpeg.dll", TestSupport.random(3000, 2));
        put("locales/pt-BR.pak", TestSupport.random(100, 3));
        put("resources/app/package.json", "{}".getBytes("UTF-8"));
        put("resources/app/src/main.js", "x".getBytes("UTF-8"));
        List<LocalManifest.Entry> entries = new ArrayList<>();
        for (String rel : TestSupport.listFiles(dir)) {
            File f = new File(dir, rel);
            entries.add(new LocalManifest.Entry(rel, f.length(), Sha256.of(f)));
        }
        manifest = new LocalManifest("electron-44.7.0-win32-x64", EngineManifest.WIN32_X64.sha256, entries);
        manifest.write(dir);
    }

    @Test
    void roundTripAndIntactFolderPasses() throws Exception {
        LocalManifest read = LocalManifest.read(dir);
        assertEquals(manifest.render(), read.render());
        assertEquals(manifest.digest(), read.digest());
        assertNull(read.quickCheck(dir, "AkashicWebHost.exe"));
        assertNull(read.fullCheck(dir, "AkashicWebHost.exe"));
        assertNull(read.hostAppCheck(dir));
        assertEquals(
            3,
            read.vendorEntries()
                .size());
    }

    @Test
    void quickCheckSeesSizesButNotSameSizeEdits() throws Exception {
        byte[] dll = Files.readAllBytes(new File(dir, "ffmpeg.dll").toPath());
        dll[10] ^= 1;
        put("ffmpeg.dll", dll);
        assertNull(manifest.quickCheck(dir, "AkashicWebHost.exe"), "sizes only at startup (ADR-011)");
        LocalManifest.Problem p = manifest.fullCheck(dir, "AkashicWebHost.exe");
        assertEquals(InstallFailure.HASH_MISMATCH, p.failure);
        assertTrue(p.detail.contains("ffmpeg.dll"), p.detail);
        assertFalse(p.hostAppOnly);
        put("ffmpeg.dll", new byte[10]);
        assertEquals(InstallFailure.HASH_MISMATCH, manifest.quickCheck(dir, "AkashicWebHost.exe").failure);
    }

    @Test
    void quarantinedExecutableReadsAsAntivirus() throws Exception {
        assertTrue(new File(dir, "AkashicWebHost.exe").delete());
        assertEquals(InstallFailure.AV_BLOCKED, manifest.quickCheck(dir, "AkashicWebHost.exe").failure);
        put("AkashicWebHost.exe", new byte[0]); // some products truncate instead of deleting
        assertEquals(InstallFailure.AV_BLOCKED, manifest.quickCheck(dir, "AkashicWebHost.exe").failure);
    }

    @Test
    void plantedFilesFailTheCheck() throws Exception {
        for (String planted : Arrays.asList("version.dll", "resources/app.asar", "resources/app/src/extra.js")) {
            put(planted, new byte[] { 1 });
            LocalManifest.Problem p = manifest.quickCheck(dir, "AkashicWebHost.exe");
            assertEquals(InstallFailure.HASH_MISMATCH, p.failure, planted);
            assertTrue(p.detail.contains(planted), p.detail);
            assertTrue(new File(dir, planted).delete());
        }
    }

    @Test
    void logsAndOurOwnTempFilesAreIgnored() throws Exception {
        put("debug.log", new byte[] { 1 });
        put("akw-manifest.sha256.tmp-1f", new byte[] { 1 });
        put("resources/.akw-app-new-12/package.json", new byte[] { 1 });
        assertNull(manifest.quickCheck(dir, "AkashicWebHost.exe"));
    }

    @Test
    void hostAppProblemsAreFlaggedAsRepairableFromTheJar() throws Exception {
        put("resources/app/src/main.js", "y".getBytes("UTF-8"));
        LocalManifest.Problem p = manifest.fullCheck(dir, "AkashicWebHost.exe");
        assertTrue(p.hostAppOnly);
        assertEquals(InstallFailure.HASH_MISMATCH, manifest.hostAppCheck(dir).failure);
        assertTrue(new File(dir, "resources/app/src/main.js").delete());
        assertTrue(manifest.quickCheck(dir, "AkashicWebHost.exe").hostAppOnly);
    }

    @Test
    void malformedManifestIsHashMismatch() throws Exception {
        for (String text : new String[] { "", "# AkashicWeb engine manifest v1\n",
            "# AkashicWeb engine manifest v1\n# engine x\n# zip-sha256 y\nnot a line\n",
            "# AkashicWeb engine manifest v1\n# engine x\n# zip-sha256 y\n" + EngineManifest.WIN32_X64.sha256
                + " 12 ../escape\n" }) {
            Files.write(new File(dir, LocalManifest.FILE).toPath(), text.getBytes("UTF-8"));
            InstallException e = assertThrows(InstallException.class, () -> LocalManifest.read(dir), text);
            assertEquals(InstallFailure.HASH_MISMATCH, e.failure);
        }
    }
}
