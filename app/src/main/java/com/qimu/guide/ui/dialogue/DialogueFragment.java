package com.qimu.guide.ui.dialogue;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.moyoung.glasses.conn.CRPBleConnection;
import com.moyoung.glasses.conn.protos.TakePhoto;
import com.qimu.guide.R;
import com.qimu.guide.model.DialogueMessage;
import com.qimu.guide.service.AIDialogueManager;
import com.qimu.guide.service.BleService;
import com.qimu.guide.service.AsrService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DialogueFragment extends Fragment {

    private static final String TAG = "DialogueFragment";
    private AIDialogueManager aiDialogueManager;
    private RecyclerView recyclerMessages;
    private MessageAdapter messageAdapter;
    private final List<DialogueMessage> messages = new ArrayList<>();

    // ── 音频状态 ──
    private int listeningMsgIndex = -1;
    private ByteArrayOutputStream audioBuffer;
    private int totalAudioBytes = 0;
    private static final int SAMPLE_RATE = 16000;
    private static final int ASR_INTERVAL_BYTES = 48000; // ~1.5秒 (16000*2*1.5)
    private int lastAsrBytes = 0;
    private boolean isAsrRunning = false;
    private final StringBuilder recognizedText = new StringBuilder();
    private boolean turnComplete = true;
    private int currentBubbleIndex = -1;
    private android.speech.tts.TextToSpeech tts;
    private final android.os.Handler silenceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable silenceRunnable = new Runnable() {
        @Override
        public void run() {
            turnComplete = true;
            String full = recognizedText.toString().trim();
            if (full.isEmpty()) return;

            CRPBleConnection conn = BleService.getInstance().getConnection();
            if (conn != null) {
                conn.sendAIDialogueState(com.moyoung.glasses.conn.protos.FlowStatus.FlowStatusType.FlowStatusStart);
                Log.d(TAG, "发送 FlowStatusStart");
            }

            if (full.contains("我眼前") || full.contains("我面前") || full.contains("这是什么") || full.contains("这是啥")) {
                triggerAiPhoto();
            }

            if (tts == null) {
                tts = new android.speech.tts.TextToSpeech(requireContext(), status -> {
                    if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                        tts.setLanguage(java.util.Locale.CHINESE);
                        doReplyTts(conn);
                    } else if (conn != null) {
                        conn.sendAIDialogueState(com.moyoung.glasses.conn.protos.FlowStatus.FlowStatusType.FlowStatusComplete);
                    }
                });
            } else {
                doReplyTts(conn);
            }
        }
    };

    private void doReplyTts(CRPBleConnection conn) {
        String reply = "好的，我看到了。";
        tts.speak(reply, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "reply");
        silenceHandler.postDelayed(() -> {
            if (conn != null) {
                conn.sendAIDialogueState(com.moyoung.glasses.conn.protos.FlowStatus.FlowStatusType.FlowStatusComplete);
                Log.d(TAG, "发送 FlowStatusComplete");
            }
        }, 1500);
    }

