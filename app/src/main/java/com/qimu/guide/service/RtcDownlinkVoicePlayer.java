package com.qimu.guide.service;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import com.ss.bytertc.engine.RTCEngine;
import com.ss.bytertc.engine.data.AudioChannel;
import com.ss.bytertc.engine.data.AudioSampleRate;
import com.ss.bytertc.engine.utils.AudioFrame;

/**
 * RTC 下行「外部渲染」播放器。
 *
 * <p>背景（2026-08-16 真机定位）：RTC 内部渲染把 AI 下行钉在媒体流（STREAM_MUSIC）输出，
 * 而收音让系统进了通话模式（MODE_IN_COMMUNICATION），安卓在通话模式下把媒体流 track 音量
 * 掐到 ~0.0075 → 下行「高保真但极小」。{@code setAudioScenario} 改不动 stream type。
 *
 * <p>方案 D：把下行渲染改为外部渲染（{@code setAudioRenderType(EXTERNAL)}），由本类用一条
 * 拉流线程 {@link RTCEngine#pullExternalAudioFrame} 主动取解码后的混音 PCM，再用自建
 * {@link AudioTrack}（{@code USAGE_VOICE_COMMUNICATION} / {@code STREAM_VOICE_CALL}）播出。
 * 这样下行就「像打电话一样」走通话流、随通话音量输出，不再被通话模式压低。
 *
 * <p>与上行对称：上行是「外部源 push」，下行是「外部渲染 pull」。上行 SCO 采集不动，打断保持。
 */
public final class RtcDownlinkVoicePlayer {

    private static final String TAG = "RtcDownVoice";

    // 与 RTC 外部渲染契约一致：每 10ms 拉一帧。16k/mono/PCM16 → 160 samples/帧 = 320 bytes。
    private static final int SAMPLE_RATE = 16_000;
    private static final int FRAME_SAMPLES = 160;
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;
    private static final AudioSampleRate RTC_SAMPLE_RATE = AudioSampleRate.AUDIO_SAMPLE_RATE_16000;
    private static final AudioChannel RTC_CHANNEL = AudioChannel.AUDIO_CHANNEL_MONO;

    private final RTCEngine engine;

    private volatile boolean running;
    private Thread pullThread;
    private AudioTrack audioTrack;

    public RtcDownlinkVoicePlayer(RTCEngine engine) {
        this.engine = engine;
    }

    /** 起播：建 VOICE_CALL 通话流 AudioTrack + 拉流线程。可重复调用（幂等）。 */
    public synchronized void start() {
        if (running) return;
        if (engine == null) {
            Log.w(TAG, "start 跳过：engine 为空");
            return;
        }
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = FRAME_BYTES * 8;
        AudioTrack track;
        try {
            // 关键：USAGE_VOICE_COMMUNICATION → 走通话流(STREAM_VOICE_CALL)，与 SCO/通话模式同路，
            // 不被通话模式当媒体流压低。这正是「像打电话一样」的下行。
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (RuntimeException e) {
            Log.e(TAG, "创建 VOICE_CALL AudioTrack 失败", e);
            return;
        }
        audioTrack = track;
        try {
            track.play();
        } catch (RuntimeException e) {
            Log.e(TAG, "AudioTrack.play 失败", e);
            releaseTrack();
            return;
        }
        running = true;
        pullThread = new Thread(this::pullLoop, "rtc-downlink-pull");
        pullThread.setDaemon(true);
        pullThread.start();
        Log.i(TAG, "下行外部渲染播放器已启动（VOICE_CALL 通话流）");
    }

    /** 停播：停线程 + 释放 AudioTrack。可重复调用。 */
    public synchronized void stop() {
        running = false;
        Thread t = pullThread;
        pullThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        releaseTrack();
        Log.i(TAG, "下行外部渲染播放器已停止");
    }

    private void releaseTrack() {
        AudioTrack track = audioTrack;
        audioTrack = null;
        if (track != null) {
            try {
                track.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                track.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void pullLoop() {
        // 复用同一个 AudioFrame + buffer，SDK 每次把 10ms 混音 PCM 填进 buffer。
        byte[] buffer = new byte[FRAME_BYTES];
        AudioFrame frame = new AudioFrame(buffer, FRAME_SAMPLES, RTC_SAMPLE_RATE, RTC_CHANNEL);
        long nextTickNanos = System.nanoTime();
        while (running) {
            RTCEngine currentEngine = engine;
            AudioTrack track = audioTrack;
            if (currentEngine == null || track == null) break;
            try {
                int ret = currentEngine.pullExternalAudioFrame(frame);
                if (ret == 0) {
                    byte[] out = frame.buffer != null ? frame.buffer : buffer;
                    track.write(out, 0, Math.min(out.length, FRAME_BYTES));
                }
                // ret != 0 通常是暂时没有可播数据，跳过本帧即可（不写=静默）。
            } catch (RuntimeException e) {
                Log.w(TAG, "pullExternalAudioFrame 异常", e);
            }
            // 稳定 10ms 节拍，避免忙等打满 CPU，又不积压。
            nextTickNanos += 10_000_000L;
            long sleep = nextTickNanos - System.nanoTime();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } else {
                // 落后太多则重置基准，防止追赶式突发。
                nextTickNanos = System.nanoTime();
            }
        }
    }
}
