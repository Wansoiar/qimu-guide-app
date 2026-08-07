package com.qimu.guide.service;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import com.google.protobuf.ByteString;
import com.moyoung.glasses.conn.CRPBleConnection;
import com.moyoung.glasses.conn.listener.CRPAiDialogueListener;
import com.moyoung.glasses.conn.protos.AiDialogueInfo;
import com.moyoung.glasses.conn.protos.FlowStatus;
import com.moyoung.glasses.conn.protos.TakePhoto;
import com.qimu.guide.QimuApplication;
import com.qimu.guide.util.BluetoothDataProcessor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * AI 对话通道管理。
 * 负责接收眼镜端 PCM 音频流、AI 识别图片，并支持向眼镜推送文字内容。
 */
public class AIDialogueManager {

    private static final String TAG = "AIDialogueManager";
    private static final int SAMPLE_RATE = 16000;

    private final CRPBleConnection connection;
    private final Object callbackLock = new Object();
    private AudioTrack audioTrack;
    private volatile DialogueCallback callback;
    private volatile boolean isDialogueActive = false;
    private volatile boolean released;
    private int audioChunkCount = 0; // 调试用

    // 用于保存接收到的 PCM 音频数据到文件（测试用）
    private File pcmOutputFile;
    private FileOutputStream pcmOutputStream;

    public interface DialogueCallback {
        void onDialogueStart();
        void onDialogueAudioChange(byte[] audioData);
        void onDialogueImageChange(File imageFile);
        void onDialogueStop(boolean isTimeout);
        void onTranslationAudioChange(byte[] audioData);
        void onError(String message);
    }

    /** API 21 兼容的回调执行接口 */
    public interface CallbackAction {
        void execute(DialogueCallback callback);
    }

    public AIDialogueManager(CRPBleConnection connection) {
        this.connection = connection;
    }

    public void setCallback(DialogueCallback callback) {
        synchronized (callbackLock) {
            if (released) return;
            this.callback = callback;
        }
    }

    /**
     * 初始化 AI 对话监听器
     */
    public void setupAiDialogueListener() {
        synchronized (callbackLock) {
            if (released) return;
            connection.setAiDialogueListener(new CRPAiDialogueListener() {
                @Override
                public void onDialogueStart() {
                    if (released) return;
                    isDialogueActive = true;
                    audioChunkCount = 0;
                    Log.d(TAG, "AI 对话开始");
                    BleService.getInstance().postLog("AI", "对话开始");
                    startPcmCapture();
                    notifyCallback(c -> c.onDialogueStart());
                }

                @Override
                public void onDialogueAudioChange(byte[] audioBytes) {
                    if (released || audioBytes == null) return;
                    audioChunkCount++;
                    if (audioChunkCount == 1 || audioChunkCount % 50 == 0) {
                        Log.d(TAG, "收到 AI 音频 #" + audioChunkCount + ": "
                                + audioBytes.length + " bytes");
                    }
                    if (audioChunkCount % 50 == 0) { // 每50块(~1秒)记录一次
                        BleService.getInstance().postLog("AI", "音频流 #" + audioChunkCount + " 累计约" + (audioChunkCount * 640 / 32000) + "s");
                    }
                    savePcmData(audioBytes);
                    notifyCallback(c -> c.onDialogueAudioChange(audioBytes));
                }

                @Override
                public void onDialogueImageChange(File file) {
                    if (released || file == null) return;
                    Log.d(TAG, "收到 AI 图片: " + file.getAbsolutePath());
                    BleService.getInstance().postLog("AI", "收到图片: " + file.getName());
                    notifyCallback(c -> c.onDialogueImageChange(file));
                }

                @Override
                public void onDialogueStop(boolean isTimeout) {
                    if (released) return;
                    isDialogueActive = false;
                    stopPcmCapture();
                    Log.d(TAG, "AI 对话结束, isTimeout=" + isTimeout + ", 总块数=" + audioChunkCount);
                    BleService.getInstance().postLog("AI", "对话结束 isTimeout=" + isTimeout + " 总块数=" + audioChunkCount);
                    notifyCallback(c -> c.onDialogueStop(isTimeout));
                }
            });
        }

        // 初始化 AudioTrack 用于播放 AI 回复音频
        initAudioTrack();
    }

    /**
     * 设置同声传译监听器（流式音频通道）
     */
    public void setupTranslationListener() {
        synchronized (callbackLock) {
            if (released) return;
            connection.setTranslationListener(audioBytes -> {
                if (released || audioBytes == null) return;
                Log.d(TAG, "收到同声传译音频: " + audioBytes.length + " bytes");
                notifyCallback(c -> c.onTranslationAudioChange(audioBytes));
            });
        }
    }

    /**
     * AI 识图拍照 — 触发眼镜拍照并接收识别结果
     */
    public void takePhotoWithAI() {
        if (released) return;
        connection.takePhoto(TakePhoto.PhotoMode.ModeAIRecognition);
        Log.d(TAG, "AI 拍照指令已发送");
    }

