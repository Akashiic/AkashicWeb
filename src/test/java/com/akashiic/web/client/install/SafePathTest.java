package com.akashiic.web.client.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafePathTest {

    @Test
    void acceptsTheNamesInTheOfficialZips() {
        for (String ok : new String[] { "electron.exe", "locales/pt-BR.pak", "resources/default_app.asar",
            "LICENSES.chromium.html", "vk_swiftshader_icd.json", "chrome-sandbox", "libvulkan.so.1", "src/main.js" }) {
            assertTrue(SafePath.isSafeRelative(ok), ok);
        }
    }

    @Test
    void rejectsTraversalAbsoluteAndWindowsTricks() {
        for (String bad : new String[] { "", "../evil", "a/../../evil", "./a", "a/./b", "a//b", "/etc/passwd", "a/",
            "C:/Windows/x", "C:x", "a\\..\\b", "file.txt:stream", "nul", "con.txt", "a/aux.js", "COM1", "trail.",
            "trail ", "a\u0000b", "a\nb", "q?", "x*y" }) {
            assertFalse(SafePath.isSafeRelative(bad), bad);
        }
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < SafePath.MAX_RELATIVE_LENGTH + 1; i++) longName.append('a');
        assertFalse(SafePath.isSafeRelative(longName.toString()));
    }

    @Test
    void resolvesInsideTheRootOnly(@TempDir File root) throws Exception {
        assertEquals(new File(root, "a" + File.separator + "b.txt"), SafePath.resolveInside(root, "a/b.txt"));
        InstallException e = assertThrows(InstallException.class, () -> SafePath.resolveInside(root, "../x"));
        assertEquals(InstallFailure.HASH_MISMATCH, e.failure);
    }

    /**
     * Review finding (medium, 5, mutant M7): a name can be well-formed and still lead outside the root through a
     * linked folder inside it. The canonical-path check is the second, independent line of defence.
     */
    @Test
    void aLinkedFolderInsideTheRootCannotLeadOutside(@TempDir File tmp) throws Exception {
        File root = new File(tmp, "staging");
        File outside = new File(tmp, "outside");
        assertTrue(root.mkdirs() && outside.mkdirs());
        java.nio.file.Files.createSymbolicLink(new File(root, "resources").toPath(), outside.toPath());
        InstallException e = assertThrows(
            InstallException.class,
            () -> SafePath.resolveInside(root, "resources/evil.dll"));
        assertEquals(InstallFailure.HASH_MISMATCH, e.failure);
        assertTrue(e.detail.contains("escapes"), e.detail);
    }
}
