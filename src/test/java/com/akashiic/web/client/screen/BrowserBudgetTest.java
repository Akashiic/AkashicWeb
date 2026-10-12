package com.akashiic.web.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class BrowserBudgetTest {

    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void twoBrowsersOnEightGigabytesFourOtherwise() {
        assertEquals(2, BrowserBudget.maxBrowsers(8 * GIB - 300L * 1024 * 1024)); // a typical "8 GB" machine
        assertEquals(2, BrowserBudget.maxBrowsers(8 * GIB));
        assertEquals(2, BrowserBudget.maxBrowsers(4 * GIB));
        assertEquals(4, BrowserBudget.maxBrowsers(12 * GIB - 400L * 1024 * 1024));
        assertEquals(4, BrowserBudget.maxBrowsers(32 * GIB));
        assertEquals(4, BrowserBudget.maxBrowsers(-1), "unknown memory gets the default");
        assertEquals(4, BrowserBudget.maxBrowsers(0));
    }

    @Test
    void scoreIsSizeTimesProximity() {
        assertEquals(144.0 / 41, BrowserBudget.score(16, 9, 40), 1e-9);
        assertEquals(1.0, BrowserBudget.score(2, 2, 3), 1e-9);
        assertEquals(1.0, BrowserBudget.score(0, 0, 0), 1e-9, "degenerate sizes count as 1x1");
        assertTrue(BrowserBudget.score(16, 9, 40) > BrowserBudget.score(2, 2, 3), "the far telão beats a near monitor");
    }

    private static BrowserBudget.Candidate<String> c(String key, double score, double distance, boolean hasBrowser) {
        return new BrowserBudget.Candidate<>(key, score, distance, 60, 76, hasBrowser);
    }

    @Test
    void grantsTheBestScoresUpToTheBudget() {
        List<BrowserBudget.Candidate<String>> list = Arrays.asList(
            c("small", 1.0, 3, false),
            c("telao", 3.5, 40, false),
            c("mid", 2.0, 10, false),
            c("far", 9.0, 61, false)); // out of create range
        assertEquals(Arrays.asList("telao", "mid"), BrowserBudget.select(list, 2));
        assertEquals(Arrays.asList("telao", "mid", "small"), BrowserBudget.select(list, 4));
        assertEquals(Collections.emptyList(), BrowserBudget.select(list, 0));
    }

    @Test
    void anExistingBrowserIsKeptUnlessARivalIsClearlyBetter() {
        List<BrowserBudget.Candidate<String>> close = Arrays.asList(c("old", 1.0, 5, true), c("new", 1.2, 5, false));
        assertEquals(Collections.singletonList("old"), BrowserBudget.select(close, 1));
        List<BrowserBudget.Candidate<String>> clear = Arrays.asList(c("old", 1.0, 5, true), c("new", 1.3, 5, false));
        assertEquals(Collections.singletonList("new"), BrowserBudget.select(clear, 1));
    }

    @Test
    void anExistingBrowserSurvivesUntilTheKeepRange() {
        assertEquals(
            Collections.singletonList("leaving"),
            BrowserBudget.select(Collections.singletonList(c("leaving", 1, 70, true)), 2));
        assertEquals(
            Collections.emptyList(),
            BrowserBudget.select(Collections.singletonList(c("arriving", 1, 70, false)), 2));
        assertEquals(
            Collections.emptyList(),
            BrowserBudget.select(Collections.singletonList(c("gone", 1, 77, true)), 2));
    }

    @Test
    void tiesGoToTheCloserScreen() {
        List<BrowserBudget.Candidate<String>> list = Arrays.asList(c("b", 1.0, 9, false), c("a", 1.0, 4, false));
        assertEquals(Collections.singletonList("a"), BrowserBudget.select(list, 1));
    }
}
