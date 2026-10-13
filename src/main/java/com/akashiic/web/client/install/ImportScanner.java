package com.akashiic.web.client.install;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manual import (ADR-011): when TLS, a firewall or DNS stop the download, the player saves the official zip (from a
 * browser, a friend, a USB stick) into {@code <base>/import/}. Any {@code .zip} there with exactly the pinned size and
 * SHA-256 is used as if it had been downloaded; the file name does not matter, and the file is never modified or
 * deleted (it belongs to the player).
 */
final class ImportScanner {

    private static final Logger LOG = LogManager.getLogger("AkashicWeb/Installer");

    private ImportScanner() {}

    /** The first matching zip, or null. Hashes only files of the exact pinned size, the asset's own name first. */
    static File find(List<File> dirs, EngineTarget t) {
        for (File dir : dirs) {
            File[] files = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".zip"));
            if (files == null) continue;
            List<File> candidates = new ArrayList<>(Arrays.asList(files));
            candidates.sort((a, b) -> {
                boolean an = a.getName().equals(t.assetName);
                boolean bn = b.getName().equals(t.assetName);
                return an == bn ? a.getName().compareTo(b.getName()) : an ? -1 : 1;
            });
            for (File f : candidates) {
                if (f.length() != t.size) {
                    if (f.getName().equals(t.assetName)) {
                        LOG.warn("Import {}: size {} is not the pinned {} (incomplete copy?)", f, f.length(), t.size);
                    }
                    continue;
                }
                try {
                    if (Sha256.same(Sha256.of(f), t.sha256)) {
                        LOG.info("Using imported engine zip {}", f);
                        return f;
                    }
                    LOG.warn("Import {}: SHA-256 is not the pinned one; ignored", f);
                } catch (IOException e) {
                    LOG.warn("Import {}: unreadable ({})", f, e.toString());
                }
            }
        }
        return null;
    }
}
