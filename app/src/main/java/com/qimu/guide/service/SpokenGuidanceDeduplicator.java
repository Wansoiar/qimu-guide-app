package com.qimu.guide.service;

import androidx.annotation.NonNull;

/** 同一字幕事件或紧邻的 FC 事件只能触发一次过程引导。 */
final class SpokenGuidanceDeduplicator {

    private static final long SAME_PHRASE_WINDOW_MS = 5_000L;

    private String lastEventId;
    private String lastPhrase;
    private long lastSpokenElapsedMs = Long.MIN_VALUE;

    boolean shouldSpeak(@NonNull String eventId,
                        @NonNull String phrase,
                        long nowElapsedMs) {
        if (eventId.equals(lastEventId)) return false;
        lastEventId = eventId;
        if (phrase.equals(lastPhrase)
                && lastSpokenElapsedMs != Long.MIN_VALUE
                && nowElapsedMs - lastSpokenElapsedMs <= SAME_PHRASE_WINDOW_MS) {
            return false;
        }
        lastPhrase = phrase;
        lastSpokenElapsedMs = nowElapsedMs;
        return true;
    }

    void reset() {
        lastEventId = null;
        lastPhrase = null;
        lastSpokenElapsedMs = Long.MIN_VALUE;
    }
}
