package com.qimu.guide.service;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * 按 SSE 到达顺序串播后端 TTS 语音。系统会把媒体音频路由到已连接的
 * Bluetooth A2DP 设备；没有音频蓝牙连接时则从手机播放。
 */
public class AudioChunkPlayer {

    private static final String TAG = "AudioChunkPlayer";

    private final Queue<String> queue = new ArrayDeque<>();
    private MediaPlayer player;
    private boolean playing = false;
    private int epoch = 0;
    private boolean released;

    public synchronized void reset() {
        if (released) return;
        epoch++;
        queue.clear();
        playing = false;
        stopPlayer();
    }

    public synchronized void enqueue(String url) {
        if (released || url == null || url.isEmpty()) return;
        queue.offer(url);
        if (!playing) playNext(epoch);
    }

    private synchronized void playNext(int currentEpoch) {
        if (released || currentEpoch != epoch) return;
        String url = queue.poll();
        if (url == null) {
            playing = false;
            return;
        }

        playing = true;
        MediaPlayer mediaPlayer = null;
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mediaPlayer.setDataSource(url);
            mediaPlayer.setOnPreparedListener(prepared -> {
                synchronized (AudioChunkPlayer.this) {
                    if (released || currentEpoch != epoch || player != prepared) {
                        releaseSafely(prepared);
                        return;
                    }
                    try {
                        prepared.start();
                    } catch (Exception e) {
                        Log.e(TAG, "启动 TTS 播放失败", e);
                        player = null;
                        releaseSafely(prepared);
                        playNext(currentEpoch);
                    }
                }
            });
            mediaPlayer.setOnCompletionListener(completed -> {
                synchronized (AudioChunkPlayer.this) {
                    if (player != completed || released || currentEpoch != epoch) {
                        releaseSafely(completed);
                        return;
                    }
                    player = null;
                    releaseSafely(completed);
                    playNext(currentEpoch);
                }
            });
            mediaPlayer.setOnErrorListener((failed, what, extra) -> {
                Log.e(TAG, "播放失败 what=" + what + " extra=" + extra + " url=" + url);
                synchronized (AudioChunkPlayer.this) {
                    if (player != failed || released || currentEpoch != epoch) {
                        releaseSafely(failed);
                        return true;
                    }
                    player = null;
                    releaseSafely(failed);
                    playNext(currentEpoch);
                }
                return true;
            });
            player = mediaPlayer;
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "播放 TTS 异常", e);
            if (player == mediaPlayer) player = null;
            releaseSafely(mediaPlayer);
            playNext(currentEpoch);
        }
    }

    private void stopPlayer() {
        MediaPlayer current = player;
        player = null;
        if (current == null) return;
        try {
            if (current.isPlaying()) current.stop();
        } catch (Exception ignored) {
        }
        releaseSafely(current);
    }

    private static void releaseSafely(MediaPlayer mediaPlayer) {
        if (mediaPlayer == null) return;
        try {
            mediaPlayer.release();
        } catch (Exception ignored) {
        }
    }

    public synchronized void release() {
        if (released) return;
        released = true;
        epoch++;
        queue.clear();
        playing = false;
        stopPlayer();
    }
}
