package com.akashiic.web.client.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Download, resume and verification against a loopback server shaped like GitHub's release redirects. These are the
 * NativesProbe pass criteria of ADR-011 that can be exercised without the network: resume after abort re-fetches less
 * than one chunk, an ETag change restarts, redirects are re-followed, a wrong hash never reaches extraction.
 */
class DownloaderTest {

    private static final int SIZE = 1_000_000 + 12_345; // not a multiple of the chunk size

    @TempDir
    File dir;

    private TestServer server;
    private byte[] body;
    private final List<Long> sleeps = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = new TestServer();
        body = TestSupport.random(SIZE, 42);
        server.body = body;
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private Downloader downloader(int maxAttempts) {
        HttpFetcher http = new HttpFetcher(HttpFetcher.userAgent("1.2.3"), new InstallerTls(), true, 5000, 5000);
        return new Downloader(http, new Backoff(1000, 60_000), sleeps::add, f -> Long.MAX_VALUE, maxAttempts);
    }

    private EngineTarget target(String url) {
        return TestSupport.target("linux-x64", url, body, false);
    }

    private File part(EngineTarget t) {
        return new File(dir, t.assetName + ".part");
    }

    private File stateFile(EngineTarget t) {
        return new File(dir, t.assetName + ".state.json");
    }

    @Test
    void downloadsVerifiesAndCleansUp() throws Exception {
        EngineTarget t = target(server.canonicalUrl("e.zip"));
        List<Long> progress = new ArrayList<>();
        File zip = downloader(3).download(t, dir, (done, total) -> progress.add(done));
        assertArrayEquals(body, Files.readAllBytes(zip.toPath()));
        assertEquals(t.assetName, zip.getName());
        assertFalse(part(t).exists());
        assertFalse(stateFile(t).exists());
        assertEquals(SIZE, server.bytesServed.get());
        assertEquals(Long.valueOf(SIZE), progress.get(progress.size() - 1));
        assertEquals("AkashicWeb/1.2.3 (+https://github.com/Akashiic/AkashicWeb)", server.requests.get(0).userAgent);
    }

    @Test
    void resumeAfterAbortRefetchesLessThanOneChunk() throws Exception {
        EngineTarget t = target(server.canonicalUrl("e.zip"));
        long cutAt = 400_000 + 777;
        server.cuts.add(cutAt);
        Downloader d = downloader(3);
        File zip = d.download(t, dir, ProgressListener.NONE);
        assertArrayEquals(body, Files.readAllBytes(zip.toPath()));
        List<TestServer.Request> entity = server.entityRequests();
        assertEquals(2, entity.size());
        assertNull(entity.get(0).range);
        assertEquals("bytes=" + cutAt + "-", entity.get(1).range);
        assertEquals("\"v1\"", entity.get(1).ifRange);
        long refetched = server.bytesServed.get() - SIZE;
        assertTrue(refetched < Downloader.CHUNK, "re-fetched " + refetched);
        assertEquals(1, sleeps.size(), "one backoff pause between the two attempts");
    }

    @Test
    void resumeAcrossARestartUsesTheStateFile() throws Exception {
        EngineTarget t = target(server.canonicalUrl("e.zip"));
        Downloader first = downloader(3);
        InstallException e = assertThrows(InstallException.class, () -> first.download(t, dir, (done, total) -> {
            if (done > 300_000) first.cancel(); // the game quits mid-download
        }));
        assertEquals(InstallFailure.DOWNLOAD_FAILED, e.failure);
        long kept = part(t).length();
        assertTrue(kept > 300_000 && kept < SIZE, "kept " + kept);
        String state = new String(Files.readAllBytes(stateFile(t).toPath()), StandardCharsets.UTF_8);
        assertTrue(state.contains("/canonical/e.zip"), state);
        assertFalse(state.contains("sig="), "the signed redirect target must never be stored: " + state);
        assertFalse(state.contains("/signed/"), state);

        int canonicalBefore = server.canonicalHits.get();
        File zip = downloader(3).download(t, dir, ProgressListener.NONE);
        assertArrayEquals(body, Files.readAllBytes(zip.toPath()));
        assertEquals(canonicalBefore + 1, server.canonicalHits.get(), "the restart re-resolved the redirect");
        List<TestServer.Request> entity = server.entityRequests();
        assertEquals("bytes=" + kept + "-", entity.get(entity.size() - 1).range);
    }

