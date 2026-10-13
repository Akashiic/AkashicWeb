package com.akashiic.web.client.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Optional checks against the real Electron 44.7.0 artifacts. Skipped by default:
 * <ul>
 * <li>{@code AKW_LIVE_DOWNLOAD=1}: downloads the real linux-x64 zip (122,998,223 B) from github.com through the
 * production HTTPS path (redirect, timeouts, User-Agent, SHA-256 pin), then installs it.</li>
 * <li>{@code AKW_ENGINE_ZIP_DIR=<dir>}: installs whichever official zips are already in that folder (win32-x64 and/or
 * linux-x64), checking pruning, renaming and the kept-byte totals pinned in {@link EngineManifest}.</li>
 * <li>{@code AKW_ENGINE_KEEP_DIR=<dir>} (with either): install there instead of a temporary folder and keep the
 * result, for a manual launch of the installed AkashicWebHost.</li>
 * </ul>
 */
class RealEngineTest {

    @TempDir
    File tmp;

    @Test
    @EnabledIfEnvironmentVariable(named = "AKW_LIVE_DOWNLOAD", matches = "1")
    void liveDownloadOfThePinnedLinuxZip() throws Exception {
        EngineTarget t = EngineManifest.LINUX_X64;
        HttpFetcher http = HttpFetcher.production("live-test", new InstallerTls());
        Downloader d = new Downloader(http, Backoff.DEFAULT, Thread::sleep, File::getUsableSpace, 6);
        File zip = d.download(t, new File(tmp, "download"), ProgressListener.NONE);
        assertEquals(t.size, zip.length());
        assertEquals(t.sha256, Sha256.of(zip));
        installAndCheck(zip, t);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AKW_ENGINE_ZIP_DIR", matches = ".+")
    void installTheOfficialZipsFromDisk() throws Exception {
        File dir = new File(System.getenv("AKW_ENGINE_ZIP_DIR"));
        int found = 0;
        for (EngineTarget t : new EngineTarget[] { EngineManifest.WIN32_X64, EngineManifest.LINUX_X64 }) {
            File zip = ImportScanner.find(dir, t);
            if (zip == null) continue;
            found++;
            installAndCheck(zip, t);
        }
        assertTrue(found > 0, "no official zip with a pinned hash in " + dir);
    }

    private void installAndCheck(File zip, EngineTarget t) throws Exception {
        String keep = System.getenv("AKW_ENGINE_KEEP_DIR");
        File base = new File(keep != null && !keep.isEmpty() ? new File(keep) : tmp, "base-" + t.platform);
        InstallPaths paths = new InstallPaths(base, "test", base.getPath());
        HostApp.Source app = HostApp.classpath(getClass().getClassLoader());
        EngineInstaller installer = new EngineInstaller(
            paths,
            t,
            null,
            app,
            File::getUsableSpace,
            System::currentTimeMillis);
        File engine = installer.installFromZip(zip, ProgressListener.NONE);
        assertEquals(new File(paths.engineRoot(), t.dirName()), engine);

        File exe = new File(engine, t.hostExecutable);
        assertTrue(exe.isFile());
        assertFalse(new File(engine, t.vendorExecutable).exists());
        try (ZipFile zf = new ZipFile(zip); InputStream in = zf.getInputStream(zf.getEntry(t.vendorExecutable))) {
            assertEquals(Sha256.of(in), Sha256.of(exe), "renamed, not modified");
        }
        assertFalse(new File(engine, "resources/default_app.asar").exists());
        String[] locales = new File(engine, "locales").list();
        java.util.Arrays.sort(locales);
        assertEquals(java.util.Arrays.asList("en-US.pak", "pt-BR.pak"), java.util.Arrays.asList(locales));
        assertTrue(new File(engine, "resources/app/package.json").isFile());
        if (!t.windows) assertTrue(exe.canExecute());

        LocalManifest m = LocalManifest.read(engine);
        assertNull(m.fullCheck(engine, t.hostExecutable));
        long vendorBytes = 0;
        for (LocalManifest.Entry e : m.vendorEntries()) vendorBytes += e.size;
        assertEquals(EngineManifest.keptBytes(t), vendorBytes, "pinned kept-byte total for the consent screen");
        List<String> files = TestSupport.listFiles(engine);
        assertTrue(files.contains("LICENSE") && files.contains("LICENSES.chromium.html"), "licence notices kept");
        assertTrue(
            EngineStore.inspect(engine, false)
                .intact());
    }
}
