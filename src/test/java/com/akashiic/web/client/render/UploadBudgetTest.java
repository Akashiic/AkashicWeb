package com.akashiic.web.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class UploadBudgetTest {

    private static final long FRAME_720 = 1280L * 720 * 4;
    private static final long FRAME_1080 = 1920L * 1080 * 4;

    @Test
    void budgetIsOneFullHdFrame() {
        assertEquals(8_294_400L, UploadBudget.DEFAULT_BYTES_PER_FRAME);
        UploadBudget b = new UploadBudget(UploadBudget.DEFAULT_BYTES_PER_FRAME);
        b.beginFrame();
        assertTrue(b.tryConsume(FRAME_720));
        assertTrue(b.tryConsume(FRAME_720), "two 720p frames fit");
        assertFalse(b.tryConsume(FRAME_720), "a third does not");
        assertTrue(b.tryConsume(1_000), "small damage still fits");
        b.beginFrame();
        assertEquals(0, b.used());
        assertTrue(b.tryConsume(FRAME_1080));
        assertFalse(b.tryConsume(1));
    }

    @Test
    void firstUploadOfAFrameAlwaysFits() {
        UploadBudget b = new UploadBudget(UploadBudget.DEFAULT_BYTES_PER_FRAME);
        b.beginFrame();
        assertTrue(b.tryConsume(3840L * 2160 * 4), "a 4K page is not starved");
        assertFalse(b.tryConsume(4));
    }

    @Test
    void orderIsByPriorityAgedBySkippedFrames() {
        List<UploadBudget.Entry<String>> e = Arrays.asList(
            new UploadBudget.Entry<>("far", 1.0, 0),
            new UploadBudget.Entry<>("telao", 3.5, 0),
            new UploadBudget.Entry<>("near", 2.0, 0));
        assertEquals(Arrays.asList("telao", "near", "far"), UploadBudget.order(e));
        List<UploadBudget.Entry<String>> aged = Arrays.asList(
            new UploadBudget.Entry<>("far", 1.0, 3),
            new UploadBudget.Entry<>("telao", 3.5, 0),
            new UploadBudget.Entry<>("near", 2.0, 0));
        assertEquals(Arrays.asList("far", "telao", "near"), UploadBudget.order(aged));
    }

    /** Simulates the uploader: three 1080p screens all repainting every frame, one budget per frame. */
    @Test
    void noScreenStarvesUnderSustainedOverload() {
        UploadBudget b = new UploadBudget(UploadBudget.DEFAULT_BYTES_PER_FRAME);
        Map<String, Integer> skipped = new HashMap<>();
        Map<String, Integer> uploaded = new HashMap<>();
        Map<String, Double> priority = new HashMap<>();
        priority.put("a", 5.0);
        priority.put("b", 2.0);
        priority.put("c", 0.5);
        for (String k : priority.keySet()) {
            skipped.put(k, 0);
            uploaded.put(k, 0);
        }
        for (int frame = 0; frame < 300; frame++) {
            b.beginFrame();
            List<UploadBudget.Entry<String>> entries = new ArrayList<>();
            for (String k : priority.keySet())
                entries.add(new UploadBudget.Entry<>(k, priority.get(k), skipped.get(k)));
            for (String k : UploadBudget.order(entries)) {
                if (b.tryConsume(FRAME_1080)) {
                    uploaded.put(k, uploaded.get(k) + 1);
                    skipped.put(k, 0);
                } else {
                    skipped.put(k, skipped.get(k) + 1);
                }
                assertTrue(skipped.get(k) < 20, "screen " + k + " starved");
            }
        }
        assertTrue(uploaded.get("a") > uploaded.get("c"), "priority still matters");
        assertTrue(uploaded.get("c") > 0);
    }
}