    @Test
    void everyAttemptFollowsTheRedirectAfresh() throws Exception {
        // Signed URLs are single-use here: reusing the first redirect target would get 403.
        EngineTarget t = target(server.canonicalUrl("e.zip"));
        server.cuts.add(100_000L);
        server.cuts.add(200_000L);
        downloader(5).download(t, dir, ProgressListener.NONE);
        assertEquals(3, server.canonicalHits.get());
        for (TestServer.Request r : server.entityRequests()) {
            assertTrue(r.path.startsWith("/signed/e.zip?sig="), r.path);
        }
    }

    @Test
    void etagChangeRestartsFromZero() throws Exception {
        EngineTarget t = target(server.canonicalUrl("e.zip"));
        Downloader first = downloader(3);
        assertThrows(
            InstallException.class,
            () -> first.download(t, dir, (done, total) -> { if (done > 500_000) first.cancel(); }));
        long kept = part(t).length();
        server.etag = "\"v2\""; // the asset host re-uploaded the file
        File zip = downloader(3).download(t, dir, ProgressListener.NONE);
        assertArrayEquals(body, Files.readAllBytes(zip.toPath()));
        TestServer.Request last = server.entityRequests()
            .get(
                server.entityRequests()
                    .size() - 1);
        assertEquals("bytes=" + kept + "-", last.range);
        assertEquals("\"v1\"", last.ifRange, "the stale validator was sent; the server answered 200 with everything");
        assertTrue(server.bytesServed.get() >= kept + SIZE, "the whole file came again");
    }

    @Test
    void hashMismatchDeletesThePartFile() throws Exception {
        EngineTarget t = target(server.canonicalUrl("e.zip"));
        server.body = TestSupport.random(SIZE, 999); // same size, different bytes
        InstallException e = assertThrows(
            InstallException.class,
            () -> downloader(3).download(t, dir, ProgressListener.NONE));
        assertEquals(InstallFailure.HASH_MISMATCH, e.failure);
        assertFalse(part(t).exists());
        assertFalse(stateFile(t).exists());
        assertFalse(new File(dir, t.assetName).exists());
    }

    @Test
    void corruptResumeGetsOneCleanRetry() throws Exception {
        EngineTarget t = target(server.canonicalUrl("e.zip"));
        Downloader first = downloader(3);
        assertThrows(
            InstallException.class,
            () -> first.download(t, dir, (done, total) -> { if (done > 200_000) first.cancel(); }));
        // A crash left garbage in the kept prefix (e.g. zero-filled tail after power loss).
        byte[] kept = Files.readAllBytes(part(t).toPath());
        Arrays.fill(kept, 100, 200, (byte) 0x55);
        Files.write(part(t).toPath(), kept);
        File zip = downloader(3).download(t, dir, ProgressListener.NONE);
        assertArrayEquals(body, Files.readAllBytes(zip.toPath()));
    }

    @Test
    void wrongDeclaredSizeFailsBeforeTheBody() throws Exception {
        EngineTarget t = target(server.canonicalUrl("e.zip"));
        server.contentLengthOverride = SIZE + 1;
        InstallException e = assertThrows(
            InstallException.class,
            () -> downloader(3).download(t, dir, ProgressListener.NONE));
        assertEquals(InstallFailure.HASH_MISMATCH, e.failure);
        assertEquals(0, server.bytesServed.get());
    }

