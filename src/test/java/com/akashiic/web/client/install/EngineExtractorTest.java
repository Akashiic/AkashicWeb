package com.akashiic.web.client.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Extraction rules (ADR-011, ADR-001 amendment) on zips shaped like the official ones. */
class EngineExtractorTest {

    @TempDir
    File tmp;

    private final EngineExtractor extractor = new EngineExtractor(f -> Long.MAX_VALUE);

    private File writeZip(Map<String, byte[]> entries) throws Exception {
        File zip = new File(tmp, "engine.zip");
        Files.write(zip.toPath(), TestSupport.zip(entries));
        return zip;
    }

    private EngineTarget winTarget(File zip) throws Exception {
        return TestSupport.target("win32-x64", "http://127.0.0.1/x", Files.readAllBytes(zip.toPath()), true);
    }

    @Test
    void windowsLayoutIsPrunedRenamedAndHashed() throws Exception {
        Map<String, byte[]> entries = TestSupport.windowsLikeEntries(1);
        File zip = writeZip(entries);
        File staging = new File(tmp, "staging");
        EngineExtractor.Result r = extractor.extract(zip, winTarget(zip), staging, ProgressListener.NONE);

        List<String> files = TestSupport.listFiles(staging);
        assertEquals(
            Arrays.asList("AkashicWebHost.exe", "LICENSE", "LICENSES.chromium.html", "ffmpeg.dll", "locales/en-US.pak",
                "locales/pt-BR.pak", "resources.pak", "version"),
            files);
        // Renamed, not modified: the exe keeps the vendor's exact bytes (and so its hash and reputation).
        byte[] exe = Files.readAllBytes(new File(staging, "AkashicWebHost.exe").toPath());
        assertArrayEquals(entries.get("electron.exe"), exe);
        assertFalse(new File(staging, "electron.exe").exists());
        assertFalse(new File(staging, "resources/default_app.asar").exists());
        assertEquals(4, r.skipped, "de, fr, pt-PT locales and default_app.asar");
        assertEquals(8, r.entries.size());
        for (LocalManifest.Entry e : r.entries) {
            File f = new File(staging, e.path);
            assertEquals(f.length(), e.size, e.path);
            assertEquals(Sha256.of(f), e.sha256, e.path);
        }
    }

    @Test
    void linuxExecutablesGetTheExecuteBit() throws Exception {
        Map<String, byte[]> entries = TestSupport.linuxLikeEntries(2);
        File zip = writeZip(entries);
        EngineTarget t = TestSupport.target("linux-x64", "http://x", Files.readAllBytes(zip.toPath()), false);
        File staging = new File(tmp, "staging");
        extractor.extract(zip, t, staging, ProgressListener.NONE);
        assertTrue(new File(staging, "AkashicWebHost").canExecute());
        assertTrue(new File(staging, "chrome_crashpad_handler").canExecute());
        assertTrue(new File(staging, "chrome-sandbox").exists());
        assertFalse(new File(staging, "electron").exists());
        assertEquals(Arrays.asList("AkashicWebHost", "chrome-sandbox", "chrome_crashpad_handler", "libffmpeg.so",
            "locales/en-US.pak", "locales/pt-BR.pak"), TestSupport.listFiles(staging));
    }

    @Test
    void zipSlipEntryIsRejectedAndNothingEscapes() throws Exception {
        for (String evil : new String[] { "../evil.txt", "locales/../../evil.txt", "/tmp/evil.txt",
            "a\\..\\..\\evil.txt",
            "C:/evil.txt", "resources/../../evil.txt" }) {
            Map<String, byte[]> entries = TestSupport.windowsLikeEntries(3);
            entries.put(evil, "pwned".getBytes("UTF-8"));
            File zip = writeZip(entries);
            File staging = new File(tmp, "staging-" + Math.abs(evil.hashCode()));
            InstallException e = assertThrows(
                InstallException.class,
                () -> extractor.extract(zip, winTarget(zip), staging, ProgressListener.NONE),
                evil);
            assertEquals(InstallFailure.HASH_MISMATCH, e.failure, evil);
            assertFalse(new File(tmp, "evil.txt").exists(), evil);
            assertFalse(new File(tmp.getParentFile(), "evil.txt").exists(), evil);
        }
    }

