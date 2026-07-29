package com.qimu.guide.ui.dialogue;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.moyoung.glasses.conn.CRPBleConnection;
import com.moyoung.glasses.conn.protos.TakePhoto;
import com.qimu.guide.R;
import com.qimu.guide.model.DialogueMessage;
import com.qimu.guide.net.GuideApiClient;
import com.qimu.guide.service.AIDialogueManager;
import com.qimu.guide.service.BleService;
import com.qimu.guide.service.MicRecorder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * AI 对话页。
 *
 * 阶段 2 改造后的链路：
 *   眼镜 PCM 音频（整段缓冲）→ 封 WAV → 后端 /v1/upload/audio 拿 audio_id
 *   → 后端 /v1/query(voice) → 后端内部做 ASR/RAG/LLM/TTS
 *   → SSE 逐帧：text_delta 实时上屏、done 拿 full_text 回推眼镜。
 *
 * 与旧版的区别：不再在 App 端直连豆包 ASR（已收归后端，密钥不再落 App），
 * 也不再回写死的"好的，我看到了"，而是真 AI 讲解。
 */
public class DialogueFragment extends Fragment {

    private static final String TAG = "DialogueFragment";
    private static final int SAMPLE_RATE = 16000;

    private AIDialogueManager aiDialogueManager;
    private RecyclerView recyclerMessages;
    private MessageAdapter messageAdapter;
    private final List<DialogueMessage> messages = new ArrayList<>();

    private final GuideApiClient apiClient = new GuideApiClient();

