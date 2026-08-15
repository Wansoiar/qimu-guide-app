package com.qimu.guide.service;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SpokenGuidancePolicyTest {

    @Test
    public void deduplicatesSameTurnAndNearbyVisionFunctionCall() {
        SpokenGuidanceDeduplicator deduplicator = new SpokenGuidanceDeduplicator();
        assertTrue(deduplicator.shouldSpeak("subtitle:1", SpokenGuidancePolicy.VISION, 1_000L));
        assertFalse(deduplicator.shouldSpeak("subtitle:1", SpokenGuidancePolicy.VISION, 1_100L));
        assertFalse(deduplicator.shouldSpeak("fc:call-1", SpokenGuidancePolicy.VISION, 1_500L));
        assertTrue(deduplicator.shouldSpeak("fc:call-2", SpokenGuidancePolicy.VISION, 7_000L));
    }
}