    @Test
    void caseOnlyDuplicatesAreRejected() throws Exception {
        // On Windows "LICENSE" and "license" are one file: a later entry would silently replace an earlier one.
        Map<String, byte[]> entries = TestSupport.windowsLikeEntries(4);
        entries.put("license", new byte[] { 1 });
        File zip = writeZip(entries);
        InstallException e = assertThrows(
            InstallException.class,
            () -> extractor.extract(zip, winTarget(zip), new File(tmp, "s"), ProgressListener.NONE));
        assertEquals(InstallFailure.HASH_MISMATCH, e.failure);
    }

    @Test
    void anArchiveMayNotShadowTheHostExeOrOurApp() throws Exception {
        Map<String, byte[]> entries = TestSupport.windowsLikeEntries(5);
        entries.put("AkashicWebHost.exe", new byte[] { 6, 6, 6 });
        entries.put("resources/app/main.js", "evil()".getBytes("UTF-8"));
        entries.put("resources/app.asar", new byte[] { 1, 2, 3 });
        File zip = writeZip(entries);
        File staging = new File(tmp, "s");
        extractor.extract(zip, winTarget(zip), staging, ProgressListener.NONE);
        byte[] exe = Files.readAllBytes(new File(staging, "AkashicWebHost.exe").toPath());
        assertArrayEquals(entries.get("electron.exe"), exe);
        assertFalse(new File(staging, "resources/app").exists());
        assertFalse(new File(staging, "resources/app.asar").exists());
    }

    @Test
    void archiveWithoutTheVendorExeIsRejected() throws Exception {
        Map<String, byte[]> entries = TestSupport.windowsLikeEntries(6);
        entries.remove("electron.exe");
        File zip = writeZip(entries);
        InstallException e = assertThrows(
            InstallException.class,
            () -> extractor.extract(zip, winTarget(zip), new File(tmp, "s"), ProgressListener.NONE));
        assertEquals(InstallFailure.HASH_MISMATCH, e.failure);
    }

    @Test
    void notAZipIsHashMismatch() throws Exception {
        File zip = new File(tmp, "x.zip");
        Files.write(zip.toPath(), "<html>captive portal</html>".getBytes("UTF-8"));
        EngineTarget t = TestSupport.target("win32-x64", "http://x", Files.readAllBytes(zip.toPath()), true);
        InstallException e = assertThrows(
            InstallException.class,
            () -> extractor.extract(zip, t, new File(tmp, "s"), ProgressListener.NONE));
        assertEquals(InstallFailure.HASH_MISMATCH, e.failure);
    }

    @Test
    void writeFailureWithoutSpaceIsDiskFull() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("electron.exe", new byte[] { 1 });
        File zip = writeZip(entries);
        File staging = new File(tmp, "s");
        // A folder where the exe should go makes the write fail; with no free space that reads as DISK_FULL.
        new File(staging, "AkashicWebHost.exe").mkdirs();
        EngineExtractor full = new EngineExtractor(f -> 0L);
        InstallException e = assertThrows(
            InstallException.class,
            () -> full.extract(zip, winTarget(zip), staging, ProgressListener.NONE));
        assertEquals(InstallFailure.DISK_FULL, e.failure);
        // ...and with space left, a failed .exe write is what an antivirus block looks like.
        InstallException av = assertThrows(
            InstallException.class,
            () -> extractor.extract(zip, winTarget(zip), staging, ProgressListener.NONE));
        assertEquals(InstallFailure.AV_BLOCKED, av.failure);
    }

    @Test
    void installedPathMapping() throws Exception {
        EngineTarget t = EngineManifest.WIN32_X64;
        assertEquals("AkashicWebHost.exe", EngineExtractor.installedPath("electron.exe", t));
        assertNull(EngineExtractor.installedPath("AkashicWebHost.exe", t));
        assertEquals("locales/pt-BR.pak", EngineExtractor.installedPath("locales/pt-BR.pak", t));
        assertEquals("locales/en-US.pak", EngineExtractor.installedPath("locales/en-US.pak", t));
        assertNull(EngineExtractor.installedPath("locales/en-GB.pak", t));
        assertNull(EngineExtractor.installedPath("locales/pt-PT.pak", t));
        assertNull(EngineExtractor.installedPath("resources/default_app.asar", t));
        assertNull(EngineExtractor.installedPath("resources/app/x.js", t));
        assertEquals("d3dcompiler_47.dll", EngineExtractor.installedPath("d3dcompiler_47.dll", t));
        assertEquals("AkashicWebHost", EngineExtractor.installedPath("electron", EngineManifest.LINUX_X64));
    }
}
