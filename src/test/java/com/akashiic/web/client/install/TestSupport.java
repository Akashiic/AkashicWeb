package com.akashiic.web.client.install;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builders shared by the installer tests: fake engine zips, host app sources, platform facts and targets. */
final class TestSupport {

    private TestSupport() {}

    static byte[] random(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    /** A zip with the given entries in order; names are written verbatim (hostile names included). */
    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** Entries shaped like the official win32-x64 zip (no folder entries, flat root), with small random payloads. */
    static Map<String, byte[]> windowsLikeEntries(long seed) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("electron.exe", random(150_000, seed));
        m.put("ffmpeg.dll", random(20_000, seed + 1));
        m.put("resources.pak", random(30_000, seed + 2));
        m.put("LICENSE", "MIT".getBytes(StandardCharsets.US_ASCII));
        m.put("LICENSES.chromium.html", random(5_000, seed + 3));
        m.put("version", "44.7.0".getBytes(StandardCharsets.US_ASCII));
        m.put("locales/en-US.pak", random(2_000, seed + 4));
        m.put("locales/pt-BR.pak", random(2_000, seed + 5));
        m.put("locales/de.pak", random(2_000, seed + 6));
        m.put("locales/fr.pak", random(2_000, seed + 7));
        m.put("locales/pt-PT.pak", random(2_000, seed + 8));
        m.put("resources/default_app.asar", random(3_000, seed + 9));
        return m;
    }

    /** Entries shaped like the official linux-x64 zip. */
    static Map<String, byte[]> linuxLikeEntries(long seed) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("electron", random(120_000, seed));
        m.put("chrome-sandbox", random(5_000, seed + 1));
        m.put("chrome_crashpad_handler", random(9_000, seed + 2));
        m.put("libffmpeg.so", random(10_000, seed + 3));
        m.put("locales/en-US.pak", random(2_000, seed + 4));
        m.put("locales/pt-BR.pak", random(2_000, seed + 5));
        m.put("locales/ja.pak", random(2_000, seed + 6));
        m.put("resources/default_app.asar", random(3_000, seed + 7));
        return m;
    }

    static EngineTarget target(String platform, String url, byte[] zip, boolean windows) {
        String version = "44.7.0";
        return new EngineTarget(
            platform,
            version,
            "electron-v" + version + "-" + platform + ".zip",
            url,
            zip.length,
            Sha256.of(zip),
            windows ? "electron.exe" : "electron",
            windows ? "AkashicWebHost.exe" : "AkashicWebHost",
            windows,
            windows ? Collections.<String>emptyList()
                : Arrays.asList("AkashicWebHost", "chrome_crashpad_handler", "chrome-sandbox"));
    }

    /** An in-memory host app with a correct sha256sum-style manifest. */
    static HostApp.Source hostApp(Map<String, String> files) {
        final Map<String, byte[]> all = new HashMap<>();
        StringBuilder manifest = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(files).entrySet()) {
            byte[] b = e.getValue()
                .getBytes(StandardCharsets.UTF_8);
            all.put(e.getKey(), b);
            manifest.append(Sha256.of(b))
                .append("  ")
                .append(e.getKey())
                .append('\n');
        }
        all.put(
            HostApp.MANIFEST,
            manifest.toString()
                .getBytes(StandardCharsets.UTF_8));
        return rel -> all.containsKey(rel) ? new ByteArrayInputStream(all.get(rel)) : null;
    }

    static HostApp.Source defaultHostApp() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("package.json", "{\"name\":\"akashicweb-host\",\"main\":\"src/main.js\"}\n");
        files.put("src/main.js", "'use strict';\n// test host app v1\n");
        files.put("src/protocol.js", "'use strict';\nmodule.exports = {};\n");
        return hostApp(files);
    }

    static PlatformFacts facts(String os, String arch, String dataModel, Map<String, String> env) {
        return new PlatformFacts(
            os,
            arch,
            dataModel,
            "Oracle Corporation",
            "Java HotSpot(TM) 64-Bit Server VM",
            "Java(TM) SE Runtime Environment",
            env,
            Collections.<String>emptySet(),
            false);
    }

    static PlatformFacts linuxDesktop() {
        Map<String, String> env = new HashMap<>();
        env.put("DISPLAY", ":0");
        return facts("Linux", "amd64", "64", env);
    }

    static PlatformFacts windows10() {
        Map<String, String> env = new HashMap<>();
        env.put("LOCALAPPDATA", "C:\\Users\\gabriel\\AppData\\Local");
        env.put("SystemRoot", "C:\\Windows");
        return facts("Windows 10", "amd64", "64", env);
    }

    static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Relative paths of every regular file under {@code root}, with '/'. */
    static List<String> listFiles(File root) throws IOException {
        List<String> out = new ArrayList<>();
        Files.walk(root.toPath())
            .filter(Files::isRegularFile)
            .forEach(
                p -> out.add(
                    root.toPath()
                        .relativize(p)
                        .toString()
                        .replace(File.separatorChar, '/')));
        Collections.sort(out);
        return out;
    }
}
