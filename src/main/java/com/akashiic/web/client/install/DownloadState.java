package com.akashiic.web.client.install;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * The resume record next to a {@code .part} file, kept across game restarts. It holds only the canonical URL, the
 * validator (ETag) and the expected size (ADR-011 amendment): the redirected, signed asset URL is never stored,
 * because it expires within minutes. Uses the Gson 2.2.4 API that Minecraft 1.7.10 ships.
 */
final class DownloadState {

    final String url;
    /** Strong ETag from the asset host, or null when the server sent none (then the download cannot resume). */
    final String etag;
    final long size;

    DownloadState(String url, String etag, long size) {
        this.url = url;
        this.etag = etag;
        this.size = size;
    }

    static DownloadState fresh(EngineTarget t) {
        return new DownloadState(t.url, null, t.size);
    }

    boolean matches(EngineTarget t) {
        return t.url.equals(url) && t.size == size;
    }

    /** The saved state, or null if absent or unreadable (an unreadable state just means starting over). */
    static DownloadState read(File f) {
        if (!f.isFile() || f.length() > 64 * 1024) return null;
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            JsonElement root = new JsonParser().parse(r);
            if (!root.isJsonObject()) return null;
            JsonObject o = root.getAsJsonObject();
            if (!o.has("url") || !o.has("size")) return null;
            String etag = o.has("etag") && !o.get("etag")
                .isJsonNull() ? o.get("etag")
                    .getAsString() : null;
            return new DownloadState(
                o.get("url")
                    .getAsString(),
                etag,
                o.get("size")
                    .getAsLong());
        } catch (IOException | JsonParseException | IllegalStateException | ClassCastException | NumberFormatException
            | UnsupportedOperationException e) {
            return null;
        }
    }

    void write(File f) throws InstallException {
        JsonObject o = new JsonObject();
        o.addProperty("url", url);
        if (etag != null) o.addProperty("etag", etag);
        o.addProperty("size", size);
        String json = new GsonBuilder().setPrettyPrinting()
            .create()
            .toJson(o) + "\n";
        try {
            FileOps.writeAtomic(f, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new InstallException(
                InstallFailure.INSTALL_DIR_NOT_WRITABLE,
                "cannot write " + f.getName() + ": " + e,
                e);
        }
    }
}
