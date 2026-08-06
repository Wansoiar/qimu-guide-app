package com.qimu.guide.service;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * 流式 TTS 串播器：把后端逐句下发的 audio_chunk（mp3 签名 URL）按到达顺序一句接一句播放。
 *
 * 声音出口由系统音频路由决定：眼镜作为蓝牙 A2DP 设备连着时从眼镜出声，
 * 否则从手机扬声器出声——一套代码覆盖两种场景（端无关）。
 *
 * 用法：
 *   每轮对话开始 reset()；
 *   每收到一个 audio_chunk 调 enqueue(url)（可乱序到达前先 reset 保证从头开始）；
 *   Fragment 销毁时 release()。
 *
 * 说明：后端 audio_chunk 的 sequence 是有序下发的（SSE 顺序保证），
 * 这里按入队顺序播放即可，无需额外按 sequence 重排。
 */
public class AudioChunkPlayer {

    private static final String TAG = "AudioChunkPlayer";

    private final Queue<String> queue = new ArrayDeque<>();
    private MediaPlayer player;
    private boolean playing = false;
    // 轮次令牌：reset() 递增，丢弃上一轮回调，避免跨轮串音
    private int epoch = 0;

    /** 新一轮对话开始：清空队列、停掉正在播的、丢弃旧回调。 */
    public synchronized void reset() {
        epoch++;
        queue.clear();
        playing = false;
        stopPlayer();
    }

    /** 入队一句 TTS 音频 URL，若当前空闲则立即开始播放。 */
    public synchronized void enqueue(String url) {
        if (url == null || url.isEmpty()) return;
        queue.offer(url);
        if (!playing) {
            playNext(epoch);
        }
    }

    private synchronized void playNext(int myEpoch) {
        if (myEpoch != epoch) return;          // 已被 reset，作废
        String url = queue.poll();
        if (url == null) {
            playing = false;
            return;
        }
        playing = true;
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(url);
            mp.setOnPreparedListener(p -> {
                Log.d(TAG, "prepared, start play: " + url);
                p.start();
            });
            mp.setOnCompletionListener(p -> {
                p.release();
                synchronized (AudioChunkPlayer.this) {
                    if (player == p) player = null;
                    playNext(myEpoch);         // 播下一句
                }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.e(TAG, "播放出错 what=" + what + " extra=" + extra + " url=" + url);
                p.release();
                synchronized (AudioChunkPlayer.this) {
                    if (player == p) player = null;
                    playNext(myEpoch);         // 跳过这句继续
                }
                return true;
            });
            player = mp;
            mp.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "playNext 异常: " + e.getMessage(), e);
            playing = false;
            playNext(myEpoch);
        }
    }

    private void stopPlayer() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
            } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    public synchronized void release() {
        epoch++;
        queue.clear();
        playing = false;
        stopPlayer();
    }
}