    /**
     * 普通拍照
     */
    public void takePhoto() {
        if (released) return;
        connection.takePhoto(TakePhoto.PhotoMode.ModeNormal);
        Log.d(TAG, "拍照指令已发送");
    }

    /**
     * 推送文字内容到眼镜（AI 讲解词）
     * 眼镜端会显示文字并通过 TTS 朗读
     */
    public void sendTextToGlasses(String text) {
        if (released || text == null || text.isEmpty()) {
            Log.e(TAG, "sendTextToGlasses: text is empty");
            return;
        }

        BluetoothDataProcessor.ProcessResult processResult =
                BluetoothDataProcessor.processDataForBluetooth(ByteString.copyFromUtf8(text));

        AiDialogueInfo info = AiDialogueInfo.newBuilder()
                .setDialogueID(1)
                .setSentenceID(1)
                .setFinishStatus(true)
                .setActive(AiDialogueInfo.ActiveType.TypeAnswer)
                .setDialogueContentBytes(processResult.processedData)
                .build();

        connection.sendAiDialogue(info);
        Log.d(TAG, "已推送文字到眼镜: " + text.substring(0, Math.min(text.length(), 50)));
    }

    /**
     * 发送 AI 对话状态到设备
     */
    public void sendDialogueState(FlowStatus.FlowStatusType type) {
        if (released) return;
        connection.sendAIDialogueState(type);
    }

    /**
     * 退出 AI 对话
     */
    public void exitAIDialogue() {
        if (released) return;
        connection.exitAIDialogue();
        isDialogueActive = false;
        stopPcmCapture();
    }

    /**
     * 同步时间（连接后调用）
     */
    public void syncTime() {
        if (released) return;
        connection.syncTime();
    }

    /**
     * 播放 AI 回复音频（PCM 格式，在手机端播放）
     */
    public synchronized void playAudioData(byte[] audioData) {
        if (released || audioData == null || audioData.length == 0) return;
        AudioTrack track = audioTrack;
        if (track != null) {
            try {
                if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play();
                }
                track.write(audioData, 0, audioData.length);
            } catch (IllegalStateException e) {
                if (!released) Log.e(TAG, "播放 PCM 失败", e);
            }
        }
    }

    private synchronized void initAudioTrack() {
        if (released || audioTrack != null) return;
        int bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (bufferSize <= 0) {
            Log.e(TAG, "AudioTrack buffer size 无效: " + bufferSize);
            return;
        }
        try {
            audioTrack = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
            );
        } catch (Exception e) {
            Log.e(TAG, "创建 AudioTrack 失败", e);
        }
    }

    public boolean isDialogueActive() {
        return isDialogueActive;
    }

    public void release() {
        synchronized (callbackLock) {
            if (released) return;
            released = true;
            callback = null;
        }
        isDialogueActive = false;
        try {
            connection.setAiDialogueListener(null);
        } catch (Exception e) {
            Log.w(TAG, "清理 AI 对话监听器失败", e);
        }
        try {
            connection.setTranslationListener(null);
        } catch (Exception e) {
            Log.w(TAG, "清理同声传译监听器失败", e);
        }
        synchronized (this) {
            AudioTrack track = audioTrack;
            audioTrack = null;
            if (track != null) {
                try {
                    if (track.getState() == AudioTrack.STATE_INITIALIZED
                            && track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
                        track.stop();
                    }
                } catch (Exception ignored) {
                }
                try {
                    track.release();
                } catch (Exception ignored) {
                }
            }
        }
        stopPcmCapture();
    }

    // ── PCM 音频保存（测试用） ────────────────────────────────

    private synchronized void startPcmCapture() {
        if (released) return;
        stopPcmCapture();
        try {
            String timeStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
            File audioRoot = QimuApplication.getAppContext().getExternalFilesDir("audio");
            if (audioRoot == null) return;
            File dir = new File(audioRoot, "pcm");
            if (!dir.exists()) dir.mkdirs();
            pcmOutputFile = new File(dir, "dialogue_" + timeStr + ".pcm");
            pcmOutputStream = new FileOutputStream(pcmOutputFile);
            Log.d(TAG, "开始保存 PCM 到: " + pcmOutputFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "创建 PCM 文件失败", e);
        }
    }

    private synchronized void savePcmData(byte[] data) {
        if (released) return;
        if (pcmOutputStream != null) {
            try {
                pcmOutputStream.write(data);
            } catch (IOException e) {
                Log.e(TAG, "保存 PCM 数据失败", e);
            }
        }
    }

    private synchronized void stopPcmCapture() {
        if (pcmOutputStream != null) {
            try {
                pcmOutputStream.close();
                Log.d(TAG, "PCM 保存完成: " + (pcmOutputFile != null ? pcmOutputFile.getAbsolutePath() : ""));
            } catch (IOException e) {
                Log.e(TAG, "关闭 PCM 文件失败", e);
            }
            pcmOutputStream = null;
        }
    }

    private void notifyCallback(CallbackAction action) {
        synchronized (callbackLock) {
            DialogueCallback current = callback;
            if (!released && current != null) {
                action.execute(current);
            }
        }
    }
}
