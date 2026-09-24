package com.qimu.guide.service;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

/**
 * 系统 SCO 麦克风收音源。
 *
 * <p>与旧的眼镜私有 BLE Translation 通道（已删除的 GlassesPcmAudioSource：与 A2DP 外放互斥、
 * 讲解外放时收不到用户语音导致打不断，这正是它被淘汰的原因）不同：本源把眼镜当<b>标准蓝牙耳机</b>，进
 * {@code MODE_IN_COMMUNICATION} + {@code startBluetoothSco} 走系统 HFP-SCO 全双工，
 * 用 {@link AudioRecord}（{@code VOICE_COMMUNICATION} 源，16k/mono/PCM16）持续采集，
 * 外放（火山 AI 下行）期间仍能收到用户说话，从而让「发声即打断」在讲解期间生效。
 *
 * <p>真机验证（2026-08-16，SCO 探针）：SCO 可连、外放期间录音 RMS 随说话起伏、
 * 静音期上行干净（无外放回声串入）。本类是探针的生产版：去掉自测正弦音（放音交火山），
 * 只保留 SCO 建链 + 持续 PCM 吐出。
 *
 * <p>对上层暴露与旧私有通道一致的 start/pause/stop + Listener 语义，
 * 便于 {@link RealtimeGuideManager} 平替。区别：本源走系统麦，不需要 BLE connection，
 * 因此 {@link #start(Context, Listener)} 只吃 Context。
 *
 * <p>PCM 帧与旧源保持一致：16 kHz / mono / PCM16，长度不定，由 {@code RtcVoiceChatManager}
 * 内部按 10 ms 重帧后注入 RTC external audio source。
 */
public final class ScoMicAudioSource {

    private static final String TAG = "ScoMicSource";
    private static final int SAMPLE_RATE = 16_000;
    private static final long SCO_WAIT_TIMEOUT_MS = 4_000L;
    private static final long SCO_POLL_INTERVAL_MS = 250L;
    /** 每次 read 的目标时长；20 ms 与 RTC 10 ms 重帧对齐良好且回调不过密。 */
    private static final int READ_MS = 20;

    public interface Listener {
        void onStarted();
        void onPcm(byte[] pcm);
        default void onAudioFocusInterrupted(String message) { }
        default void onAudioFocusRestored() { }
        void onError(int code, String message);
    }

