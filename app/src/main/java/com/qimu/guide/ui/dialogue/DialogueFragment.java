package com.qimu.guide.ui.dialogue;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.moyoung.glasses.conn.CRPBleConnection;
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener;
import com.moyoung.glasses.conn.protos.TakePhoto;
import com.qimu.guide.R;
import com.qimu.guide.model.DialogueMessage;
import com.qimu.guide.net.GuideApiClient;
import com.qimu.guide.net.TourSessionManager;
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
 * 手机“按住说话”和眼镜左键是两个独立入口，但任何时刻只允许一轮对话进行，
 * 避免两路音频共用气泡、SSE 文本或 TTS 播放状态而串线。
 */
public class DialogueFragment extends Fragment {

    private static final String TAG = "DialogueFragment";
    private static final int SAMPLE_RATE = 16000;
    private static final long MIN_LONG_PRESS_MS = 300L;
    private static final int MIN_AUDIO_BYTES = MicRecorder.SAMPLE_RATE; // 约 0.5 秒 PCM
    private static final long AUDIO_PROGRESS_INTERVAL_MS = 250L;

    private enum TurnSource { PHONE, GLASSES }

    private enum TurnPhase { PHONE_RECORDING, GLASSES_RECORDING, BACKEND }

    /** 每轮对话独享的音频、气泡和流式回复状态。 */
    private static final class TurnContext {
        final TurnSource source;
        TurnPhase phase;
        ByteArrayOutputStream pcmBuffer;
        int audioBytes;
        long lastProgressUpdateMs;
        volatile int listeningMessageIndex = -1;
        volatile int userBubbleIndex = -1;
        volatile int replyBubbleIndex = -1;
        final StringBuilder replyText = new StringBuilder();
        boolean backendScheduled;
        volatile boolean terminal;

        TurnContext(TurnSource source, TurnPhase phase) {
            this.source = source;
            this.phase = phase;
        }
    }

    private final Object turnLock = new Object();
    private volatile TurnContext activeTurn;
    private volatile boolean viewActive;

    private volatile AIDialogueManager aiDialogueManager;
    private volatile CRPBleConnection boundDialogueConnection;
    private GuideApiClient apiClient;
    private MicRecorder micRecorder;
    private AudioChunkPlayer ttsPlayer;

    private RecyclerView recyclerMessages;
    private MessageAdapter messageAdapter;
    private final List<DialogueMessage> messages = new ArrayList<>();

    private View talkButton;
    private long pressStartedAt;
    private boolean gestureStartedRecording;

