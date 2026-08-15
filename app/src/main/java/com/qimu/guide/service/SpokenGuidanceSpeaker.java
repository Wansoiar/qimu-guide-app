package com.qimu.guide.service;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Locale;

/** 游览生命周期内复用的轻量 TTS；只负责播放，不产生任何字幕事件。 */
final class SpokenGuidanceSpeaker {

    private static final String TAG = "SpokenGuidance";
    private static final String UTTERANCE_ID = "qimu-process-guidance";

    private final Context appContext;
    private TextToSpeech tts;
    private boolean ready;
    private boolean initializing;
    private String pendingText;

    SpokenGuidanceSpeaker(@NonNull Context context) {
        appContext = context.getApplicationContext();
    }

    void prepare() {
        if (tts != null || initializing) return;
        initializing = true;
        tts = new TextToSpeech(appContext, status -> {
            initializing = false;
            TextToSpeech current = tts;
            if (status != TextToSpeech.SUCCESS || current == null) {
                Log.w(TAG, "TTS 初始化失败 status=" + status);
                ready = false;
                pendingText = null;
                return;
            }
            // 与设备页已经过真机验证的 A2DP TTS 配置保持一致。
            int languageResult = current.setLanguage(Locale.CHINESE);
            ready = languageResult != TextToSpeech.LANG_MISSING_DATA
                    && languageResult != TextToSpeech.LANG_NOT_SUPPORTED;
            current.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            if (!ready) {
                Log.w(TAG, "设备 TTS 不支持中文 languageResult=" + languageResult);
                pendingText = null;
                return;
            }
            String text = pendingText;
            pendingText = null;
            if (text != null) speakNow(text);
        });
    }

    void speak(@NonNull String text) {
        if (!ready || tts == null) {
            pendingText = text;
            prepare();
            return;
        }
        speakNow(text);
    }

    void stop() {
        pendingText = null;
        TextToSpeech current = tts;
        if (current != null) current.stop();
    }

    private void speakNow(String text) {
        TextToSpeech current = tts;
        if (current == null) return;
        int result = current.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID);
        if (result == TextToSpeech.ERROR) {
            Log.w(TAG, "TTS 播放失败 text=" + text);
        } else {
            Log.i(TAG, "已播放过程引导 text=" + text);
        }
    }
}
