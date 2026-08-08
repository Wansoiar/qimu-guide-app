package com.qimu.guide.util;

/**
 * 本地拍照意图兜底匹配。
 *
 * RTC 主链路下，若用户说出明显的“看看眼前/这是什么”类话术，
 * 即使火山 Function Calling 尚未接通，App 也先本地触发一次拍照，
 * 后续仍统一汇入 upload -> inject -> RTC 的单脑链路。
 */
public final class PhotoIntentMatcher {

    private static final String[] TRIGGERS = new String[] {
            "帮我看看眼前",
            "帮我看看我眼前",
            "帮我看看我面前",
            "看看眼前",
            "看看我眼前",
            "看看我面前",
            "帮我看看这个",
            "看看这个",
            "这是什么",
            "这是啥",
            "这是个啥",
            "识别一下这个",
            "识别一下这是什么",
            "帮我识别一下"
    };

    private PhotoIntentMatcher() {}

    public static boolean shouldTriggerPhoto(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return false;
        for (String trigger : TRIGGERS) {
            if (normalized.contains(trigger)) return true;
        }
        return false;
    }

    static String normalize(String text) {
        if (text == null) return "";
        return text
                .toLowerCase()
                .replaceAll("[\\s，。！？、；：,.!?;:'\"（）()【】\\[\\]<>《》]", "")
                .trim();
    }
}