    private final ActivityResultLauncher<String> microphonePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!viewActive || !isAdded()) return;
                Toast.makeText(requireContext(),
                        granted ? "麦克风权限已允许，请再次按住说话" : "需要麦克风权限才能按住说话",
                        Toast.LENGTH_SHORT).show();
            });

    private final BleService.BleListener bleListener = new BleService.BleListener() {
        @Override
        public void onConnectionStateChanged(int state) {
            if (!viewActive) return;
            if (state == CRPBleConnectionStateListener.STATE_CONNECTED) {
                CRPBleConnection connection = BleService.getInstance().getConnection();
                postUi(() -> bindDialogueConnection(connection, true));
            } else {
                postUi(() -> unbindDialogueConnection(true));
            }
        }

        @Override public void onBatteryUpdate(int level, boolean charging) { }
        @Override public void onFirmwareVersion(String version) { }
        @Override public void onMediaFileChanged(int photoCount, int videoCount, int audioCount) { }
        @Override public void onWifiStateChange(int state) { }
        @Override public void onWifiConnectionChanged(boolean connected) { }
        @Override public void onLog(String tag, String message) { }
        @Override public void onError(String message) { }
    };

    private final AIDialogueManager.DialogueCallback dialogueCallback =
            new AIDialogueManager.DialogueCallback() {
                @Override
                public void onDialogueStart() {
                    if (!viewActive) return;

                    TurnContext turn;
                    synchronized (turnLock) {
                        if (activeTurn != null) {
                            turn = null;
                        } else {
                            turn = new TurnContext(TurnSource.GLASSES, TurnPhase.GLASSES_RECORDING);
                            turn.pcmBuffer = new ByteArrayOutputStream();
                            activeTurn = turn;
                        }
                    }

                    if (turn == null) {
                        Log.d(TAG, "忽略眼镜左键：上一轮对话仍在进行");
                        showToast("上一轮对话仍在处理，请稍后再按眼镜左键");
                        return;
                    }

                    stopTtsForNewTurn();
                    Log.d(TAG, "眼镜左键对话开始");
                    postUiForTurn(turn, () -> turn.listeningMessageIndex = appendMessageDirect(
                            new DialogueMessage(
                                    DialogueMessage.Type.AI_REPLY,
                                    "正在通过眼镜聆听…",
                                    System.currentTimeMillis())));
                }

                @Override
                public void onDialogueAudioChange(byte[] audioData) {
                    if (!viewActive || audioData == null || audioData.length == 0) return;

                    TurnContext turn;
                    int byteCount;
                    boolean updateProgress;
                    synchronized (turnLock) {
                        turn = activeTurn;
                        if (turn == null || turn.terminal
                                || turn.phase != TurnPhase.GLASSES_RECORDING
                                || turn.pcmBuffer == null) {
                            return;
                        }
                        turn.pcmBuffer.write(audioData, 0, audioData.length);
                        turn.audioBytes += audioData.length;
                        byteCount = turn.audioBytes;
                        long now = SystemClock.elapsedRealtime();
                        updateProgress = now - turn.lastProgressUpdateMs >= AUDIO_PROGRESS_INTERVAL_MS;
                        if (updateProgress) turn.lastProgressUpdateMs = now;
                    }

                    if (updateProgress) {
                        int index = turn.listeningMessageIndex;
                        postUiForTurn(turn, () -> updateMessageDirect(index,
                                "正在通过眼镜聆听 · " + (byteCount / 1024) + " KB"));
                    }
                }

                @Override
                public void onDialogueImageChange(File imageFile) {
                    if (!viewActive || imageFile == null) return;
                    DialogueMessage message = new DialogueMessage(
                            DialogueMessage.Type.PHOTO,
                            "眼镜拍照",
                            System.currentTimeMillis());
                    message.setImageFile(imageFile);
                    addMessage(message);
                }

                @Override
                public void onDialogueStop(boolean isTimeout) {
                    TurnContext turn;
                    byte[] pcm;
                    int byteCount;
                    synchronized (turnLock) {
                        turn = activeTurn;
                        if (turn == null || turn.terminal
                                || turn.phase != TurnPhase.GLASSES_RECORDING) {
                            return;
                        }
                        pcm = turn.pcmBuffer == null
                                ? new byte[0] : turn.pcmBuffer.toByteArray();
                        byteCount = turn.audioBytes;
                        turn.pcmBuffer = null;
                        if (pcm.length > 0) turn.phase = TurnPhase.BACKEND;
                    }

                    Log.d(TAG, "眼镜左键对话结束, isTimeout=" + isTimeout
                            + ", bytes=" + byteCount);
                    int seconds = byteCount / (SAMPLE_RATE * 2);
                    int index = turn.listeningMessageIndex;
                    postUiForTurn(turn, () -> updateMessageDirect(index,
                            "眼镜录音结束 · " + seconds + " 秒 · "
                                    + (byteCount / 1024) + " KB"));

                    if (pcm.length == 0) {
                        if (completeTurn(turn)) {
                            postUi(() -> {
                                updateMessageDirect(index, "没有收到眼镜音频，请重试");
                                appendMessageDirect(new DialogueMessage(
                                        DialogueMessage.Type.AI_REPLY,
                                        "请再次按眼镜左键说话，或按住 App 下方按钮提问。",
                                        System.currentTimeMillis()));
                            });
                        }
                        return;
                    }
                    beginBackend(turn, pcm);
                }

                @Override
                public void onTranslationAudioChange(byte[] audioData) {
                    Log.d(TAG, "收到同声传译音频: "
                            + (audioData == null ? 0 : audioData.length) + " bytes");
                }

                @Override
                public void onError(String message) {
                    Log.e(TAG, "眼镜对话错误: " + message);
                    TurnContext interrupted = null;
                    synchronized (turnLock) {
                        TurnContext current = activeTurn;
                        if (current != null && !current.terminal
                                && current.phase == TurnPhase.GLASSES_RECORDING) {
                            current.terminal = true;
                            current.pcmBuffer = null;
                            activeTurn = null;
                            interrupted = current;
                        }
                    }
                    TurnContext finalInterrupted = interrupted;
                    postUi(() -> {
                        if (finalInterrupted != null) {
                            updateMessageDirect(finalInterrupted.listeningMessageIndex,
                                    "眼镜录音异常，已取消");
                        }
                        appendMessageDirect(new DialogueMessage(
                                DialogueMessage.Type.AI_REPLY,
                                "识别异常 · " + message,
                                System.currentTimeMillis()));
                    });
                }
            };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dialogue, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewActive = true;
        apiClient = new GuideApiClient();
        micRecorder = new MicRecorder();
        ttsPlayer = new AudioChunkPlayer();

        recyclerMessages = view.findViewById(R.id.recycler_messages);
        messageAdapter = new MessageAdapter(messages);
        recyclerMessages.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerMessages.setAdapter(messageAdapter);

        view.findViewById(R.id.btn_take_photo).setOnClickListener(clicked -> {
            BleService service = BleService.getInstance();
            CRPBleConnection connection = service.getConnection();
            if (!service.isConnected() || connection == null) {
                Toast.makeText(requireContext(), "拍照需要先连接眼镜", Toast.LENGTH_SHORT).show();
                return;
            }
            connection.takePhoto(TakePhoto.PhotoMode.ModeAIRecognition);
            addMessage(new DialogueMessage(
                    DialogueMessage.Type.AI_REPLY,
                    "拍照指令已发送，正在等待眼镜返回图片。",
                    System.currentTimeMillis()));
        });

        setupPushToTalk(view.findViewById(R.id.btn_push_text));

        BleService service = BleService.getInstance();
        service.addListener(bleListener);
        CRPBleConnection connection = service.getConnection();
        boolean connected = service.getConnectionState()
                == CRPBleConnectionStateListener.STATE_CONNECTED && connection != null;
        if (connected) {
            bindDialogueConnection(connection, messages.isEmpty());
        } else if (messages.isEmpty()) {
            appendMessageDirect(new DialogueMessage(
                    DialogueMessage.Type.AI_REPLY,
                    "未连接眼镜，也可以按住下方按钮使用手机麦克风提问。",
                    System.currentTimeMillis()));
        }

        if (TourSessionManager.get().consumeFirstTutorial()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("开始本次导览")
                    .setMessage("按眼镜左键可以开始语音提问；也可以在手机上按住“说话”按钮。想识别眼前展品时，可点“拍照提问”。")
                    .setPositiveButton("我知道了", null)
                    .show();
        }
    }

    private void setupPushToTalk(View button) {
        talkButton = button;
        setTalkButtonText("按住说话");
        button.setOnClickListener(clicked -> {
            // 触摸监听器负责短按提示；保留 performClick 的无障碍语义。
        });
        button.setOnTouchListener((pressedView, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressStartedAt = SystemClock.elapsedRealtime();
                    gestureStartedRecording = startMicRecording();
                    if (gestureStartedRecording) {
                        pressedView.setPressed(true);
                        setTalkButtonText("松开结束");
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    long heldMs = SystemClock.elapsedRealtime() - pressStartedAt;
                    pressedView.setPressed(false);
                    setTalkButtonText("按住说话");
                    if (gestureStartedRecording) stopMicRecording(true, heldMs);
                    gestureStartedRecording = false;
                    pressedView.performClick();
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    pressedView.setPressed(false);
                    setTalkButtonText("按住说话");
                    if (gestureStartedRecording) stopMicRecording(false, 0L);
                    gestureStartedRecording = false;
                    return true;

                default:
                    return true;
            }
        });
    }

    /** ACTION_DOWN：独占本轮并立即开始采集手机麦克风。 */
    private boolean startMicRecording() {
        if (!viewActive) return false;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return false;
        }

        MicRecorder recorder = micRecorder;
        if (recorder == null) return false;

        TurnContext turn;
        synchronized (turnLock) {
            if (activeTurn != null) {
                turn = null;
            } else {
                turn = new TurnContext(TurnSource.PHONE, TurnPhase.PHONE_RECORDING);
                activeTurn = turn;
            }
        }
        if (turn == null) {
            showToast("上一轮对话仍在处理，请稍后再说");
            return false;
        }

        stopTtsForNewTurn();
        if (!recorder.start()) {
            completeTurn(turn);
            showToast("麦克风启动失败");
            return false;
        }
        turn.listeningMessageIndex = appendMessageDirect(new DialogueMessage(
                DialogueMessage.Type.AI_REPLY,
                "正在使用手机麦克风录音…松开发送",
                System.currentTimeMillis()));
        return true;
    }

    /** ACTION_UP 才发送；ACTION_CANCEL 和过短录音均停止并丢弃。 */
    private void stopMicRecording(boolean shouldSend, long heldMs) {
        TurnContext turn;
        synchronized (turnLock) {
            turn = activeTurn;
            if (turn == null || turn.terminal || turn.phase != TurnPhase.PHONE_RECORDING) {
                return;
            }
        }

        MicRecorder recorder = micRecorder;
        byte[] pcm = recorder == null ? new byte[0] : recorder.stop();
        int index = turn.listeningMessageIndex;

        if (!shouldSend) {
            if (completeTurn(turn)) {
                postUi(() -> updateMessageDirect(index, "手机录音已取消"));
            }
            return;
        }

        if (heldMs < MIN_LONG_PRESS_MS || pcm.length < MIN_AUDIO_BYTES) {
            if (completeTurn(turn)) {
                postUi(() -> updateMessageDirect(index, "录音太短，已取消"));
            }
            showToast("请按住说话，松开发送");
            return;
        }

        postUiForTurn(turn, () -> updateMessageDirect(index, "手机录音结束，正在识别…"));
        beginBackend(turn, pcm);
    }

    /** 手机和眼镜共用：PCM -> WAV -> 上传 -> SSE 文本/TTS。 */
    private void beginBackend(TurnContext turn, byte[] pcm) {
        synchronized (turnLock) {
            if (activeTurn != turn || turn.terminal || turn.backendScheduled) return;
            turn.phase = TurnPhase.BACKEND;
            turn.backendScheduled = true;
        }

        postUiForTurn(turn, () -> {
            turn.userBubbleIndex = appendMessageDirect(new DialogueMessage(
                    DialogueMessage.Type.VOICE,
                    "正在识别…",
                    System.currentTimeMillis()));
            turn.replyBubbleIndex = appendMessageDirect(new DialogueMessage(
                    DialogueMessage.Type.AI_REPLY,
                    "正在思考…",
                    System.currentTimeMillis()));

            File audioRoot = requireContext().getExternalFilesDir("audio");
            GuideApiClient client = apiClient;
            AudioChunkPlayer player = ttsPlayer;
            if (audioRoot == null || client == null || player == null) {
                failBackend(turn, "音频缓存不可用，请重试");
                return;
            }
            player.reset();
            new Thread(() -> runBackend(turn, pcm, audioRoot, client, player),
                    "dialogue-backend").start();
        });
    }

    private void runBackend(TurnContext turn,
                            byte[] pcm,
                            File audioRoot,
                            GuideApiClient client,
                            AudioChunkPlayer player) {
        if (!isTurnActive(turn)) return;
        File wav = writePcmToWav(pcm, audioRoot);
        if (wav == null) {
            failBackend(turn, "音频封装失败，请重试");
            return;
        }

        String audioId = client.uploadAudio(wav);
        if (!isTurnActive(turn)) return;
        if (audioId == null) {
            failBackend(turn, "语音上传失败，请检查导览服务连接");
            return;
        }

        client.queryVoice(audioId, new GuideApiClient.QueryCallback() {
            @Override
            public void onTextDelta(String delta) {
                if (!isTurnActive(turn) || delta == null || delta.isEmpty()) return;
                String text;
                synchronized (turn.replyText) {
                    turn.replyText.append(delta);
                    text = turn.replyText.toString();
                }
                postUiForTurn(turn, () -> updateMessageDirect(turn.replyBubbleIndex, text));
            }

            @Override
            public void onAudioChunk(int sequence, String url, int durationMs) {
                if (!isTurnActive(turn)) return;
                Log.d(TAG, "TTS audio_chunk #" + sequence + " duration=" + durationMs);
                player.enqueue(url);
            }

            @Override
            public void onDone(String transcribedText, String fullText, String aigcLabel) {
                if (!isTurnActive(turn)) return;
                maybeTriggerPhotoFromTranscription(transcribedText);
                String recognized = transcribedText == null || transcribedText.isEmpty()
                        ? "（未识别到语音）" : transcribedText;
                String streamedReply;
                synchronized (turn.replyText) {
                    streamedReply = turn.replyText.toString();
                }
                String reply = fullText == null || fullText.isEmpty()
                        ? streamedReply : fullText;
                if (reply.isEmpty()) reply = "（暂无回复）";

                sendReplyToConnectedGlasses(reply);
                if (!completeTurn(turn)) return;
                String finalReply = reply;
                postUi(() -> {
                    updateMessageDirect(turn.userBubbleIndex, recognized);
                    updateMessageDirect(turn.replyBubbleIndex, finalReply);
                });
            }

            @Override
            public void onError(String message) {
                failBackend(turn, "讲解服务异常 · " + message);
            }
        });
    }

    private void maybeTriggerPhotoFromTranscription(@Nullable String transcribedText) {
        if (transcribedText == null) return;
        String normalized = transcribedText.replace(" ", "");
        String[] photoIntents = {
                "帮我看看眼前", "看看眼前", "我眼前", "我面前",
                "这是什么", "这是啥", "这个是什么", "识别一下"
        };
        boolean matched = false;
        for (String keyword : photoIntents) {
            if (normalized.contains(keyword)) {
                matched = true;
                break;
            }
        }
        if (!matched) return;

        BleService service = BleService.getInstance();
        CRPBleConnection connection = service.getConnection();
        if (!service.isConnected() || connection == null) return;
        try {
            connection.takePhoto(TakePhoto.PhotoMode.ModeAIRecognition);
            postUi(() -> appendMessageDirect(new DialogueMessage(
                    DialogueMessage.Type.AI_REPLY,
                    "已根据你的问题触发眼镜拍照识别。",
                    System.currentTimeMillis())));
        } catch (RuntimeException e) {
            Log.e(TAG, "关键词触发拍照失败", e);
        }
    }

    private void sendReplyToConnectedGlasses(String reply) {
        BleService service = BleService.getInstance();
        CRPBleConnection connection = service.getConnection();
        AIDialogueManager manager = aiDialogueManager;
        if (viewActive && service.isConnected() && connection != null
                && connection == boundDialogueConnection && manager != null
                && reply != null && !reply.isEmpty()) {
            try {
                manager.sendTextToGlasses(reply);
            } catch (Exception e) {
                Log.e(TAG, "回复文字发送到眼镜失败", e);
            }
        }
    }

    private void failBackend(TurnContext turn, String message) {
        if (!completeTurn(turn)) return;
        postUi(() -> updateMessageDirect(turn.replyBubbleIndex, message));
    }

    private boolean isTurnActive(TurnContext turn) {
        synchronized (turnLock) {
            return viewActive && activeTurn == turn && !turn.terminal;
        }
    }

    private boolean completeTurn(TurnContext turn) {
        synchronized (turnLock) {
            if (activeTurn != turn || turn.terminal) return false;
            turn.terminal = true;
            turn.pcmBuffer = null;
            activeTurn = null;
            return true;
        }
    }

    /** BLE 重连后必须在新的 SDK connection 上重新注册左键对话监听。 */
    private void bindDialogueConnection(@Nullable CRPBleConnection connection, boolean announce) {
        if (!viewActive || connection == null) return;
        if (boundDialogueConnection == connection && aiDialogueManager != null) return;

        releaseDialogueManager();
        AIDialogueManager manager = new AIDialogueManager(connection);
        try {
            manager.setCallback(dialogueCallback);
            manager.setupAiDialogueListener();
            manager.setupTranslationListener();
            boundDialogueConnection = connection;
            aiDialogueManager = manager;
            if (announce) {
                appendMessageDirect(new DialogueMessage(
                        DialogueMessage.Type.AI_REPLY,
                        "眼镜已就绪：可按眼镜左键，也可按住下方按钮说话。",
                        System.currentTimeMillis()));
            }
        } catch (Exception e) {
            Log.e(TAG, "绑定眼镜对话监听失败", e);
            manager.release();
            boundDialogueConnection = null;
            aiDialogueManager = null;
            appendMessageDirect(new DialogueMessage(
                    DialogueMessage.Type.AI_REPLY,
                    "眼镜对话通道初始化失败，仍可使用手机按住说话。",
                    System.currentTimeMillis()));
        }
    }

    private void unbindDialogueConnection(boolean announceDisconnect) {
        boolean hadBinding = boundDialogueConnection != null || aiDialogueManager != null;
        releaseDialogueManager();

        TurnContext interrupted = null;
        synchronized (turnLock) {
            TurnContext current = activeTurn;
            if (current != null && !current.terminal
                    && current.phase == TurnPhase.GLASSES_RECORDING) {
                current.terminal = true;
                current.pcmBuffer = null;
                activeTurn = null;
                interrupted = current;
            }
        }
        if (interrupted != null) {
            updateMessageDirect(interrupted.listeningMessageIndex,
                    "眼镜已断开，本次录音已取消");
        }
        if (announceDisconnect && hadBinding) {
            appendMessageDirect(new DialogueMessage(
                    DialogueMessage.Type.AI_REPLY,
                    "眼镜连接已断开；手机按住说话仍可继续使用。",
                    System.currentTimeMillis()));
        }
    }

    private void releaseDialogueManager() {
        AIDialogueManager manager = aiDialogueManager;
        aiDialogueManager = null;
        boundDialogueConnection = null;
        if (manager != null) {
            manager.setCallback(null);
            manager.release();
        }
    }

    private File writePcmToWav(byte[] pcm, File audioRoot) {
        try {
            File directory = new File(audioRoot, "query");
            if (!directory.exists() && !directory.mkdirs()) return null;

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
                    .format(new Date());
            File wav = new File(directory, "q_" + timestamp + ".wav");
            try (FileOutputStream output = new FileOutputStream(wav)) {
                writeWavHeader(output, pcm.length, SAMPLE_RATE, 1, 16);
                output.write(pcm);
            }
            return wav;
        } catch (Exception e) {
            Log.e(TAG, "PCM 封装 WAV 失败", e);
            return null;
        }
    }

    private void writeWavHeader(FileOutputStream output,
                                int dataSize,
                                int sampleRate,
                                int channels,
                                int bitsPerSample) throws Exception {
        byte[] header = new byte[44];
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int totalSize = 36 + dataSize;

        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalSize & 0xff);
        header[5] = (byte) ((totalSize >> 8) & 0xff);
        header[6] = (byte) ((totalSize >> 16) & 0xff);
        header[7] = (byte) ((totalSize >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16;
        header[20] = 1;
        header[22] = (byte) channels;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) blockAlign;
        header[34] = (byte) bitsPerSample;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (dataSize & 0xff);
        header[41] = (byte) ((dataSize >> 8) & 0xff);
        header[42] = (byte) ((dataSize >> 16) & 0xff);
        header[43] = (byte) ((dataSize >> 24) & 0xff);
        output.write(header);
    }

    private int appendMessageDirect(DialogueMessage message) {
        if (!viewActive || messageAdapter == null || recyclerMessages == null) return -1;
        messages.add(message);
        int index = messages.size() - 1;
        messageAdapter.notifyItemInserted(index);
        recyclerMessages.smoothScrollToPosition(index);
        return index;
    }

    private void addMessage(DialogueMessage message) {
        postUi(() -> appendMessageDirect(message));
    }

    private void updateMessageDirect(int index, String text) {
        if (!viewActive || messageAdapter == null || index < 0 || index >= messages.size()) return;
        messages.get(index).setText(text);
        messageAdapter.notifyItemChanged(index);
    }

    private void postUiForTurn(TurnContext turn, Runnable action) {
        postUi(() -> {
            if (isTurnActive(turn)) action.run();
        });
    }

    private void postUi(Runnable action) {
        if (!viewActive) return;
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (!viewActive || getView() == null) return;
            action.run();
        });
    }

    private void showToast(String text) {
        postUi(() -> Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show());
    }

    private void setTalkButtonText(String text) {
        View button = talkButton;
        if (button instanceof TextView) ((TextView) button).setText(text);
    }

    /** 用户开始新一轮收音时立即停止上一轮尚未播完的 TTS，避免被麦克风再次录入。 */
    private void stopTtsForNewTurn() {
        AudioChunkPlayer player = ttsPlayer;
        if (player != null) player.reset();
    }

    @Override
    public void onDestroyView() {
        viewActive = false;
        BleService.getInstance().removeListener(bleListener);

        GuideApiClient client = apiClient;
        apiClient = null;
        if (client != null) client.cancelAll();

        synchronized (turnLock) {
            if (activeTurn != null) {
                activeTurn.terminal = true;
                activeTurn.pcmBuffer = null;
                activeTurn = null;
            }
        }
        gestureStartedRecording = false;

        MicRecorder recorder = micRecorder;
        micRecorder = null;
        if (recorder != null) {
            if (recorder.isRecording()) recorder.stop();
            recorder.release();
        }

        AudioChunkPlayer player = ttsPlayer;
        ttsPlayer = null;
        if (player != null) player.release();

        releaseDialogueManager();
        talkButton = null;
        recyclerMessages = null;
        messageAdapter = null;
        super.onDestroyView();
    }
}
