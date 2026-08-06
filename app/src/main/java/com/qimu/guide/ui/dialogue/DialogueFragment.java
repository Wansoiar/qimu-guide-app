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
import com.qimu.guide.service.AudioChunkPlayer;
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
    // 流式 TTS 串播器（audio_chunk 逐句播放；A2DP 连眼镜时从眼镜出声）
    private final AudioChunkPlayer ttsPlayer = new AudioChunkPlayer();

    /**
     * TTS 声源策略（架构兼容点）：
     *  PHONE_ONLY   —— 无眼镜时手机播后端豆包 TTS；眼镜连着时由眼镜自带 TTS 朗读（当前默认，避免两个TTS重叠）。
     *  BACKEND_ALWAYS —— 眼镜也用后端豆包 TTS（音质更好）。启用前提：眼镜端不再自动朗读
     *                    （摸清 sendAiReplyMode 语义 / 眼镜只显示文字后），否则会与眼镜自带TTS重叠。
     * 后续切豆包只需把此常量改成 BACKEND_ALWAYS，并在回推眼镜处停用自带朗读。
     */
    private enum TtsOutput { PHONE_ONLY, BACKEND_ALWAYS }
    private static final TtsOutput TTS_OUTPUT = TtsOutput.BACKEND_ALWAYS;

    /** 本轮是否由后端豆包 TTS 出声（决定要不要播 audio_chunk）。 */
    private volatile boolean playBackendTtsThisTurn = false;
    private final androidx.activity.result.ActivityResultLauncher<String> micPermLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (!granted) Toast.makeText(getContext(), "需要麦克风权限", Toast.LENGTH_SHORT).show();
                    });

    // 调试入口：从相册/文件选一张图，走 photo query（无眼镜时验证图片识物链路）
    private final androidx.activity.result.ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri == null) return;
                        File img = copyUriToTempFile(uri);
                        if (img == null) {
                            Toast.makeText(getContext(), "读取图片失败", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // 先显示缩略图气泡（与眼镜拍照回调一致），再走上传+query
                        DialogueMessage msg = new DialogueMessage(DialogueMessage.Type.PHOTO,
                                "🖼️ 选图测试", System.currentTimeMillis());
                        msg.setImageFile(img);
                        uiAddMessage(msg);
                        sendPhotoToBackend(img);
                    });

    /**
     * 流式上行开关：true = 手机「按住说话」走上行 WS 流式 ASR（边说边出字 + VAD 判停）；
     * false = 走原整段路径（录完 → WAV → upload → query）。
     * 眼镜端一期先不走流式（onDialogueStop 仍整段），待明天真机验证流式采集稳定后再切。
     */
    private static final boolean STREAM_UPLOAD = true;

    /**
     * 眼镜端流式开关：true = 眼镜 PCM 回调（onDialogueAudioChange）边收边推上行 WS；
     * false = 眼镜走原整段路径（onDialogueStop 整段发）。
     * ⚠️ 眼镜不在身边，代码先写好默认关；明天真机验证眼镜 PCM 回调节奏与 WS 推流稳定后再置 true。
     */
    private static final boolean STREAM_UPLOAD_GLASSES = false;

    // 流式会话句柄（按住说话/眼镜说话期间持有；松手/判停后由 done/error 自然失效）
    private volatile GuideApiClient.StreamSession streamSession;

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
            if (STREAM_UPLOAD_GLASSES) {
                // 眼镜流式：开对话即建上行 WS 会话 + 铺气泡，PCM 随 onDialogueAudioChange 推入
                ttsPlayer.reset();
                boolean glassesConnected = BleService.getInstance().getConnection() != null;
                playBackendTtsThisTurn = (TTS_OUTPUT == TtsOutput.BACKEND_ALWAYS) || !glassesConnected;
                setupTurnBubbles("🗣️ 你说：（聆听中…）", DialogueMessage.Type.VOICE);
                streamSession = apiClient.queryVoiceStream(newRenderCallback(/*fillUserBubbleWithAsr=*/true));
                return;
            }
            // "聆听中"状态气泡（整段路径）
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
            if (STREAM_UPLOAD_GLASSES) {
                // 眼镜流式：每块 PCM 直推上行 WS（眼镜 SDK 已是 16k/mono/16bit）
                GuideApiClient.StreamSession s = streamSession;
                if (s != null) s.sendPcm(audioData, audioData.length);
                totalAudioBytes += audioData.length;
                return;
            }
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
            // 图片识物链路：拍到的图 → 上传拿 image_id → query(photo) → 讲解词 + TTS。
            sendPhotoToBackend(imageFile);
        }

        @Override
        public void onDialogueStop(boolean isTimeout) {
            Log.d(TAG, "AI对话结束, isTimeout=" + isTimeout);
            if (STREAM_UPLOAD_GLASSES) {
                // 眼镜流式：告诉服务端上行结束（VAD 可能已提前判停）；下行帧继续到 done
                GuideApiClient.StreamSession s = streamSession;
                if (s != null) s.finish();
                totalAudioBytes = 0;
                return;
            }
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

            // 新一轮：重置 TTS 串播器（清掉上一轮残留）
            ttsPlayer.reset();
            // 决定本轮 TTS 声源：BACKEND_ALWAYS 恒用豆包；PHONE_ONLY 仅在无眼镜时用豆包（眼镜连着由自带TTS念）
            boolean glassesConnected = BleService.getInstance().getConnection() != null;
            playBackendTtsThisTurn = (TTS_OUTPUT == TtsOutput.BACKEND_ALWAYS) || !glassesConnected;

            // 语音轮：加"我说的话"气泡（onDone 填 ASR）+ AI 回复气泡，再走后端 SSE
            setupTurnBubbles("🗣️ 你说：（识别中…）", DialogueMessage.Type.VOICE);
            apiClient.queryVoice(audioId, newRenderCallback(/*fillUserBubbleWithAsr=*/true));
        }).start();
    }

    /**
     * 眼镜拍照 → 上传图片拿 image_id → query(photo) → 讲解词 + TTS。
     * 与 sendToBackend(pcm) 并列，共用 setupTurnBubbles / newRenderCallback 渲染逻辑。
     * 全程后台线程。
     */
    private void sendPhotoToBackend(File imageFile) {
        new Thread(() -> {
            String imageId = apiClient.uploadImage(imageFile);
            if (imageId == null) {
                uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                        "⚠️ 图片上传失败，检查后端与 adb reverse", System.currentTimeMillis()));
                return;
            }

            ttsPlayer.reset();
            boolean glassesConnected = BleService.getInstance().getConnection() != null;
            playBackendTtsThisTurn = (TTS_OUTPUT == TtsOutput.BACKEND_ALWAYS) || !glassesConnected;

            // 拍照轮：加"我拍了张照片"气泡（无 ASR，不回填）+ AI 回复气泡
            setupTurnBubbles("📷 我拍了张照片，请讲解", DialogueMessage.Type.PHOTO);
            apiClient.queryPhoto(imageId, newRenderCallback(/*fillUserBubbleWithAsr=*/false));
        }).start();
    }

    /**
     * 新一轮问答：重置 replyText，插入"用户气泡 + AI 回复气泡"，滚到底。
     * userBubbleText 为本轮用户侧提示文案；AI 回复气泡先占位"…"。
     */
    private void setupTurnBubbles(String userBubbleText, DialogueMessage.Type userType) {
        replyText.setLength(0);
        requireActivitySafe(() -> {
            DialogueMessage userMsg = new DialogueMessage(userType,
                    userBubbleText, System.currentTimeMillis());
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
    }

    /**
     * 构造 voice / photo 共用的 SSE 渲染回调：text_delta 上屏、audio_chunk 串播、done 定稿+回推眼镜。
     * fillUserBubbleWithAsr=true 时（语音轮）用 done 的 ASR 结果回填用户气泡；photo 轮为 false（无 ASR）。
     */
    private GuideApiClient.QueryCallback newRenderCallback(boolean fillUserBubbleWithAsr) {
        return new GuideApiClient.QueryCallback() {
            @Override
            public void onAsrPartial(String text, boolean isFinal) {
                // 流式 ASR 中间结果：实时刷新"你说：…"气泡（累积文本，可覆盖）
                if (fillUserBubbleWithAsr && text != null && !text.isEmpty()) {
                    uiUpdateMessage(userBubbleIndex, "🗣️ 你说：" + text);
                }
            }

            @Override
            public void onTextDelta(String delta) {
                replyText.append(delta);
                uiUpdateMessage(replyBubbleIndex, replyText.toString());
            }

            @Override
            public void onAudioChunk(int sequence, String url, int durationMs) {
                Log.d(TAG, "audio_chunk #" + sequence + " " + url);
                // 仅在本轮用后端豆包 TTS 时串播（眼镜连着且策略=PHONE_ONLY 则跳过，避免与眼镜自带TTS重叠）
                if (playBackendTtsThisTurn) {
                    ttsPlayer.enqueue(url);
                }
            }

            @Override
            public void onDone(String transcribedText, String fullText, String aigcLabel) {
                Log.d(TAG, "done transcribed=" + transcribedText + " full=" + fullText);
                if (fillUserBubbleWithAsr) {
                    // 语音轮：回填 ASR 识别结果到"我说的话"气泡
                    String asr = (transcribedText != null && !transcribedText.isEmpty())
                            ? transcribedText : "（未识别到语音）";
                    uiUpdateMessage(userBubbleIndex, "🗣️ 你说：" + asr);
                }
                // 定稿 AI 回复气泡（以 full_text 为准，防止 delta 拼接有偏差）
                final String reply = fullText != null && !fullText.isEmpty()
                        ? fullText : replyText.toString();
                uiUpdateMessage(replyBubbleIndex, reply);
                // 回推眼镜：眼镜端会显示文字 + 自带 TTS 朗读。
                // 注意声源耦合：PHONE_ONLY 下眼镜连着时靠这里的自带TTS出声（不播audio_chunk）；
                // 若将来切到 BACKEND_ALWAYS，需让眼镜只显示不朗读，否则与豆包TTS重叠。
                CRPBleConnection conn = BleService.getInstance().getConnection();
                if (conn != null && aiDialogueManager != null && !reply.isEmpty()) {
                    aiDialogueManager.sendTextToGlasses(reply);
                }
            }

            @Override
            public void onError(String message) {
                uiUpdateMessage(replyBubbleIndex, "⚠️ " + message);
            }
        };
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

        setupVenueSpinner(v);

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

        // 调试：从相册选图走 photo query（眼镜不在时验证图片识物）
        v.findViewById(R.id.btn_pick_image).setOnClickListener(vi -> pickImageLauncher.launch("image/*"));

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

    /** 按下：申请麦克风权限并开始录音（STREAM_UPLOAD 开则走流式上行 WS）。 */
    private void startMicRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        if (micRecorder.isRecording()) return;

        if (STREAM_UPLOAD) {
            startMicStreaming();
            return;
        }

        boolean ok = micRecorder.start();
        if (!ok) {
            Toast.makeText(getContext(), "麦克风启动失败", Toast.LENGTH_SHORT).show();
            return;
        }
        uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                "🎤 录音中…（松开发送）", System.currentTimeMillis()));
    }

    /** 松开：停止录音 → 后端。STREAM_UPLOAD 开则走流式结束（finish 交给 VAD/服务端收尾）。 */
    private void stopMicRecordingAndSend() {
        if (!micRecorder.isRecording()) return;

        if (STREAM_UPLOAD) {
            stopMicStreaming();
            return;
        }

        byte[] pcm = micRecorder.stop();
        if (pcm.length < MicRecorder.SAMPLE_RATE) { // 少于 ~0.5s 视为误触
            uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                    "⚠️ 录音太短，请按住多说一会儿", System.currentTimeMillis()));
            return;
        }
        sendToBackend(pcm);
    }

    /**
     * 流式采集开始：先建上行 WS + 铺好气泡，再开麦克风并把每块 PCM 通过监听器推到 WS。
     * asr_partial 实时刷"你说：…"，text_delta/audio_chunk/done 复用 newRenderCallback。
     */
    private void startMicStreaming() {
        // 新一轮：重置 TTS 串播器 + 决定声源
        ttsPlayer.reset();
        boolean glassesConnected = BleService.getInstance().getConnection() != null;
        playBackendTtsThisTurn = (TTS_OUTPUT == TtsOutput.BACKEND_ALWAYS) || !glassesConnected;

        // 铺气泡（用户气泡随 asr_partial 刷新；AI 回复气泡随 text_delta 追加）
        setupTurnBubbles("🗣️ 你说：（聆听中…）", DialogueMessage.Type.VOICE);

        // 建上行 WS 会话
        streamSession = apiClient.queryVoiceStream(newRenderCallback(/*fillUserBubbleWithAsr=*/true));

        // 麦克风每块 PCM → 推 WS
        micRecorder.setPcmListener((pcm, length) -> {
            GuideApiClient.StreamSession s = streamSession;
            if (s != null) s.sendPcm(pcm, length);
        });
        boolean ok = micRecorder.start();
        if (!ok) {
            Toast.makeText(getContext(), "麦克风启动失败", Toast.LENGTH_SHORT).show();
            if (streamSession != null) { streamSession.cancel(); streamSession = null; }
        }
    }

    /** 流式采集结束：停麦 + 发 finish（服务端 VAD 可能已提前判停，此处是兜底）。 */
    private void stopMicStreaming() {
        micRecorder.setPcmListener(null);
        micRecorder.stop();  // 停采集（整段 buffer 丢弃，流式不用）
        GuideApiClient.StreamSession s = streamSession;
        if (s != null) {
            s.finish();  // 告诉服务端上行结束；下行帧继续回调直到 done
            // 不在这里置空 streamSession：done/error 回调后自然失效，cancel 交给 onDestroyView
        }
    }

    /** 把相册选中的图片 Uri 拷到临时 .jpg 文件（供 uploadImage）。失败返 null。 */
    private File copyUriToTempFile(android.net.Uri uri) {
        try {
            File dir = new File(requireContext().getExternalFilesDir("images"), "query");
            if (!dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File out = new File(dir, "pick_" + ts + ".jpg");
            try (java.io.InputStream in = requireContext().getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(out)) {
                if (in == null) return null;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            return out;
        } catch (Exception e) {
            Log.e(TAG, "copyUriToTempFile 异常: " + e.getMessage(), e);
            return null;
        }
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

    /**
     * 调试用场馆切换：义净寺（语音/文字 demo，有全量文字）↔ 大雁塔（图片识物测试图所在馆）。
     * 选中即 SessionContext.setVenueId，后续 query 带上新 venue_id。
     */
    private void setupVenueSpinner(View v) {
        // 名称 与 venue_id 一一对应（与后端库对齐）
        final String[] names = {"义净寺（语音/文字）", "大雁塔（图片识物）"};
        final String[] ids = {
                "61f1f93d-fe42-49d0-b392-bcbf9cd1c13d",  // 义净寺
                "58cf0bef-90b9-4117-bd83-f59f4b05d9eb",  // 大雁塔
        };
        android.widget.Spinner spinner = v.findViewById(R.id.spinner_venue);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        // 初始选中项对齐 SessionContext 当前 venue（默认义净寺）
        String current = com.qimu.guide.net.SessionContext.get().venueId();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(current)) { spinner.setSelection(i); break; }
        }

        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                com.qimu.guide.net.SessionContext.get().setVenueId(ids[pos]);
                uiAddMessage(new DialogueMessage(DialogueMessage.Type.AI_REPLY,
                        "🏛️ 已切换到「" + names[pos] + "」", System.currentTimeMillis()));
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (aiDialogueManager != null) aiDialogueManager.release();
        if (streamSession != null) { streamSession.cancel(); streamSession = null; }
        micRecorder.release();
        ttsPlayer.release();
    }
}