    // 手机麦克风（无眼镜时的音频来源；眼镜在时也可用）
    private final MicRecorder micRecorder = new MicRecorder();
    private final androidx.activity.result.ActivityResultLauncher<String> micPermLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (!granted) Toast.makeText(getContext(), "需要麦克风权限", Toast.LENGTH_SHORT).show();
                    });

    // ── 音频状态 ──
    private int listeningMsgIndex = -1;
    private ByteArrayOutputStream audioBuffer;
    private int totalAudioBytes = 0;
    // 本轮 AI 回复气泡（流式追加 text_delta）
    private int replyBubbleIndex = -1;
    private final StringBuilder replyText = new StringBuilder();
    // 本轮"我说的话"气泡（onDone 时填 ASR 识别结果）
    private int userBubbleIndex = -1;

    // ── 眼镜 AI 对话回调 ──
    private final AIDialogueManager.DialogueCallback dialogueCallback = new AIDialogueManager.DialogueCallback() {
        @Override
        public void onDialogueStart() {
            Log.d(TAG, "AI对话开始");
            audioBuffer = new ByteArrayOutputStream();
            totalAudioBytes = 0;
            // "聆听中"状态气泡
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
            if (audioBuffer != null) {
                try { audioBuffer.write(audioData); } catch (Exception ignored) {}
            }
            totalAudioBytes += audioData.length;
            int kb = totalAudioBytes / 1024;
            uiUpdateMessage(listeningMsgIndex, "🎤 聆听中... (" + kb + " KB)");
        }

        @Override
        public void onDialogueImageChange(File imageFile) {
            Log.d(TAG, "收到AI图片: " + imageFile.getAbsolutePath());
            DialogueMessage msg = new DialogueMessage(DialogueMessage.Type.PHOTO,
                    "📷 眼镜拍照", System.currentTimeMillis());
            msg.setImageFile(imageFile);
            uiAddMessage(msg);
            // 图片识物链路（image_id → query 带 image_id）留到下一轮，本阶段先打通语音。
        }

        @Override
        public void onDialogueStop(boolean isTimeout) {
            Log.d(TAG, "AI对话结束, isTimeout=" + isTimeout);
            int sec = totalAudioBytes / (SAMPLE_RATE * 2);
            uiUpdateMessage(listeningMsgIndex,
                    "🎤 录音结束 (" + sec + "秒, " + (totalAudioBytes / 1024) + " KB)");

            byte[] pcm = audioBuffer != null ? audioBuffer.toByteArray() : new byte[0];
            audioBuffer = null;
            listeningMsgIndex = -1;
            totalAudioBytes = 0;

            if (pcm.length == 0) {
                uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                        "⚠️ 没录到音频", System.currentTimeMillis()));
                return;
            }
            // 整段音频 → 后端真链路
            sendToBackend(pcm);
        }

        @Override
        public void onTranslationAudioChange(byte[] audioData) {
            // 同声传译通道，本阶段不接后端
        }

        @Override
        public void onError(String message) {
            Log.e(TAG, "对话错误: " + message);
            uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                    "⚠️ " + message, System.currentTimeMillis()));
        }
    };

    /**
     * 整段 PCM → WAV → 后端 upload + query，SSE 逐帧渲染，done 回推眼镜。
     * 全程在后台线程（OkHttp 阻塞调用），UI 操作切主线程。
     */
    private void sendToBackend(byte[] pcm) {
        new Thread(() -> {
            File wav = writePcmToWav(pcm);
            if (wav == null) {
                uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                        "⚠️ 音频封装失败", System.currentTimeMillis()));
                return;
            }

            String audioId = apiClient.uploadAudio(wav);
            if (audioId == null) {
                uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                        "⚠️ 上传失败，检查后端与 adb reverse", System.currentTimeMillis()));
                return;
            }

            // 先加"我说的话"气泡（onDone 时填 ASR 结果），再加 AI 回复气泡
            replyText.setLength(0);
            requireActivitySafe(() -> {
                DialogueMessage userMsg = new DialogueMessage(DialogueMessage.Type.VOICE,
                        "🗣️ 你说：（识别中…）", System.currentTimeMillis());
                messages.add(userMsg);
                userBubbleIndex = messages.size() - 1;
                messageAdapter.notifyItemInserted(userBubbleIndex);

                DialogueMessage bubble = new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                        "…", System.currentTimeMillis());
                messages.add(bubble);
                replyBubbleIndex = messages.size() - 1;
                messageAdapter.notifyItemInserted(replyBubbleIndex);
                recyclerMessages.smoothScrollToPosition(replyBubbleIndex);
            });

            apiClient.queryVoice(audioId, new GuideApiClient.QueryCallback() {
                @Override
                public void onTextDelta(String delta) {
                    replyText.append(delta);
                    uiUpdateMessage(replyBubbleIndex, replyText.toString());
                }

                @Override
                public void onAudioChunk(int sequence, String url, int durationMs) {
                    // 流式 TTS 播放留待下一轮（拿 url 下载 mp3 串播）。本阶段先打通文字。
                    Log.d(TAG, "audio_chunk #" + sequence + " " + url);
                }

                @Override
                public void onDone(String transcribedText, String fullText, String aigcLabel) {
                    Log.d(TAG, "done transcribed=" + transcribedText + " full=" + fullText);
                    // 回填 ASR 识别结果到"我说的话"气泡
                    String asr = (transcribedText != null && !transcribedText.isEmpty())
                            ? transcribedText : "（未识别到语音）";
                    uiUpdateMessage(userBubbleIndex, "🗣️ 你说：" + asr);
                    // 定稿 AI 回复气泡（以 full_text 为准，防止 delta 拼接有偏差）
                    final String reply = fullText != null && !fullText.isEmpty()
                            ? fullText : replyText.toString();
                    uiUpdateMessage(replyBubbleIndex, reply);
                    // 回推眼镜（显示 + TTS 朗读）
                    CRPBleConnection conn = BleService.getInstance().getConnection();
                    if (conn != null && aiDialogueManager != null && !reply.isEmpty()) {
                        aiDialogueManager.sendTextToGlasses(reply);
                    }
                }

                @Override
                public void onError(String message) {
                    uiUpdateMessage(replyBubbleIndex, "⚠️ " + message);
                }
            });
        }).start();
    }

    // ── UI 辅助（回调多来自后台线程） ──

    private void uiAddMessage(DialogueMessage msg) {
        requireActivitySafe(() -> {
            messages.add(msg);
            messageAdapter.notifyItemInserted(messages.size() - 1);
            recyclerMessages.smoothScrollToPosition(messages.size() - 1);
        });
    }

    private void uiUpdateMessage(int index, String newText) {
        requireActivitySafe(() -> {
            if (index < 0 || index >= messages.size()) return;
            messages.get(index).setText(newText);
            messageAdapter.notifyItemChanged(index);
        });
    }

    /** 安全地切到主线程执行（Fragment 已 detach 则跳过）。 */
    private void requireActivitySafe(Runnable r) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (!isAdded()) return;
            r.run();
        });
    }

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
                    "🟢 已连接眼镜。按眼镜左键 或 屏幕「按住说话」→ AI讲解", System.currentTimeMillis()));
        } else {
            // 无眼镜也可用：手机麦克风「按住说话」走同一套后端链路
            uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                    "🟡 未连接眼镜，可用手机麦克风：按住下方「🎤 按住说话」提问", System.currentTimeMillis()));
        }

        v.findViewById(R.id.btn_take_photo).setOnClickListener(vi -> {
            CRPBleConnection c = BleService.getInstance().getConnection();
            if (c == null) { Toast.makeText(getContext(), "拍照需连接眼镜", Toast.LENGTH_SHORT).show(); return; }
            c.takePhoto(TakePhoto.PhotoMode.ModeAIRecognition);
            uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                    "📷 拍照指令已发送", System.currentTimeMillis()));
        });

        // 「按住说话」：按下用手机麦克风录音，松开 → 走后端（与眼镜链路复用 sendToBackend）
        View btnPush = v.findViewById(R.id.btn_push_text);
        btnPush.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startMicRecording();
                    view.setPressed(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    stopMicRecordingAndSend();
                    view.performClick();
                    return true;
            }
            return false;
        });
    }

    /** 按下：申请麦克风权限并开始录音。 */
    private void startMicRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        if (micRecorder.isRecording()) return;
        boolean ok = micRecorder.start();
        if (!ok) {
            Toast.makeText(getContext(), "麦克风启动失败", Toast.LENGTH_SHORT).show();
            return;
        }
        uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                "🎤 录音中…（松开发送）", System.currentTimeMillis()));
    }

    /** 松开：停止录音，整段 PCM → 后端（复用眼镜同款链路）。 */
    private void stopMicRecordingAndSend() {
        if (!micRecorder.isRecording()) return;
        byte[] pcm = micRecorder.stop();
        if (pcm.length < MicRecorder.SAMPLE_RATE) { // 少于 ~0.5s 视为误触
            uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                    "⚠️ 录音太短，请按住多说一会儿", System.currentTimeMillis()));
            return;
        }
        sendToBackend(pcm);
    }

    /** 整段 PCM(16k/mono/16bit) 封成 WAV 文件。 */
    private File writePcmToWav(byte[] pcm) {
        try {
            File dir = new File(requireContext().getExternalFilesDir("audio"), "query");
            if (!dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File wav = new File(dir, "q_" + ts + ".wav");
            try (FileOutputStream fos = new FileOutputStream(wav)) {
                writeWavHeader(fos, pcm.length, SAMPLE_RATE, 1, 16);
                fos.write(pcm);
            }
            return wav;
        } catch (Exception e) {
            Log.e(TAG, "writePcmToWav 异常: " + e.getMessage(), e);
            return null;
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
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0;
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
        micRecorder.release();
    }
}
