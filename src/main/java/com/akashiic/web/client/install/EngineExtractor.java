package com.akashiic.web.client.install;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Unpacks a verified engine zip into a staging folder (ADR-011, ADR-001 amendment):
 * <ul>
 * <li>every entry name is validated and resolved inside the staging folder ({@link SafePath}: zip-slip);</li>
 * <li>locales other than en-US and pt-BR are not written;</li>
 * <li>{@code resources/default_app.asar} is not written, nor anything under {@code resources/app};</li>
 * <li>the vendor exe is written under its host name, byte for byte (renaming keeps its hash and signature state);</li>
 * <li>each written file is hashed while it is written and must be exactly the pinned file
 * ({@link EngineTarget#vendorFiles}); the zip's own hash was checked before, but by path, so a zip swapped between that
 * check and this extraction (another local user, an import folder on a shared drive) is caught here.</li>
 * </ul>
 * The central directory is read through {@link ZipFile}, so a crafted local header cannot smuggle extra entries.
 */
final class EngineExtractor {

    /** What was written. Paths are relative to the staging folder, with {@code /}. */
    static final class Result {

        final List<LocalManifest.Entry> entries = new ArrayList<>();
        int skipped;
        long bytesWritten;
    }

    private final ToLongFunction<File> usableSpace;

    EngineExtractor(ToLongFunction<File> usableSpace) {
        this.usableSpace = usableSpace;
    }

    /** The installed path for a zip entry, or null if the entry is not installed. Pure. */
    static String installedPath(String entryName, EngineTarget t) {
        return installedPath(entryName, t.vendorExecutable, t.hostExecutable);
    }

    /** As {@link #installedPath(String, EngineTarget)}, by executable names (for generating pins). Pure. */
    static String installedPath(String entryName, String vendorExecutable, String hostExecutable) {
        if (entryName.equals(vendorExecutable)) return hostExecutable;
        if (entryName.equals(hostExecutable)) return null; // never let the archive shadow the renamed exe
        if (entryName.startsWith(EngineManifest.LOCALES_PREFIX)) {
            return EngineManifest.KEPT_LOCALES.contains(entryName) ? entryName : null;
        }
        if (EngineManifest.REMOVED_ENTRIES.contains(entryName)) return null;
        // Our app is the only app: drop anything the archive might carry where Electron looks for one.
        if (entryName.equals("resources/app.asar") || entryName.startsWith(EngineManifest.APP_DIR + "/")) return null;
        return entryName;
    }

    Result extract(File zip, EngineTarget t, File staging, ProgressListener progress) throws InstallException {
        Result result = new Result();
        Set<String> seen = new HashSet<>();
        boolean sawExecutable = false;
        try (ZipFile zf = new ZipFile(zip)) {
            if (zf.size() > EngineManifest.MAX_ENTRIES) {
                throw new InstallException(InstallFailure.HASH_MISMATCH, "archive has " + zf.size() + " entries");
            }
            long total = 0;
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements();) {
                total += Math.max(
                    0,
                    en.nextElement()
                        .getSize());
            }
            long done = 0;
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory()) {
                    // Folders are created on demand; still reject a hostile name so it is reported, not ignored.
                    SafePath.resolveInside(staging, name.substring(0, name.length() - 1));
                    continue;
                }
                // Validate before deciding to skip: an unsafe name anywhere means the archive is not the pinned one.
                SafePath.resolveInside(staging, name);
                if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                    throw new InstallException(InstallFailure.HASH_MISMATCH, "duplicate entry " + name);
                }
                String target = installedPath(name, t);
                if (target == null) {
                    result.skipped++;
                    done += Math.max(0, e.getSize());
                    continue;
                }
                sawExecutable |= target.equals(t.hostExecutable);
                File out = SafePath.resolveInside(staging, target);
                LocalManifest.Entry written = writeEntry(zf, e, out, target, result);
                LocalManifest.Entry pin = t.vendorFiles.get(target);
                if (pin == null || pin.size != written.size || !pin.sha256.equals(written.sha256)) {
                    throw new InstallException(InstallFailure.HASH_MISMATCH, target + " is not the pinned file");
                }
                result.entries.add(written);
                done += Math.max(0, e.getSize());
                progress.onProgress(done, total);
            }
        } catch (ZipException e) {
            throw new InstallException(InstallFailure.HASH_MISMATCH, "not a valid zip: " + e.getMessage(), e);
        } catch (InstallException e) {
            throw e;
        } catch (IOException e) {
            throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "cannot read archive: " + e, e);
        }
        if (!sawExecutable) {
            throw new InstallException(InstallFailure.HASH_MISMATCH, "archive has no " + t.vendorExecutable);
        }
        if (result.entries.size() != t.vendorFiles.size()) {
            throw new InstallException(
                InstallFailure.HASH_MISMATCH,
                "archive has " + result.entries.size() + " of the " + t.vendorFiles.size() + " pinned files");
        }
        if (!t.windows) markExecutables(staging, t);
        return result;
    }

    private LocalManifest.Entry writeEntry(ZipFile zf, ZipEntry e, File out, String target, Result result)
        throws InstallException {
        File parent = out.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new InstallException(InstallFailure.INSTALL_DIR_NOT_WRITABLE, "cannot create folder for " + target);
        }
        MessageDigest md = Sha256.newDigest();
        long size = 0;
        try (InputStream in = zf.getInputStream(e)) {
            FileOutputStream os;
            try {
                os = new FileOutputStream(out);
            } catch (IOException ex) {
                throw writeFailure(target, out, ex);
            }
            try (FileOutputStream o = os) {
                byte[] buf = new byte[Sha256.BUFFER];
                int n;
                while ((n = in.read(buf)) != -1) {
                    size += n;
                    result.bytesWritten += n;
                    if (result.bytesWritten > EngineManifest.MAX_WRITTEN_BYTES) {
                        throw new InstallException(InstallFailure.HASH_MISMATCH, "archive unpacks beyond the cap");
                    }
                    md.update(buf, 0, n);
                    try {
                        o.write(buf, 0, n);
                    } catch (IOException ex) {
                        throw writeFailure(target, out, ex);
                    }
                }
                // On disk before the marker is written (review finding 9b): the start-up check compares sizes only,
                // and a power loss could otherwise leave zero-filled files of the right size behind a valid marker.
                o.getFD()
                    .sync();
            } catch (InstallException ex) {
                throw ex;
            } catch (IOException ex) {
                // close() failing is a write failure too (the data may not be on disk).
                throw writeFailure(target, out, ex);
            }
        } catch (InstallException ex) {
            throw ex;
        } catch (ZipException ex) {
            throw new InstallException(InstallFailure.HASH_MISMATCH, "corrupt entry " + target + ": " + ex, ex);
        } catch (IOException ex) {
            throw new InstallException(InstallFailure.DOWNLOAD_FAILED, "cannot read entry " + target + ": " + ex, ex);
        }
        if (e.getSize() >= 0 && size != e.getSize()) {
            throw new InstallException(InstallFailure.HASH_MISMATCH, target + " unpacked to an unexpected size");
        }
        return new LocalManifest.Entry(target, size, Sha256.hex(md.digest()));
    }

    /**
     * A write that fails with space left is, for an executable on Windows, most likely an antivirus holding or
     * deleting it; for other files it is reported as a plain failure.
     */
    private InstallException writeFailure(String target, File out, IOException ex) {
        File dir = out.getAbsoluteFile()
            .getParentFile();
        if (usableSpace.applyAsLong(dir) < Downloader.WRITE_MARGIN) {
            return new InstallException(InstallFailure.DISK_FULL, "no space left writing " + target, ex);
        }
        String lower = target.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".dll")) {
            return new InstallException(InstallFailure.AV_BLOCKED, "cannot write " + target + ": " + ex, ex);
        }
        return new InstallException(InstallFailure.INSTALL_DIR_NOT_WRITABLE, "cannot write " + target + ": " + ex, ex);
    }

    /** Java 8's ZipFile hides Unix modes, so the executables are known by name (EngineTarget#unixExecutables). */
    private static void markExecutables(File staging, EngineTarget t) throws InstallException {
        for (String rel : t.unixExecutables) {
            File f = new File(staging, rel);
            if (f.isFile() && !f.setExecutable(true, false)) {
                throw new InstallException(
                    InstallFailure.INSTALL_DIR_NOT_WRITABLE,
                    "cannot mark " + rel + " executable");
            }
        }
    }
}
