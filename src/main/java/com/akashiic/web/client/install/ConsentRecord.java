package com.akashiic.web.client.install;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The player's consent to download one pinned engine, written when they press Install (or Repair) and kept next to
 * the download ({@code download/<asset>.consent}) until that install finishes, fails, or is cancelled.
 * <p>
 * Why a record of its own (review finding 4): wave 1 took the mere existence of a resume file ({@code .state.json}) as
 * consent, so a planted or stale state file, one naming another URL, or the leftover of a failed download made every
 * later game start fetch 158 MB in the background. Now an install resumes at start-up only when this record names the
 * exact pin (SHA-256 and canonical URL) being installed, which means the game was closed mid-install; a failed install
 * deletes the record, so the next start asks again (the partial download stays and resumes after a new "yes").
 */
final class ConsentRecord {

    private static final Logger LOG = LogManager.getLogger("AkashicWeb/Installer");

    static final String SUFFIX = ".consent";

    private ConsentRecord() {}

    static File file(File downloadDir, EngineTarget t) {
        return new File(downloadDir, t.assetName + SUFFIX);
    }

    static void write(File downloadDir, EngineTarget t, long nowMs) throws InstallException {
        Properties p = new Properties();
        p.setProperty("sha256", t.sha256);
        p.setProperty("url", t.url);
        p.setProperty("granted-ms", Long.toString(nowMs));
        StringWriter w = new StringWriter();
        try {
            p.store(w, "AkashicWeb: the player agreed to download this engine");
            FileOps.ensureDir(downloadDir);
            FileOps.writeAtomic(
                file(downloadDir, t),
                w.toString()
                    .getBytes(StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            if (e instanceof InstallException) throw (InstallException) e;
            throw new InstallException(
                InstallFailure.INSTALL_DIR_NOT_WRITABLE,
                "cannot write " + file(downloadDir, t).getName() + ": " + e,
                e);
        }
    }

    /** True only for a readable record naming exactly this pin. */
    static boolean matches(File downloadDir, EngineTarget t) {
        File f = file(downloadDir, t);
        if (!f.isFile() || f.length() > 4096) return false;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
        return t.sha256.equals(p.getProperty("sha256")) && t.url.equals(p.getProperty("url"));
    }

    static void delete(File downloadDir, EngineTarget t) {
        File f = file(downloadDir, t);
        if (f.exists() && !f.delete()) LOG.warn("Could not delete {}", f.getName());
    }
}
