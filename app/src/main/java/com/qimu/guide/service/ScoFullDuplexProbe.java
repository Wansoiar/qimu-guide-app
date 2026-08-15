package com.qimu.guide.service;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * SCO 全双工自测探针（一次性验证，非正式链路）。
 *
 * <p>目的：验证「把眼镜当标准蓝牙耳机、走系统 HFP-SCO 路由」时，能否<b>边外放边收音</b>
 * （像微信语音那样的全双工）。若成立，则打断（barge-in）可以不依赖眼镜私有 BLE 通道
 * （{@link GlassesPcmAudioSource} 等），主链路可以大幅拆薄。
 *
 * <p>做法（完全绕开眼镜 SDK，只用 Android 系统音频）：
 * <ol>
 *   <li>{@code setMode(MODE_IN_COMMUNICATION)} + {@code startBluetoothSco()} 进通话音频模式；</li>
 *   <li>等 SCO 真正 connected（否则会走手机麦/听筒，测不准）；</li>
 *   <li>同时开一路 {@link AudioTrack} 持续外放正弦音（VOICE_COMMUNICATION 流，走 SCO 下行）
 *       + 一路 {@link AudioRecord}（VOICE_COMMUNICATION 源，走 SCO 上行）；</li>
 *   <li>持续打印录音 RMS 音量。<b>外放期间对眼镜说话，若 RMS 明显跳动 → 全双工成立。</b></li>
 * </ol>
 *
 * <p>判读：
 * <ul>
 *   <li>外放同时录音 RMS 有随说话起伏 → <b>SCO 全双工 OK，拆薄方案可行</b>；</li>
 *   <li>SCO 起不来 / 录音恒为静音 / 外放一响录音就断 → 固件不支持并发，须回退眼镜私有全双工通道。</li>
 * </ul>
 *
 * 日志 tag 统一 {@code ScoProbe}，同时经 {@link Listener} 回 UI 调试面板。
 */
public final class ScoFullDuplexProbe {

    public interface Listener {
        void onLog(String line);
        void onFinished();
    }

    private static final String TAG = "ScoProbe";
    private static final int SAMPLE_RATE = 16_000;             // SCO 通常 8k/16k，取 16k
    private static final int TONE_HZ = 440;
    private static final long SCO_WAIT_TIMEOUT_MS = 4_000L;
    private static final long RUN_MS = 12_000L;               // 跑 12 秒，够对着说几句

    private final Context appContext;
    private final AudioManager audioManager;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private Thread recordThread;
    private Thread playThread;
    private AudioRecord record;
    private AudioTrack track;
    private int savedMode;

    public ScoFullDuplexProbe(Context context) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    /** 启动探针；结果经 listener 回调（主线程）。重复调用无效直到上一轮结束。 */
    public void start(Listener listener) {
        if (running) {
            emit(listener, "探针已在运行，忽略重复启动");
            return;
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            emit(listener, "缺少 RECORD_AUDIO 权限，无法录音自测");
            main.post(listener::onFinished);
            return;
        }
        if (audioManager == null) {
            emit(listener, "AudioManager 不可用");
            main.post(listener::onFinished);
            return;
        }
        running = true;
        emit(listener, "=== SCO 全双工自测开始 ===");
        emit(listener, "SCO 可用: " + audioManager.isBluetoothScoAvailableOffCall());

        savedMode = audioManager.getMode();
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.startBluetoothSco();
        audioManager.setBluetoothScoOn(true);
        emit(listener, "已请求进 MODE_IN_COMMUNICATION + startBluetoothSco，等待 SCO connected…");

        waitScoConnected(listener, 0);
    }

    /** 轮询等待 SCO 真正连上（isBluetoothScoOn），再开始收发。 */
    private void waitScoConnected(Listener listener, long waited) {
        if (!running) return;
        boolean scoOn = audioManager.isBluetoothScoOn();
        if (scoOn) {
            emit(listener, "SCO 已连接（isBluetoothScoOn=true）→ 开始边放边收");
            beginDuplex(listener);
            return;
        }
        if (waited >= SCO_WAIT_TIMEOUT_MS) {
            emit(listener, "⚠️ 等 " + (SCO_WAIT_TIMEOUT_MS / 1000)
                    + "s SCO 仍未连上——可能眼镜未走 HFP，或系统未建 SCO。"
                    + "仍继续测（此时大概率走手机内置麦，非眼镜）。");
            beginDuplex(listener);
            return;
        }
        main.postDelayed(() -> waitScoConnected(listener, waited + 300L), 300L);
    }

