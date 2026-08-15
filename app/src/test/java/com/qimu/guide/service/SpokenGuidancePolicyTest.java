package com.qimu.guide.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class SpokenGuidancePolicyTest {

    @Test
    public void onlyHandlesVisionBecauseNormalGuidanceComesFromRtc() {
        assertEquals(SpokenGuidancePolicy.VISION,
                SpokenGuidancePolicy.phraseForUserText("帮我看看眼前有什么"));
        assertEquals(SpokenGuidancePolicy.VISION,
                SpokenGuidancePolicy.phraseForUserText("拍照识别一下"));
        assertNull(
                SpokenGuidancePolicy.phraseForUserText("它是什么年代出土的？"));
        assertNull(
                SpokenGuidancePolicy.phraseForUserText("这件文物是什么材质？"));
        assertNull(
                SpokenGuidancePolicy.phraseForUserText("博物馆几点闭馆？"));
        assertNull(
                SpokenGuidancePolicy.phraseForUserText("介绍一下这件展品"));
        assertNull(
                SpokenGuidancePolicy.phraseForUserText("为什么会这样？"));
    }

    @Test
    public void skipsOnlyShortGreetingsAndAcknowledgements() {
        assertNull(SpokenGuidancePolicy.phraseForUserText("你好"));
        assertNull(SpokenGuidancePolicy.phraseForUserText("谢谢"));
        assertNull(
                SpokenGuidancePolicy.phraseForUserText("请继续介绍"));
    }

    @Test
    public void deduplicatesSameTurnAndNearbyVisionFunctionCall() {
        SpokenGuidanceDeduplicator deduplicator = new SpokenGuidanceDeduplicator();
        assertTrue(deduplicator.shouldSpeak("subtitle:1", SpokenGuidancePolicy.VISION, 1_000L));
        assertFalse(deduplicator.shouldSpeak("subtitle:1", SpokenGuidancePolicy.VISION, 1_100L));
        assertFalse(deduplicator.shouldSpeak("fc:call-1", SpokenGuidancePolicy.VISION, 1_500L));
        assertTrue(deduplicator.shouldSpeak("fc:call-2", SpokenGuidancePolicy.VISION, 7_000L));
    }
}