/** 所有 UI 操作切到主线程（回调来自 BLE 线程） */
    private void uiAddMessage(DialogueMessage msg) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (!isAdded()) return;
            messages.add(msg);
            messageAdapter.notifyItemInserted(messages.size() - 1);
            recyclerMessages.smoothScrollToPosition(messages.size() - 1);
        });
    }

    /** 更新已存在的消息文本（用于"聆听中…"动态更新） */
    private void uiUpdateMessage(int index, String newText) {
        if (!isAdded() || index < 0 || index >= messages.size()) return;
        requireActivity().runOnUiThread(() -> {
            if (!isAdded()) return;
            messages.get(index).setText(newText);
            messageAdapter.notifyItemChanged(index);
        });
    }

    private void triggerAiPhoto() {
        CRPBleConnection c = BleService.getInstance().getConnection();
        if (c == null) {
            uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                    "⚠️ 连接不存在，无法拍照", System.currentTimeMillis()));
            return;
        }
        c.takePhoto(TakePhoto.PhotoMode.ModeAIRecognition);
        Log.d(TAG, "自动拍照 (AI识别)");
        uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                "📷 正在拍照识别...", System.currentTimeMillis()));
    }

    private final AIDialogueManager.DialogueCallback dialogueCallback = new AIDialogueManager.DialogueCallback() {
        @Override
        public void onDialogueStart() {
            Log.d(TAG, "AI对话开始");
            audioBuffer = new ByteArrayOutputStream();
            totalAudioBytes = 0;
            lastAsrBytes = 0;
            isAsrRunning = false;
            recognizedText.setLength(0);
            turnComplete = true;
            currentBubbleIndex = -1;
            // 添加"聆听中…"消息
            DialogueMessage statusMsg = new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                    "🎤 聆听中...", System.currentTimeMillis());
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                messages.add(statusMsg);
                listeningMsgIndex = messages.size() - 1;
                messageAdapter.notifyItemInserted(listeningMsgIndex);
                recyclerMessages.smoothScrollToPosition(listeningMsgIndex);
            });
        }

        @Override
        public void onDialogueAudioChange(byte[] audioData) {
            try { audioBuffer.write(audioData); } catch (Exception ignored) {}
            totalAudioBytes += audioData.length;
            int kb = totalAudioBytes / 1024;
            uiUpdateMessage(listeningMsgIndex, "🎤 聆听中... (" + kb + " KB)");
            Log.d(TAG, "音频累计: " + totalAudioBytes + " bytes, lastAsrBytes=" + lastAsrBytes);

            // ★ 实时 ASR：只送增量音频，避免重复
            int newBytes = totalAudioBytes - lastAsrBytes;
            if (!isAsrRunning && newBytes >= ASR_INTERVAL_BYTES) {
                isAsrRunning = true;
                byte[] full = audioBuffer.toByteArray();
                int deltaLen = full.length - lastAsrBytes;
                byte[] delta = new byte[deltaLen];
                System.arraycopy(full, lastAsrBytes, delta, 0, deltaLen);
                lastAsrBytes = full.length;
                Log.d(TAG, "触发ASR, delta=" + deltaLen + " bytes");
                realtimeAsr(delta);
            }
        }

        @Override
        public void onDialogueImageChange(File imageFile) {
            Log.d(TAG, "收到AI图片: " + imageFile.getAbsolutePath());
            DialogueMessage msg = new DialogueMessage(DialogueMessage.Type.PHOTO,
                    "📷 眼镜拍照", System.currentTimeMillis());
            msg.setImageFile(imageFile);
            uiAddMessage(msg);
        }

        @Override
        public void onDialogueStop(boolean isTimeout) {
            Log.d(TAG, "AI对话结束, isTimeout=" + isTimeout);
            int sec = totalAudioBytes / (SAMPLE_RATE * 2);
            String duration = sec > 0 ? (sec + "秒") : (totalAudioBytes / (SAMPLE_RATE * 2 / 1000) + "ms");
            uiUpdateMessage(listeningMsgIndex, "🎤 录音结束 (" + duration + ", " + (totalAudioBytes / 1024) + " KB)");
            listeningMsgIndex = -1;
            silenceHandler.removeCallbacks(silenceRunnable);

            // 只识别剩余未处理的部分（从 lastAsrBytes 到末尾）
            byte[] pcmData = audioBuffer.toByteArray();
            int remaining = pcmData.length - lastAsrBytes;
            if (remaining > 0 && !isAsrRunning) {
                isAsrRunning = true;
                byte[] chunk = new byte[remaining];
                System.arraycopy(pcmData, lastAsrBytes, chunk, 0, remaining);
                realtimeAsr(chunk);
            }
            audioBuffer = null;
            totalAudioBytes = 0;

            uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                    "对话结束", System.currentTimeMillis()));
        }

        @Override
        public void onTranslationAudioChange(byte[] audioData) {
            Log.d(TAG, "同声传译音频: " + audioData.length + " bytes");
            uiAddMessage(new DialogueMessage(DialogueMessage.Type.VOICE,
                    "🎤 传译音频 (" + audioData.length + " bytes)", System.currentTimeMillis()));
        }

        @Override
        public void onError(String message) {
            Log.e(TAG, "ASR错误: " + message);
            uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                    "⚠️ " + message, System.currentTimeMillis()));
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        return inflater.inflate(R.layout.fragment_dialogue, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        recyclerMessages = v.findViewById(R.id.recycler_messages);
        messageAdapter = new MessageAdapter(messages);
        recyclerMessages.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerMessages.setAdapter(messageAdapter);

        CRPBleConnection conn = BleService.getInstance().getConnection();
        if (conn != null) {
            aiDialogueManager = new AIDialogueManager(conn);
            aiDialogueManager.setCallback(dialogueCallback);
            aiDialogueManager.setupAiDialogueListener();
            aiDialogueManager.setupTranslationListener();
            uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                    "🟢 已就绪。按眼镜左键 → AI对话", System.currentTimeMillis()));
        } else {
            uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                    "🔴 未连接", System.currentTimeMillis()));
        }

        v.findViewById(R.id.btn_take_photo).setOnClickListener(vi -> {
            CRPBleConnection c = BleService.getInstance().getConnection();
            if (c == null) { Toast.makeText(getContext(), "请先连接眼镜", Toast.LENGTH_SHORT).show(); return; }
            c.takePhoto(TakePhoto.PhotoMode.ModeAIRecognition);
            Log.d(TAG, "takePhoto(ModeAIRecognition)");
            uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                    "📷 拍照指令已发送", System.currentTimeMillis()));
            Toast.makeText(getContext(), "拍照指令已发送", Toast.LENGTH_SHORT).show();
        });

        v.findViewById(R.id.btn_push_text).setOnClickListener(vi ->
                Toast.makeText(getContext(), "按眼镜左键说话", Toast.LENGTH_LONG).show());
    }

    /** 实时 ASR：将当前缓冲区转为 WAV 并调用豆包 ASR */
    private void realtimeAsr(byte[] pcmData) {
        try {
            File dir = new File(requireContext().getExternalFilesDir("audio"), "pcm");
            if (!dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File wavFile = new File(dir, "asr_" + ts + ".wav");

            int channels = 1, bitsPerSample = 16;
            FileOutputStream fos = new FileOutputStream(wavFile);
            writeWavHeader(fos, pcmData.length, SAMPLE_RATE, channels, bitsPerSample);
            // 音量放大 10 倍（豆包 ASR 判定为静音太敏感）
            byte[] amplified = new byte[pcmData.length];
            for (int i = 0; i < pcmData.length; i += 2) {
                short s = (short) ((pcmData[i+1] << 8) | (pcmData[i] & 0xFF));
                int a = s * 10;
                if (a > 32767) a = 32767;
                if (a < -32768) a = -32768;
                amplified[i] = (byte) (a & 0xFF);
                amplified[i+1] = (byte) ((a >> 8) & 0xFF);
            }
            fos.write(amplified);
            fos.close();

            new AsrService().recognize(wavFile, new AsrService.AsrCallback() {

                @Override
                public void onResult(String text, int durationMs) {
                    isAsrRunning = false;
                    Log.d(TAG, "ASR增量: " + text);
                    if (text.isEmpty()) return;

                    // 上一段结束则创建新气泡
                    if (turnComplete) {
                        turnComplete = false;
                        recognizedText.setLength(0);
                        DialogueMessage msg = new DialogueMessage(DialogueMessage.Type.VOICE, "", System.currentTimeMillis());
                        messages.add(msg);
                        currentBubbleIndex = messages.size() - 1;
                        messageAdapter.notifyItemInserted(currentBubbleIndex);
                        recyclerMessages.smoothScrollToPosition(currentBubbleIndex);
                    }

                    // 文字追加到当前气泡
                    recognizedText.append(text);
                    if (currentBubbleIndex >= 0) {
                        uiUpdateMessage(currentBubbleIndex, "🎤\n" + recognizedText.toString().trim());
                    }

                    // 重置 3 秒停顿定时器
                    silenceHandler.removeCallbacks(silenceRunnable);
                    silenceHandler.postDelayed(silenceRunnable, 3000);
                }

                @Override
                public void onError(String message) {
                    isAsrRunning = false;
                    Log.e(TAG, "ASR: " + message);
                }
            });

        } catch (Exception e) {
            isAsrRunning = false;
            Log.e(TAG, "realtimeAsr异常: " + e.getMessage());
        }
    }

    private void writeWavHeader(FileOutputStream fos, int dataSize, int sampleRate, int channels, int bitsPerSample) throws Exception {
        byte[] header = new byte[44];
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int totalSize = 36 + dataSize;

        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte)(totalSize & 0xff); header[5] = (byte)((totalSize >> 8) & 0xff);
        header[6] = (byte)((totalSize >> 16) & 0xff); header[7] = (byte)((totalSize >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; // chunk size 16
        header[20] = 1; header[21] = 0; // PCM format
        header[22] = (byte)channels; header[23] = 0;
        header[24] = (byte)(sampleRate & 0xff); header[25] = (byte)((sampleRate >> 8) & 0xff);
        header[26] = (byte)((sampleRate >> 16) & 0xff); header[27] = (byte)((sampleRate >> 24) & 0xff);
        header[28] = (byte)(byteRate & 0xff); header[29] = (byte)((byteRate >> 8) & 0xff);
        header[30] = (byte)((byteRate >> 16) & 0xff); header[31] = (byte)((byteRate >> 24) & 0xff);
        header[32] = (byte)blockAlign; header[33] = 0;
        header[34] = (byte)bitsPerSample; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte)(dataSize & 0xff); header[41] = (byte)((dataSize >> 8) & 0xff);
        header[42] = (byte)((dataSize >> 16) & 0xff); header[43] = (byte)((dataSize >> 24) & 0xff);
        fos.write(header);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (aiDialogueManager != null) aiDialogueManager.release();
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
    }
}
