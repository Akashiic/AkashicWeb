package com.akashiic.web.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UploadStatsTest {

    @Test
    void percentilesUseNearestRank() {
        UploadStats s = new UploadStats();
        assertEquals(0, s.percentileNs(95));
        for (int i = 1; i <= 100; i++) s.recordUpload(i * 1_000L, 10, 0);
        assertEquals(50_000, s.percentileNs(50));
        assertEquals(95_000, s.percentileNs(95));
        assertEquals(99_000, s.percentileNs(99));
        assertEquals(100_000, s.percentileNs(100));
        assertEquals(1_000, s.percentileNs(0));
        assertEquals(100, s.uploaded());
    }

    @Test
    void windowKeepsTheLatestSamples() {
        UploadStats s = new UploadStats();
        for (int i = 0; i < UploadStats.WINDOW; i++) s.recordUpload(1_000_000, 1, 0);
        for (int i = 0; i < UploadStats.WINDOW; i++) s.recordUpload(10, 1, 0);
        assertEquals(10, s.percentileNs(99), "old slow uploads aged out");
    }

    @Test
    void bytesPerSecondCoversTheLastSecondOnly() {
        UploadStats s = new UploadStats();
        s.recordUpload(1, 1_000, 10_000);
        s.recordUpload(1, 2_000, 10_450);
        s.recordUpload(1, 4_000, 10_990);
        assertEquals(7_000, s.bytesPerSecond(10_999));
        assertEquals(6_000, s.bytesPerSecond(11_050), "the first bucket has left the window");
        assertEquals(0, s.bytesPerSecond(20_000));
        s.recordUpload(1, 500, 20_000);
        assertEquals(500, s.bytesPerSecond(20_000), "a reused bucket is reset, not added to");
    }

    @Test
    void snapshotNamesEveryCounter() {
        UploadStats s = new UploadStats();
        s.recordTaken();
        s.recordSkippedBudget();
        s.recordDroppedSize();
        s.recordDroppedInvalid();
        s.recordFailure();
        s.recordUpload(1_500_000, 3_686_400, 0);
        String line = s.snapshot(0);
        assertTrue(line.contains("p95=1.50ms"), line);
        assertTrue(line.contains("skipped(budget)=1"), line);
        assertTrue(line.contains("dropped(size)=1"), line);
        assertTrue(line.contains("failures=1"), line);
        assertEquals(2, s.dropped());
        assertEquals(1, s.skippedBudget());
    }
}
