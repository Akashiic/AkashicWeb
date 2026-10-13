package com.akashiic.web.client.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The engine folder store: promotion of a staged install and what its failures are reported as. */
class EngineStoreTest {

    @TempDir
    File root;

    private File dir(String name) {
        File d = new File(root, name);
        assertTrue(d.mkdirs() || d.isDirectory());
        return d;
    }

    @Test
    void anOldFolderHeldOpenByAnotherProcessIsEngineInUse() {
        File staging = dir(".staging-x");
        File finalDir = dir("electron-44.7.0-win32-x64-eee30dc8");
        // Windows refuses to rename a folder while another process (the other instance's host) has files open in it.
        EngineStore store = new EngineStore(root, (from, to) -> {
            if (from.equals(finalDir)) throw new FileSystemException(from.getPath(), to.getPath(), "in use");
            EngineStore.moveDir(from, to);
        });
        InstallException e = assertThrows(InstallException.class, () -> store.promote(staging, finalDir));
        assertEquals(InstallFailure.ENGINE_IN_USE, e.failure);
        assertTrue(staging.isDirectory(), "nothing was swapped");
    }

    @Test
    void aStagingFolderThatCannotBeRenamedIsAvBlocked() {
        File staging = dir(".staging-y");
        EngineStore store = new EngineStore(root, (from, to) -> { throw new AccessDeniedException(from.getPath()); });
        InstallException e = assertThrows(
            InstallException.class,
            () -> store.promote(staging, new File(root, "electron-44.7.0-linux-x64-3ae7d5bd")));
        assertEquals(InstallFailure.AV_BLOCKED, e.failure);
    }

    @Test
    void aMarkerThatCannotBeWrittenIsInstallDirNotWritable() throws Exception {
        // A regular file where the engine folder should be makes the write fail even for root (tests run as root).
        File dir = new File(root, "engine");
        assertTrue(dir.createNewFile());
        LocalManifest m = new LocalManifest(
            "electron-44.7.0-linux-x64",
            EngineManifest.LINUX_X64.sha256,
            java.util.Collections.singletonList(new LocalManifest.Entry("x", 1, EngineManifest.LINUX_X64.sha256)));
        InstallException e = assertThrows(
            InstallException.class,
            () -> new EngineStore(root).writeMarker(dir, m, "x", 1L));
        assertEquals(InstallFailure.INSTALL_DIR_NOT_WRITABLE, e.failure);
    }
}
