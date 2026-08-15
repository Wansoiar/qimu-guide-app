package com.qimu.guide.service;

/**
 * 控制 RTC 字幕中哪些内容进入用户可见的对话记录。
 *
 * <p>火山会把工具等待期间的口语引导与正式回答都作为 AI 字幕下发。引导语需要
 * 保留在音频里降低等待感，但不应成为对话气泡。服务端 prompt 只允许使用这里
 * 列出的固定短句；本类只移除 AI 字幕开头的过程短句，不影响用户原话和正文。</p>
 */
public final class TranscriptDisplayPolicy {

    private static final String[] PROCESS_GUIDANCE_PREFIXES = {
            // 当前分类引导语
            "我先确认一下这件展品",
            "我查一下它的历史背景",
            "我核对一下相关细节",
            "我确认一下场馆信息",
            "我看看眼前的展品",
            // 兼容已创建 RTC 会话及旧版本固定话术
            "让我看看这件展品",
            "我看看这件展品",
            "我看一下"
    };

    private TranscriptDisplayPolicy() {
    }

    /**
     * 返回应写入会话气泡的文本；纯过程引导返回空字符串。
     */
    public static String visibleText(boolean fromSelf, String text) {
        if (text == null) return "";
        String visible = text.trim();
        if (visible.isEmpty() || fromSelf) return visible;

        boolean removed;
        do {
            removed = false;
            for (String prefix : PROCESS_GUIDANCE_PREFIXES) {
                if (!visible.startsWith(prefix)) continue;
                visible = trimLeadingSeparators(visible.substring(prefix.length()));
                removed = true;
                break;
            }
        } while (removed && !visible.isEmpty());
        return visible;
    }

    private static String trimLeadingSeparators(String value) {
        int index = 0;
        while (index < value.length()) {
            char ch = value.charAt(index);
            if (Character.isWhitespace(ch)
                    || ch == '，' || ch == '。' || ch == '！' || ch == '？'
                    || ch == '、' || ch == '…'
                    || ch == ',' || ch == '.' || ch == '!' || ch == '?'
                    || ch == ':' || ch == '：' || ch == ';' || ch == '；') {
                index++;
                continue;
            }
            break;
        }
        return value.substring(index).trim();
    }
}