    private final Context appContext;
    private final AudioManager audioManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private int generation;
    private volatile boolean running;
    private Thread recordThread;
    private AudioRecord record;
    private int savedMode;
    private boolean modeChanged;
    private boolean audioFocusHeld;
    private boolean focusInterrupted;
    private AudioFocusRequest audioFocusRequest;
    private volatile Listener activeListener;
    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            Listener current;
            synchronized (lock) {
                if (!focusInterrupted) return;
                focusInterrupted = false;
                current = activeListener;
            }
            if (current != null) {
                Log.i(TAG, "音频焦点已恢复，准备恢复眼镜收音");
                current.onAudioFocusRestored();
            }
            return;
        }
        Listener current = activeListener;
        if (current == null) return;
        if (isTransientFocusLoss(focusChange)) {
            synchronized (lock) {
                focusInterrupted = true;
            }
            Log.i(TAG, "音频焦点被短暂占用，等待系统恢复: " + focusChange);
            // 保留 SCO、通话模式和焦点监听，只暂停 PCM。AUDIOFOCUS_GAIN
            // 到来后由上层按原有 desiredListening 意图恢复。
            stopRecordingOnly();
            current.onAudioFocusInterrupted("音频被暂时占用，结束后将自动恢复");
            return;
        }
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            Log.i(TAG, "音频焦点被长期占用，停止眼镜收音");
            stop();
            current.onError(-15, "音频被通话或其他应用长期占用");
        }
    };

    static boolean isTransientFocusLoss(int focusChange) {
        return focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK;
    }

    public ScoMicAudioSource(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    /**
     * 进通话音频模式、开 SCO，等 SCO 连上后开始持续采集。
     * 成功（SCO 连上、录音启动）回调 {@link Listener#onStarted()}；失败回调 onError。
     * 暂停后可再次调用 start 恢复。
     */
    public void start(@NonNull Context context, @NonNull Listener newListener) {
        final int requestGeneration;
        synchronized (lock) {
            generation++;
            requestGeneration = generation;
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            newListener.onError(-10, "缺少录音权限");
            return;
        }
        if (audioManager == null) {
            newListener.onError(-11, "AudioManager 不可用");
            return;
        }
        activeListener = newListener;
        synchronized (lock) {
            focusInterrupted = false;
        }
        if (!requestAudioFocus()) {
            activeListener = null;
            newListener.onError(-15, "无法取得通话音频使用权");
            return;
        }
        Log.i(TAG, "start() 进通话模式 + startBluetoothSco");
        synchronized (lock) {
            if (!modeChanged) {
                savedMode = audioManager.getMode();
                modeChanged = true;
            }
        }
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.startBluetoothSco();
        audioManager.setBluetoothScoOn(true);
        raiseVoiceCallVolume();
        waitScoConnected(requestGeneration, newListener, 0L);
    }

    /**
     * 通话模式下 AI 下行走 STREAM_VOICE_CALL；其在 SCO 上默认档位偏低（真机实测 6/15，
     * 声音很小要开到最大才勉强听见）。这里把通话音量拉到最大档，让眼镜里 AI 讲解够响。
     * 恢复由 stop() 里 setMode(savedMode) 后系统按原场景管理，不单独还原音量档。
     */
    private void raiseVoiceCallVolume() {
        try {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0);
            Log.i(TAG, "通话音量拉满 STREAM_VOICE_CALL=" + max);
        } catch (RuntimeException e) {
            Log.w(TAG, "设置通话音量失败", e);
        }
    }

    /** 轮询等待 SCO 真正连上再开采集；超时失败关闭，绝不回退到手机麦克风。 */
    private void waitScoConnected(int requestGeneration, Listener listener, long waited) {
        synchronized (lock) {
            if (requestGeneration != generation) return;
        }
        if (audioManager.isBluetoothScoOn()) {
            Log.i(TAG, "SCO 已连接 → 开始采集");
            raiseVoiceCallVolume(); // SCO 建立后再补一次，避免建立前设置被系统重置
            beginRecording(requestGeneration, listener);
            return;
        }
        if (waited >= SCO_WAIT_TIMEOUT_MS) {
            Log.w(TAG, "等 " + (SCO_WAIT_TIMEOUT_MS / 1000)
                    + "s SCO 未连上，拒绝回退到手机麦克风");
            stop();
            listener.onError(-16, "眼镜通话音频未连接");
            return;
        }
        main.postDelayed(() -> waitScoConnected(requestGeneration, listener, waited + SCO_POLL_INTERVAL_MS),
                SCO_POLL_INTERVAL_MS);
    }

    private void beginRecording(int requestGeneration, Listener listener) {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = SAMPLE_RATE;
        AudioRecord created;
        try {
            created = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
        } catch (SecurityException | IllegalArgumentException e) {
            stop();
            listener.onError(-12, "AudioRecord 创建失败: " + e.getMessage());
            return;
        }
        if (created.getState() != AudioRecord.STATE_INITIALIZED) {
            created.release();
            stop();
            listener.onError(-13, "AudioRecord 未初始化");
            return;
        }
        synchronized (lock) {
            if (requestGeneration != generation) {
                created.release();
                return;
            }
            record = created;
            running = true;
        }
        try {
            created.startRecording();
        } catch (IllegalStateException e) {
            synchronized (lock) { record = null; running = false; }
            created.release();
            stop();
            listener.onError(-14, "startRecording 失败: " + e.getMessage());
            return;
        }

        final int frameBytes = SAMPLE_RATE / 1000 * READ_MS * 2; // 20ms * 16k * 2B = 640
        recordThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            byte[] buf = new byte[frameBytes];
            while (true) {
                synchronized (lock) {
                    if (requestGeneration != generation || !running || record == null) break;
                }
                int n = created.read(buf, 0, buf.length);
                if (n <= 0) continue;
                Listener current;
                synchronized (lock) {
                    if (requestGeneration != generation || !running) break;
                    current = listener;
                }
                if (current != null) {
                    byte[] frame = n == buf.length ? buf.clone() : java.util.Arrays.copyOf(buf, n);
                    current.onPcm(frame);
                }
            }
        }, "sco-mic-record");
        recordThread.start();
        Log.i(TAG, "录音线程已启动");
        listener.onStarted();
    }

    /**
     * 暂停采集但保留通话音频模式与 SCO（供拍照/短暂静音后快速恢复）。
     * 恢复时再次调用 start。
     */
    public void pause() {
        stopRecordingOnly();
        activeListener = null;
        synchronized (lock) {
            focusInterrupted = false;
        }
        abandonAudioFocus();
        Log.i(TAG, "pause() 已停采集（保留 SCO）");
    }

    /** 彻底停止：停采集 + 关 SCO + 恢复音频模式。 */
    public void stop() {
        stopRecordingOnly();
        activeListener = null;
        synchronized (lock) {
            focusInterrupted = false;
        }
        abandonAudioFocus();
        AudioManager am = audioManager;
        if (am != null) {
            try {
                am.setBluetoothScoOn(false);
                am.stopBluetoothSco();
            } catch (RuntimeException e) {
                Log.w(TAG, "关闭 SCO 失败", e);
            }
            synchronized (lock) {
                if (modeChanged) {
                    try {
                        am.setMode(savedMode);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "恢复音频模式失败", e);
                    }
                    modeChanged = false;
                }
            }
        }
        Log.i(TAG, "stop() 已释放 SCO 并恢复音频模式");
    }

    private boolean requestAudioFocus() {
        if (audioFocusHeld) return true;
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(audioFocusListener, main)
                    .build();
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(audioFocusListener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
        audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return audioFocusHeld;
    }

    @SuppressWarnings("deprecation")
    private void abandonAudioFocus() {
        if (!audioFocusHeld || audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(audioFocusListener);
        }
        audioFocusHeld = false;
        audioFocusRequest = null;
    }

    private void stopRecordingOnly() {
        Thread thread;
        AudioRecord current;
        synchronized (lock) {
            generation++;
            running = false;
            thread = recordThread;
            recordThread = null;
            current = record;
            record = null;
        }
        if (current != null) {
            // AudioRecord.read() 可能忽略 Thread.interrupt() 并持续阻塞；先 stop
            // 才能立即唤醒读线程，避免焦点切换或照片导出被固定拖慢约 300 ms。
            try { current.stop(); } catch (RuntimeException ignored) { }
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (current != null) {
            current.release();
        }
    }
}
