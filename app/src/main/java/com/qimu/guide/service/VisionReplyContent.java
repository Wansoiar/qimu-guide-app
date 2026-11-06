package com.qimu.guide.service;

import androidx.annotation.Nullable;

import com.qimu.guide.net.GuideApiClient;

/**
 * 识图结果 → 回填给模型的文案。纯函数，不依赖 Android，便于单元测试。
 *
 * <p>按后端识别状态分流：high_conf / web_identified 都直接讲 summary，
 * ambiguous 引导确认，未匹配建议重拍。未知状态兜底：有 summary 就按普通讲解注入，
 * 避免后端新增状态时误报“没认出”。
 */
final class VisionReplyContent {
    private VisionReplyContent() { }

    static String build(@Nullable GuideApiClient.ImageDescribeResult desc) {
        boolean hasSummary = desc != null && desc.summary != null
                && !desc.summary.trim().isEmpty();
        String exhibitName = desc != null && desc.exhibitName != null
                && !desc.exhibitName.trim().isEmpty()
                ? desc.exhibitName.trim() : "眼前这件";
        if (desc != null && (desc.isHighConf() || desc.isWebIdentified()) && hasSummary) {
            // 已识别（馆内展品或联网识图）：直接口语化讲解 summary。
            return "这是「" + exhibitName + "」。以下是讲解资料，"
                    + "请用讲解员口吻面向游客口语化介绍：" + desc.summary;
        }
        if (desc != null && desc.isWebIdentified()) {
            // 联网识图但 summary 为空（后端不保证非空）：用通用知识介绍。
            return "这是「" + exhibitName + "」，暂时没有现成的介绍资料。"
                    + "请用讲解员口吻结合通用知识自然地介绍一下，"
                    + "不要编造具体的年代、作者或馆藏信息。";
        }
        if (desc != null && desc.isAmbiguous() && hasSummary) {
            // 待确认：识别到多个候选，引导用户确认是哪一件，不要硬挑一个讲。
            return "眼前这件有多个相似的候选展品，还不能确定是哪一件，先别急着讲解。"
                    + "请用讲解员口吻自然地把这些候选口语化地说给游客，"
                    + "并问他看的是哪一件，帮你确认后再讲。以下是候选信息：" + desc.summary;
        }
        if (desc != null
                && GuideApiClient.ImageDescribeResult.NOT_RECOGNIZED.equals(desc.recognized)) {
            return notRecognizedPrompt();
        }
        if (hasSummary) {
            // 后端新增/未知状态：按普通识别结果注入，保证对话不中断。
            return "以下是眼前这件展品的介绍资料，请用讲解员口吻面向游客口语化介绍："
                    + desc.summary;
        }
        // 未匹配（含 null/异常）→ 让模型引导用户重拍，保持单一声音。
        return notRecognizedPrompt();
    }

    private static String notRecognizedPrompt() {
        return "没有从本馆知识库里识别出这件展品。请用讲解员口吻告诉游客："
                + "暂时没认出眼前这件，建议靠近一点或换个角度再让我看看。";
    }
}
