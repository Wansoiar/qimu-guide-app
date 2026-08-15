package com.qimu.guide.service;

import androidx.annotation.Nullable;

/**
 * 把用户的最终语音字幕映射为一句很短的过程引导。
 *
 * <p>这些话由端侧 TTS 播放，不进入 RTC 字幕或对话记录。分类放在端侧是为了避免
 * 模型直接调用工具时跳过引导，也避免 MCP 的单一 ComfortWords 套用到所有问题。</p>
 */
public final class SpokenGuidancePolicy {

    public static final String VISION = "让我来看一看～";

    private SpokenGuidancePolicy() {
    }

    /** 拍照意图使用视觉引导；普通问答由 RTC MCP ComfortWords 统一处理。 */
    @Nullable
    public static String phraseForUserText(@Nullable String rawText) {
        if (rawText == null) return null;
        String text = rawText.trim().replaceAll("\\s+", "");
        if (text.isEmpty() || isShortConversation(text)) return null;

        if (containsAny(text,
                "眼前", "帮我看", "帮忙看", "看看这个", "看下这个", "看一下这个",
                "这是什么", "这个是什么", "拍张照", "拍照", "识别一下")) {
            return VISION;
        }
        return null;
    }

    private static boolean isShortConversation(String text) {
        return text.length() <= 8 && containsAny(text,
                "你好", "您好", "嗨", "谢谢", "感谢", "再见", "拜拜", "好的", "好吧",
                "知道了", "明白了", "嗯", "哦", "可以", "没事");
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}
