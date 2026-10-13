package com.akashiic.web.client.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Layer 2 (ADR-011): the host app from the jar, checked against host-app.sha256 while it is copied. */
class HostAppTest {

    @TempDir
    File tmp;

    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesSha256sumFormat() throws Exception {
        List<HostApp.Pin> pins = HostApp.parse(utf8(HASH + "  package.json\n" + HASH + " *src/main.js\r\n\n# c\n"));
        assertEquals(2, pins.size());
        assertEquals("src/main.js", pins.get(1).path);
    }

    @Test
    void rejectsMalformedAndUnsafeManifests() {
        String[] bad = { HASH + "  ../escape.js\n" + HASH + "  package.json\n", HASH + "  /abs.js\n",
            HASH + "  a/../../b\n", HASH.substring(1) + "  package.json\n", HASH + "\tpackage.json\n",
            HASH + "  src/main.js\n" /* no package.json */, HASH + "  package.json\n" + HASH + "  package.json\n",
            HASH + "  host-app.sha256\n" + HASH + "  package.json\n", "" };
        for (String m : bad) {
            InstallException e = assertThrows(InstallException.class, () -> HostApp.parse(utf8(m)), m);
            assertEquals(InstallFailure.HASH_MISMATCH, e.failure);
        }
    }

    @Test
    void installsEveryFileAndTheManifest() throws Exception {
        File app = new File(tmp, "resources/app");
        List<LocalManifest.Entry> entries = HostApp.install(TestSupport.defaultHostApp(), app);
        assertEquals(
            java.util.Arrays.asList("host-app.sha256", "package.json", "src/main.js", "src/protocol.js"),
            TestSupport.listFiles(app));
        for (LocalManifest.Entry e : entries) {
            assertTrue(e.path.startsWith("resources/app/"), e.path);
            File f = new File(tmp, e.path);
            assertEquals(Sha256.of(f), e.sha256);
            assertEquals(f.length(), e.size);
        }
        assertTrue(HostApp.isCurrent(TestSupport.defaultHostApp(), app));
    }

    @Test
    void aTamperedFileInTheJarIsRefused() throws Exception {
        HostApp.Source good = TestSupport.defaultHostApp();
        HostApp.Source tampered = rel -> rel.equals("src/main.js") ? new ByteArrayInputStream(utf8("evil()"))
            : good.open(rel);
        InstallException e = assertThrows(InstallException.class, () -> HostApp.install(tampered, new File(tmp, "a")));
        assertEquals(InstallFailure.HASH_MISMATCH, e.failure);
    }

    @Test
    void aMissingFileOrManifestIsRefused() {
        HostApp.Source good = TestSupport.defaultHostApp();
        HostApp.Source missing = rel -> rel.equals("src/protocol.js") ? null : good.open(rel);
        assertEquals(
            InstallFailure.HASH_MISMATCH,
            assertThrows(InstallException.class, () -> HostApp.install(missing, new File(tmp, "a"))).failure);
        assertEquals(
            InstallFailure.HASH_MISMATCH,
            assertThrows(InstallException.class, () -> HostApp.install(rel -> null, new File(tmp, "b"))).failure);
    }

    @Test
    void aJarThatCannotBeReadIsAModJarProblemNotADownloadOne() {
        HostApp.Source good = TestSupport.defaultHostApp();
        HostApp.Source unreadable = rel -> rel.equals("src/main.js") ? new InputStream() {

            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("zip entry corrupt");
            }
        } : good.open(rel);
        InstallException e = assertThrows(
            InstallException.class,
            () -> HostApp.install(unreadable, new File(tmp, "a")));
        assertEquals(InstallFailure.HASH_MISMATCH, e.failure);
        assertTrue(e.detail.startsWith("mod jar"), e.detail);
    }

    @Test
    void aFolderThatCannotBeWrittenIsInstallDirNotWritable() throws Exception {
        File app = new File(tmp, "app");
        // A folder where a file must go makes the write fail even for root (tests run as root here).
        assertTrue(new File(app, "src/main.js").mkdirs());
        InstallException e = assertThrows(
            InstallException.class,
            () -> HostApp.install(TestSupport.defaultHostApp(), app));
        assertEquals(InstallFailure.INSTALL_DIR_NOT_WRITABLE, e.failure);
    }

    @Test
    void isCurrentDetectsANewerJar() throws Exception {
        File app = new File(tmp, "app");
        HostApp.install(TestSupport.defaultHostApp(), app);
        Map<String, String> v2 = new LinkedHashMap<>();
        v2.put("package.json", "{\"main\":\"src/main.js\"}");
        v2.put("src/main.js", "// v2");
        assertFalse(HostApp.isCurrent(TestSupport.hostApp(v2), app));
    }

    /**
     * The real packaging step: addon.gradle's packageHostApp put host/src/** and host/package.json on the classpath
     * with host-app.sha256. Every listed file must match, and the format must be the one the installer parses.
     */
    @Test
    void thePackagedHostAppInTheJarVerifies() throws Exception {
        HostApp.Source jar = HostApp.classpath(getClass().getClassLoader());
        byte[] manifest = HostApp.manifestBytes(jar);
        List<HostApp.Pin> pins = HostApp.parse(manifest);
        Set<String> paths = new HashSet<>();
        for (HostApp.Pin p : pins) {
            paths.add(p.path);
            try (InputStream in = jar.open(p.path)) {
                assertNotNull(in, p.path);
                assertEquals(p.sha256, Sha256.of(in), p.path);
            }
            // Byte-for-byte copy of the repository file (no filtering, no line-ending changes).
            File source = new File("host", p.path);
            if (source.isFile()) assertEquals(Sha256.of(source), p.sha256, p.path);
        }
        assertTrue(paths.contains("package.json"));
        assertTrue(paths.contains("src/protocol.js"));
        for (String p : paths) assertTrue(p.equals("package.json") || p.startsWith("src/"), p);
        File app = new File(tmp, "resources/app");
        HostApp.install(jar, app);
        assertTrue(new File(app, "package.json").isFile());
        assertEquals(
            new String(manifest, StandardCharsets.UTF_8),
            new String(Files.readAllBytes(new File(app, HostApp.MANIFEST).toPath()), StandardCharsets.UTF_8));
    }
}
