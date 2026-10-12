package com.akashiic.web.client.install;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Layer 2 of the runtime (ADR-011): our host app (the JS that runs inside Electron), shipped in the mod jar under
 * {@code assets/akashicweb/host-app/} by the {@code packageHostApp} step of {@code addon.gradle}, together with
 * {@code host-app.sha256} ({@code <sha256>  <path>} per line, sha256sum format). It is installed into the engine's
 * {@code resources/app/} and every file is checked against that manifest while it is copied. A mod update with a new
 * host app only rewrites this folder; the engine is never downloaded again for it.
 */
public final class HostApp {

    public static final String RESOURCE_ROOT = "assets/akashicweb/host-app/";
    public static final String MANIFEST = "host-app.sha256";
    static final int MAX_FILES = 256;
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    /** Where the packaged files come from (the jar in game, a map in tests). */
    @FunctionalInterface
    public interface Source {

        /** The file at {@code relativePath} under the host-app root, or null if absent. */
        InputStream open(String relativePath) throws IOException;
    }

    /** One manifest line: a path under the host-app root and its pinned SHA-256. */
    static final class Pin {

        final String path;
        final String sha256;

        Pin(String path, String sha256) {
            this.path = path;
            this.sha256 = sha256;
        }
    }

    private HostApp() {}

    /** Reads from the class loader that loaded the mod (Forge's LaunchClassLoader sees the mod jar). */
    public static Source classpath(ClassLoader loader) {
        return rel -> loader.getResourceAsStream(RESOURCE_ROOT + rel);
    }

    /** The raw manifest bytes from the source. */
    static byte[] manifestBytes(Source src) throws InstallException {
        try (InputStream in = src.open(MANIFEST)) {
            if (in == null) {
                throw new InstallException(InstallFailure.HASH_MISMATCH, "host app missing from the mod jar");
            }
            return readCapped(in, 256 * 1024);
        } catch (InstallException e) {
            throw e;
        } catch (IOException e) {
            throw new InstallException(InstallFailure.HASH_MISMATCH, "host app manifest unreadable: " + e, e);
        }
    }

    /** Parses {@code <64 hex>  <path>} lines (sha256sum format; a {@code *} binary marker is accepted). */
    static List<Pin> parse(byte[] manifest) throws InstallException {
        String text = new String(manifest, StandardCharsets.UTF_8);
        List<Pin> files = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : text.split("\n")) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isEmpty() || line.startsWith("#")) continue;
            // sha256sum format: hash, a space, ' ' (text) or '*' (binary), then the path.
            if (line.length() < 67 || !line.substring(0, 64).matches("[0-9a-f]{64}") || line.charAt(64) != ' '
                || (line.charAt(65) != ' ' && line.charAt(65) != '*')) {
                throw new InstallException(InstallFailure.HASH_MISMATCH, "host app manifest line malformed");
            }
            String path = line.substring(66);
            if (!SafePath.isSafeRelative(path) || path.equals(MANIFEST) || !seen.add(path)) {
                throw new InstallException(
                    InstallFailure.HASH_MISMATCH,
                    "host app manifest path rejected: " + SafePath.printable(path));
            }
            files.add(new Pin(path, line.substring(0, 64)));
        }
        if (files.isEmpty() || files.size() > MAX_FILES) {
            throw new InstallException(InstallFailure.HASH_MISMATCH, "host app manifest lists " + files.size());
        }
        boolean hasPackageJson = false;
        for (Pin f : files) {
            hasPackageJson |= f.path.equals("package.json");
        }
        if (!hasPackageJson) {
            throw new InstallException(InstallFailure.HASH_MISMATCH, "host app has no package.json");
        }
        return files;
    }

    /**
     * Copies every listed file into {@code appDir} (which must not exist or be empty) and the manifest itself, each
     * checked against its pin. Returns the entries for the engine's local manifest, with paths relative to
     * {@code appDir}'s engine folder ({@code resources/app/...}).
     */
    static List<LocalManifest.Entry> install(Source src, File appDir) throws InstallException {
        byte[] manifest = manifestBytes(src);
        List<Pin> files = parse(manifest);
        List<LocalManifest.Entry> out = new ArrayList<>();
        String prefix = EngineManifest.APP_DIR + "/";
        for (Pin f : files) {
            File target = SafePath.resolveInside(appDir, f.path);
            long size = copyVerified(src, f, target);
            out.add(new LocalManifest.Entry(prefix + f.path, size, f.sha256));
        }
        File manifestFile = new File(appDir, MANIFEST);
        try {
            FileOps.writeAtomic(manifestFile, manifest);
        } catch (IOException e) {
            throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "cannot write " + MANIFEST + ": " + e, e);
        }
        out.add(new LocalManifest.Entry(prefix + MANIFEST, manifest.length, Sha256.of(manifest)));
        return Collections.unmodifiableList(out);
    }

    /** True when the installed app was made from exactly this jar's manifest. */
    static boolean isCurrent(Source src, File appDir) throws InstallException {
        byte[] jar = manifestBytes(src);
        File installed = new File(appDir, MANIFEST);
        if (!installed.isFile() || installed.length() != jar.length) return false;
        try {
            return Sha256.same(Sha256.of(installed), Sha256.of(jar));
        } catch (IOException e) {
            return false;
        }
    }

    private static long copyVerified(Source src, Pin f, File target) throws InstallException {
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "cannot create folder for " + f.path);
        }
        MessageDigest md = Sha256.newDigest();
        long size = 0;
        try (InputStream in = src.open(f.path)) {
            if (in == null) {
                throw new InstallException(InstallFailure.HASH_MISMATCH, "host app file missing from jar: " + f.path);
            }
            try (OutputStream o = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    size += n;
                    if (size > MAX_FILE_BYTES) {
                        throw new InstallException(InstallFailure.HASH_MISMATCH, "host app file too large: " + f.path);
                    }
                    md.update(buf, 0, n);
                    o.write(buf, 0, n);
                }
            }
        } catch (InstallException e) {
            throw e;
        } catch (IOException e) {
            throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "cannot copy host app " + f.path + ": " + e, e);
        }
        String actual = Sha256.hex(md.digest());
        if (!actual.equals(f.sha256)) {
            throw new InstallException(InstallFailure.HASH_MISMATCH, "host app file " + f.path + " does not match");
        }
        return size;
    }

    private static byte[] readCapped(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > cap) throw new IOException("larger than " + cap + " bytes");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
