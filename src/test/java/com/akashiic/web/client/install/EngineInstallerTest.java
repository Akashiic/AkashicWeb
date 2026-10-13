package com.akashiic.web.client.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One install from a zip, end to end, without the network. */
class EngineInstallerTest {

    @TempDir
    File tmp;

    /**
     * Review finding (medium, 5, mutant M6): bytes we just wrote and hashed cannot legitimately change, so an
     * executable
     * that is gone right after the rename is an on-write antivirus action: AV_BLOCKED, and the folder is not used.
     */
    @Test
    void anExecutableRemovedRightAfterTheRenameIsAvBlocked() throws Exception {
        byte[] zipBytes = TestSupport.zip(TestSupport.linuxLikeEntries(3));
        File zip = new File(tmp, "engine.zip");
        Files.write(zip.toPath(), zipBytes);
        EngineTarget t = TestSupport.target("linux-x64", "http://127.0.0.1/x.zip", zipBytes, false);
        InstallPaths paths = new InstallPaths(new File(tmp, "base"), "test", "base");
        EngineStore scanned = new EngineStore(paths.engineRoot(), (from, to) -> {
            EngineStore.moveDir(from, to);
            if (from.getName()
                .startsWith(EngineStore.STAGING_PREFIX)) {
                assertTrue(new File(to, t.hostExecutable).delete()); // quarantined the moment it became final
            }
        });
        EngineInstaller installer = new EngineInstaller(
            paths,
            t,
            null,
            TestSupport.defaultHostApp(),
            f -> Long.MAX_VALUE,
            () -> 1L,
            scanned);
        InstallException e = assertThrows(
            InstallException.class,
            () -> installer.installFromZip(zip, ProgressListener.NONE));
        assertEquals(InstallFailure.AV_BLOCKED, e.failure);
        assertNull(EngineStore.readMarker(scanned.dirFor(t)), "a folder that failed its re-hash is never used");
    }
}
