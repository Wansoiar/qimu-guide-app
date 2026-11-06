package com.qimu.guide.service;

import com.qimu.guide.net.GuideApiClient;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VisionReplyContentTest {
    private static GuideApiClient.ImageDescribeResult result(
            String recognized, String exhibitName, String summary) {
        return new GuideApiClient.ImageDescribeResult(recognized, exhibitName, summary);
    }

    @Test
    public void highConfInjectsSummaryForExplanation() {
        String content = VisionReplyContent.build(
                result(GuideApiClient.ImageDescribeResult.HIGH_CONF, "青花瓷瓶", "明代青花"));
        assertTrue(content.contains("青花瓷瓶"));
        assertTrue(content.contains("明代青花"));
    }

    @Test
    public void highConfWithoutSummaryFallsBackToRetake() {
        String content = VisionReplyContent.build(
                result(GuideApiClient.ImageDescribeResult.HIGH_CONF, "青花瓷瓶", "  "));
        assertTrue(content.contains("暂时没认出"));
    }

    @Test
    public void webIdentifiedWithSummaryExplainsLikeHighConf() {
        String content = VisionReplyContent.build(
                result(GuideApiClient.ImageDescribeResult.WEB_IDENTIFIED, "蒙娜丽莎", "文艺复兴名作"));
        assertTrue(content.contains("蒙娜丽莎"));
        assertTrue(content.contains("文艺复兴名作"));
        assertFalse(content.contains("联网识别"));
        assertFalse(content.contains("知识库"));
        assertFalse(content.contains("暂时没认出"));
    }

    @Test
    public void webIdentifiedWithoutSummaryStillGuidesGeneralKnowledge() {
        String content = VisionReplyContent.build(
                result(GuideApiClient.ImageDescribeResult.WEB_IDENTIFIED, "蒙娜丽莎", ""));
        assertTrue(content.contains("通用知识"));
        assertFalse(content.contains("联网识别"));
        assertFalse(content.contains("暂时没认出"));
    }

    @Test
    public void webIdentifiedUsesGenericNameWhenExhibitNameMissing() {
        String content = VisionReplyContent.build(
                result(GuideApiClient.ImageDescribeResult.WEB_IDENTIFIED, "", "某件雕塑"));
        assertTrue(content.contains("眼前这件"));
        assertFalse(content.contains("「」"));
    }

    @Test
    public void ambiguousAsksForConfirmation() {
        String content = VisionReplyContent.build(
                result(GuideApiClient.ImageDescribeResult.AMBIGUOUS, "", "候选A / 候选B"));
        assertTrue(content.contains("候选"));
        assertTrue(content.contains("候选A / 候选B"));
    }

    @Test
    public void notRecognizedAdvisesRetake() {
        String content = VisionReplyContent.build(
                result(GuideApiClient.ImageDescribeResult.NOT_RECOGNIZED, "", ""));
        assertTrue(content.contains("暂时没认出"));
    }

    @Test
    public void nullResultAdvisesRetake() {
        String content = VisionReplyContent.build(null);
        assertTrue(content.contains("暂时没认出"));
    }

    @Test
    public void unknownStateWithSummaryInjectsInsteadOfRetake() {
        String content = VisionReplyContent.build(
                result("some_new_state", "新展品", "新状态介绍"));
        assertTrue(content.contains("新状态介绍"));
        assertFalse(content.contains("暂时没认出"));
    }

    @Test
    public void unknownStateWithoutSummaryFallsBackToRetake() {
        String content = VisionReplyContent.build(
                result("some_new_state", "新展品", ""));
        assertTrue(content.contains("暂时没认出"));
    }
}
