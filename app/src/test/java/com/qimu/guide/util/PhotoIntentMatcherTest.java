package com.qimu.guide.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PhotoIntentMatcherTest {

    @Test
    public void shouldTriggerForDirectPhotoPhrases() {
        assertTrue(PhotoIntentMatcher.shouldTriggerPhoto("帮我看看眼前是什么"));
        assertTrue(PhotoIntentMatcher.shouldTriggerPhoto("我眼前有什么？"));
        assertTrue(PhotoIntentMatcher.shouldTriggerPhoto("这是什么？"));
        assertTrue(PhotoIntentMatcher.shouldTriggerPhoto("识别一下这个"));
    }

    @Test
    public void shouldIgnoreOrdinaryKnowledgeQuestions() {
        assertFalse(PhotoIntentMatcher.shouldTriggerPhoto("介绍一下青铜方鼎"));
        assertFalse(PhotoIntentMatcher.shouldTriggerPhoto("这个展馆几点开门"));
        assertFalse(PhotoIntentMatcher.shouldTriggerPhoto("它是什么年代的"));
    }

    @Test
    public void shouldBeRobustToPunctuationAndWhitespace() {
        assertTrue(PhotoIntentMatcher.shouldTriggerPhoto("帮我 看看，眼前是什么？"));
        assertTrue(PhotoIntentMatcher.shouldTriggerPhoto("  看看这个  "));
    }
}
