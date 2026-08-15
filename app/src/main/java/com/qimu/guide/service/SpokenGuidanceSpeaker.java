package com.qimu.guide.service;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** 播放由服务端使用 RTC 同款讲解员音色生成的引导音频，不产生字幕事件。 */
final class SpokenGuidanceSpeaker {

    private static final String TAG = "SpokenGuidance";

    private final AudioChunkPlayer player = new AudioChunkPlayer();
    private String visionAudioUrl;

    SpokenGuidanceSpeaker() { }

    void prepare() {
        // MediaPlayer 在真正播放 URL 时按需初始化。
    }

    void configure(@Nullable String visionUrl) {
        visionAudioUrl = normalize(visionUrl);
    }

    void speak(@NonNull String text) {
        String url = SpokenGuidancePolicy.VISION.equals(text) ? visionAudioUrl : null;
        if (url == null) {
            Log.w(TAG, "同音色引导音频不可用，跳过系统 TTS text=" + text);
            return;
        }
        player.reset();
        player.enqueue(url);
        Log.i(TAG, "播放讲解员同音色过程引导 text=" + text);
    }

    void stop() {
        player.reset();
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