    private void beginDuplex(Listener listener) {
        final long endAt = System.currentTimeMillis() + RUN_MS;

        // 外放线程：VOICE_COMMUNICATION 流的正弦音，走 SCO 下行。
        playThread = new Thread(() -> playTone(listener, endAt), "sco-probe-play");
        // 录音线程：VOICE_COMMUNICATION 源，走 SCO 上行，持续打印 RMS。
        recordThread = new Thread(() -> recordAndMeter(listener, endAt), "sco-probe-record");
        playThread.start();
        recordThread.start();

        main.postDelayed(() -> stop(listener), RUN_MS + 200L);
    }

    private void playTone(Listener listener, long endAt) {
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = SAMPLE_RATE;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (RuntimeException e) {
            emit(listener, "AudioTrack 创建失败: " + e.getMessage());
            return;
        }
        short[] buf = new short[SAMPLE_RATE / 10]; // 100ms
        double phase = 0;
        double step = 2 * Math.PI * TONE_HZ / SAMPLE_RATE;
        track.play();
        emit(listener, "外放已开始（440Hz 正弦音，应从眼镜出声）");
        while (running && System.currentTimeMillis() < endAt) {
            for (int i = 0; i < buf.length; i++) {
                buf[i] = (short) (Math.sin(phase) * 6000); // 适中音量
                phase += step;
                if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
            }
            track.write(buf, 0, buf.length);
        }
    }

    private void recordAndMeter(Listener listener, long endAt) {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = SAMPLE_RATE;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
        } catch (SecurityException | IllegalArgumentException e) {
            emit(listener, "AudioRecord 创建失败: " + e.getMessage());
            return;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            emit(listener, "AudioRecord 未初始化，录音源不可用");
            return;
        }
        short[] buf = new short[SAMPLE_RATE / 10]; // 100ms
        record.startRecording();
        emit(listener, "录音已开始（VOICE_COMMUNICATION 源）→ 现在对着眼镜说话，看 RMS 是否跳动");
        int tick = 0;
        int silentStreak = 0;
        int liveStreak = 0;
        while (running && System.currentTimeMillis() < endAt) {
            int n = record.read(buf, 0, buf.length);
            if (n <= 0) continue;
            double sum = 0;
            for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
            int rms = (int) Math.sqrt(sum / n);
            if (rms > 500) liveStreak++; else silentStreak++;
            if (++tick % 3 == 0) { // 每 ~300ms 报一次，避免刷屏
                emit(listener, "录音 RMS=" + rms + (rms > 500 ? "  ← 有声音输入" : ""));
            }
        }
        emit(listener, "录音统计：有声帧≈" + liveStreak + "  静音帧≈" + silentStreak);
    }

    /** 停止并恢复音频模式；幂等。 */
    public void stop(Listener listener) {
        if (!running) return;
        running = false;
        try { if (record != null) { record.stop(); record.release(); } } catch (RuntimeException ignored) { }
        try { if (track != null) { track.stop(); track.release(); } } catch (RuntimeException ignored) { }
        record = null;
        track = null;
        try {
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setMode(savedMode);
        } catch (RuntimeException e) {
            Log.w(TAG, "恢复音频模式失败", e);
        }
        emit(listener, "=== 自测结束（已恢复音频模式）===");
        emit(listener, "判读：外放期间录音 RMS 有随说话起伏=全双工OK可拆薄；"
                + "SCO起不来/RMS恒静音/外放一响就断=需回退眼镜私有全双工通道");
        main.post(listener::onFinished);
    }

    private void emit(Listener listener, String line) {
        Log.i(TAG, line);
        if (listener != null) main.post(() -> listener.onLog(line));
    }
}