    @Test
    void missingAssetFailsWithoutRetrying() throws Exception {
        EngineTarget t = target(server.canonicalUrl("e.zip"));
        server.persistentStatus = 404;
        InstallException e = assertThrows(
            InstallException.class,
            () -> downloader(5).download(t, dir, ProgressListener.NONE));
        assertEquals(InstallFailure.DOWNLOAD_FAILED, e.failure);
        assertTrue(
            e.getMessage()
                .contains("404"),
            e.getMessage());
        assertEquals(1, server.entityHits.get());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void serverErrorsBackOffExponentiallyThenFail() throws Exception {
        EngineTarget t = target(server.canonicalUrl("e.zip"));
        server.persistentStatus = 503;
        InstallException e = assertThrows(
            InstallException.class,
            () -> downloader(4).download(t, dir, ProgressListener.NONE));
        assertEquals(InstallFailure.DOWNLOAD_FAILED, e.failure);
        assertEquals(4, server.entityHits.get());
        assertEquals(Arrays.asList(1000L, 2000L, 4000L), sleeps);
    }

    @Test
    void transientErrorsRecover() throws Exception {
        EngineTarget t = target(server.canonicalUrl("e.zip"));
        server.statuses.add(500);
        server.statuses.add(502);
        File zip = downloader(4).download(t, dir, ProgressListener.NONE);
        assertArrayEquals(body, Files.readAllBytes(zip.toPath()));
        assertEquals(Arrays.asList(1000L, 2000L), sleeps);
    }

    @Test
    void progressResetsTheAttemptCounter() throws Exception {
        // Five drops in a row with only two attempts allowed: it still completes, because each attempt progressed.
        EngineTarget t = target(server.canonicalUrl("e.zip"));
        for (long c : new long[] { 150_000, 150_000, 150_000, 150_000, 150_000 }) server.cuts.add(c);
        File zip = downloader(2).download(t, dir, ProgressListener.NONE);
        assertArrayEquals(body, Files.readAllBytes(zip.toPath()));
        assertEquals(6, server.entityHits.get());
        for (long s : sleeps) assertEquals(1000L, s);
    }

    @Test
    void noValidatorMeansNoResume() throws Exception {
        EngineTarget t = target(server.directUrl("e.zip"));
        new DownloadState(t.url, null, t.size).write(stateFile(t));
        Files.write(part(t).toPath(), Arrays.copyOf(body, 1234));
        File zip = downloader(3).download(t, dir, ProgressListener.NONE);
        assertArrayEquals(body, Files.readAllBytes(zip.toPath()));
        assertNull(
            server.entityRequests()
                .get(0).range,
            "no ETag on record: never splice, download from 0");
    }

    @Test
    void stateForAnotherAssetIsDiscarded() throws Exception {
        EngineTarget t = target(server.directUrl("e.zip"));
        new DownloadState("https://example.invalid/other.zip", "\"v1\"", t.size).write(stateFile(t));
        Files.write(part(t).toPath(), new byte[5000]);
        File zip = downloader(3).download(t, dir, ProgressListener.NONE);
        assertArrayEquals(body, Files.readAllBytes(zip.toPath()));
        assertNull(
            server.entityRequests()
                .get(0).range);
    }

    @Test
    void alreadyDownloadedZipIsReusedAfterVerification() throws Exception {
        EngineTarget t = target(server.directUrl("e.zip"));
        Files.write(new File(dir, t.assetName).toPath(), body);
        downloader(3).download(t, dir, ProgressListener.NONE);
        assertEquals(0, server.requestCount());
    }

    @Test
    void plainHttpIsRefusedInProduction() {
        EngineTarget t = target(server.directUrl("e.zip"));
        HttpFetcher prod = HttpFetcher.production("1.0", new InstallerTls());
        Downloader d = new Downloader(prod, Backoff.DEFAULT, sleeps::add, f -> Long.MAX_VALUE, 3);
        InstallException e = assertThrows(InstallException.class, () -> d.download(t, dir, ProgressListener.NONE));
        assertEquals(InstallFailure.DOWNLOAD_FAILED, e.failure);
        assertEquals(0, server.requestCount());
    }

    @Test
    void contentRangeParsing() {
        assertArrayEquals(new long[] { 5, 9, 10 }, Downloader.parseContentRange("bytes 5-9/10"));
        assertNull(Downloader.parseContentRange("bytes */10"));
        assertNull(Downloader.parseContentRange(null));
        assertNull(Downloader.parseContentRange("items 1-2/3"));
    }

    @Test
    void backoffDoublesAndCaps() {
        Backoff b = new Backoff(1000, 60_000);
        assertEquals(0, b.delayMs(0));
        assertEquals(1000, b.delayMs(1));
        assertEquals(2000, b.delayMs(2));
        assertEquals(32_000, b.delayMs(6));
        assertEquals(60_000, b.delayMs(7));
        assertEquals(60_000, b.delayMs(500));
    }

    @Test
    void stateFileRoundTripAndCorruption() throws Exception {
        File f = new File(dir, "s.json");
        new DownloadState("https://github.com/x.zip", "\"abc\"", 42).write(f);
        DownloadState s = DownloadState.read(f);
        assertNotNull(s);
        assertEquals("\"abc\"", s.etag);
        assertEquals(42, s.size);
        Files.write(f.toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        assertNull(DownloadState.read(f));
        Files.write(f.toPath(), "[1,2]".getBytes(StandardCharsets.UTF_8));
        assertNull(DownloadState.read(f));
    }
}
