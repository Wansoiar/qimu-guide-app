package com.qimu.guide.util;

/**
 * 本地拍照意图兜底匹配。
 *
 * RTC 主链路下，若用户说出明显的“看看眼前/这是什么”类话术，
 * 即使火山 Function Calling 尚未接通，App 也先本地触发一次拍照，
 * 后续仍统一汇入 upload -> inject -> RTC 的单脑链路。
 */
public final class PhotoIntentMatcher {

    private static final String[] STRONG_TRIGGERS = new String[] {
            "帮我看看眼前",
            "帮我看看我眼前",
            "帮我看看我面前",
            "我眼前有什么",
            "眼前有什么",
            "我面前有什么",
            "面前有什么",
            "眼前是什么",
            "我眼前是什么",
            "面前是什么",
            "我面前是什么",
            "识别一下这个",
            "识别一下这是什么",
            "帮我识别一下"
    };

    private static final String[] WEAK_TRIGGERS = new String[] {
            "看看眼前",
            "看看我眼前",
            "看看我面前",
            "帮我看看这个",
            "看看这个",
            "这是什么",
            "这是啥",
            "这是个啥"
    };

    private PhotoIntentMatcher() {}

    public static boolean shouldTriggerPhoto(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return false;
        for (String trigger : STRONG_TRIGGERS) {
            if (normalized.contains(trigger)) return true;
        }
        for (String trigger : WEAK_TRIGGERS) {
            if (normalized.contains(trigger)) return true;
        }
        return false;
    }

    /**
     * RTC 字幕里的说话人识别偶尔会抖，用户强意图短句允许放宽；
     * 但像“这是什么”这类弱触发词仍要求明确来自用户，避免 AI 自己说到时误拍照。
     */
    public static boolean shouldTriggerPhotoFromSubtitle(String text, boolean fromSelf) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return false;
        for (String trigger : STRONG_TRIGGERS) {
            if (normalized.contains(trigger)) return true;
        }
        if (!fromSelf) return false;
        for (String trigger : WEAK_TRIGGERS) {
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
