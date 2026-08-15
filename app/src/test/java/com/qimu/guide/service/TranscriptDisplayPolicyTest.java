package com.qimu.guide.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class TranscriptDisplayPolicyTest {

    @Test
    public void hidesCurrentProcessGuidanceFromAiTranscript() {
        assertEquals("", TranscriptDisplayPolicy.visibleText(
                false, "我先确认一下这件展品。"));
        assertEquals("", TranscriptDisplayPolicy.visibleText(
                false, "我查一下它的历史背景……"));
        assertEquals("", TranscriptDisplayPolicy.visibleText(
                false, "我核对一下相关细节！"));
        assertEquals("", TranscriptDisplayPolicy.visibleText(
                false, "我确认一下场馆信息。"));
        assertEquals("", TranscriptDisplayPolicy.visibleText(
                false, "我看看眼前的展品。"));
    }

    @Test
    public void hidesLegacyComfortWordsFromAiTranscript() {
        assertEquals("", TranscriptDisplayPolicy.visibleText(
                false, "让我看看这件展品……"));
        assertEquals("", TranscriptDisplayPolicy.visibleText(
                false, "我看看这件展品。"));
    }

    @Test
    public void stripsGuidancePrefixButKeepsAnswerInSameSubtitle() {
        assertEquals("这是西汉时期的金缕玉衣。", TranscriptDisplayPolicy.visibleText(
                false, "我查一下它的历史背景。 这是西汉时期的金缕玉衣。"));
    }

    @Test
    public void keepsNormalAiAnswerAndAllUserText() {
        assertEquals("这件展品出土于河北满城。", TranscriptDisplayPolicy.visibleText(
                false, "这件展品出土于河北满城。"));
        assertEquals("让我看看这件展品", TranscriptDisplayPolicy.visibleText(
                true, "让我看看这件展品"));
    }
}
