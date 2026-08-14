package com.qimu.guide.ui.dialogue;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.moyoung.glasses.conn.CRPBleConnection;
import com.moyoung.glasses.conn.listener.CRPAiDialogueListener;
import com.moyoung.glasses.conn.protos.TakePhoto;
import com.qimu.guide.R;
import com.qimu.guide.model.DialogueMessage;
import com.qimu.guide.net.TourSessionManager;
import com.qimu.guide.service.BleService;
import com.qimu.guide.service.RealtimeGuideManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 智能导览对话页。
 *
 * RTC 房间属于整次游览：进入游览后常驻，页面销毁或暂停收音都不会退房。
 * App 按钮只控制眼镜 Translation PCM 是否上行；结束游览才停止 Agent 并销毁 RTC。
 */
public class DialogueFragment extends Fragment {

    private static final String TAG = "DialogueFragment";
    private static final long VISION_CAPTURE_TIMEOUT_MS = 10_000L;
    private static final long HARDWARE_RELEASE_DELAY_MS = 300L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<DialogueMessage> messages = new ArrayList<>();
    private final RealtimeGuideManager guideManager = RealtimeGuideManager.get();

    private volatile boolean viewActive;
    private RecyclerView recyclerMessages;
    private MessageAdapter messageAdapter;
    private MaterialButton dialogueButton;
    private MaterialButton photoButton;
    private TextView rtcStatusText;
    private TextView rtcSessionBody;
    private View rtcCardStatusDot;
    private View rtcControlStatusDot;

    // 对齐 feat/volc-main-dialogue：中间态不上屏；同一说话人的连续 final 分句
    // 合并进同一个气泡，只有说话人切换时才新建气泡。
    private final Set<String> renderedFinalSubtitleKeys = new HashSet<>();
    private int volcBubbleIndex = -1;
    private boolean volcBubbleFromSelf;
    private final StringBuilder volcBubbleText = new StringBuilder();

    private int visionGeneration;
    private boolean visionBusy;
    private boolean visionImageAccepted;
    private boolean visionHardwareStageActive;
    private boolean resumeAfterVisionFailure;
    private String visionQuestion;
    private String visionCommandId;
    private CRPBleConnection visionConnection;
    private Runnable visionTimeout;

    private final RealtimeGuideManager.Listener realtimeListener =
            new RealtimeGuideManager.Listener() {
                @Override
                public void onStateChanged(RealtimeGuideManager.State state, String message) {
                    postUi(() -> {
                        if (!isVisionReadyState(state)) {
                            // Manager 负责取消自己的 reservation/HTTP；这里只立即释放
                            // Fragment 的延迟拍照、BLE listener 和硬件阶段。
                            cancelVisionCapture(false);
                        }
                        renderState(state, message);
                    });
                }

                @Override
                public void onSubtitle(boolean fromSelf, String text,
                                       boolean definite, long sequence) {
                    postUi(() -> upsertSubtitle(fromSelf, text, definite, sequence));
                }

                @Override
                public boolean onVisionCaptureRequested(String commandId, String question) {
                    if (!viewActive || getActivity() == null || getView() == null) return false;
                    return requestVisionCapture(question, commandId, false);
                }

                @Override
                public void onVisionOperationChanged(boolean inProgress, String message) {
                    postUi(() -> {
                        if (inProgress) {
                            visionBusy = true;
                        } else if (visionConnection == null) {
                            visionBusy = false;
                        }
                        renderState(guideManager.getState(), inProgress
                                ? message : guideManager.getStateMessage());
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

        recyclerMessages = view.findViewById(R.id.recycler_messages);
        recyclerMessages.setLayoutManager(new LinearLayoutManager(requireContext()));
        messageAdapter = new MessageAdapter(messages);
        recyclerMessages.setAdapter(messageAdapter);

        dialogueButton = view.findViewById(R.id.btn_push_text);
        photoButton = view.findViewById(R.id.btn_take_photo);
        rtcStatusText = view.findViewById(R.id.tv_rtc_status);
        rtcSessionBody = view.findViewById(R.id.tv_rtc_session_body);
        rtcCardStatusDot = view.findViewById(R.id.rtc_card_status_dot);
        rtcControlStatusDot = view.findViewById(R.id.rtc_control_status_dot);

        dialogueButton.setOnClickListener(clicked -> handleDialogueButton());
        photoButton.setOnClickListener(clicked -> requestVisionCapture(null, null, true));

        // 先注册再取快照，避免视图重建时字幕恰好到达而漏掉一条。
        guideManager.addListener(realtimeListener);
        List<RealtimeGuideManager.TranscriptEntry> transcript =
                guideManager.getTranscriptSnapshot();
        if (messages.isEmpty() && transcript.isEmpty()) {
            appendMessageDirect(new DialogueMessage(
                    DialogueMessage.Type.AI_REPLY,
                    "AI 导览房间会在本次游览中保持在线。点击开始对话后，直接通过眼镜提问。",
                    System.currentTimeMillis()));
        }
        for (RealtimeGuideManager.TranscriptEntry entry : transcript) {
            upsertSubtitle(entry.fromSelf, entry.text, entry.definite, entry.sequence);
        }

        renderState(guideManager.getState(), guideManager.getStateMessage());

        if (TourSessionManager.get().consumeFirstTutorial()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("开始本次智能导览")
                    .setMessage("点击“开始对话”后直接说话，声音由眼镜持续采集。点击暂停只停止收音，AI 导览房间仍会保持在线；结束游览时才会关闭。")
                    .setPositiveButton("我知道了", null)
                    .show();
        }
    }

    private void handleDialogueButton() {
        switch (guideManager.getState()) {
            case READY:
            case PAUSED:
                guideManager.startGuidance();
                break;
            case LISTENING:
            case AUDIO_LINK_STARTING:
                guideManager.pauseGuidance();
                break;
            case ERROR:
                guideManager.retryCurrentTour();
                break;
            default:
                break;
        }
    }

    private void renderState(RealtimeGuideManager.State state, String message) {
        if (!viewActive || dialogueButton == null) return;

        String safeMessage = message == null || message.trim().isEmpty()
                ? "正在准备 AI 导览房间…" : message;
        rtcStatusText.setText(safeMessage);
        rtcSessionBody.setText(safeMessage);

        boolean roomOnline = state == RealtimeGuideManager.State.READY
                || state == RealtimeGuideManager.State.AUDIO_LINK_STARTING
                || state == RealtimeGuideManager.State.LISTENING
                || state == RealtimeGuideManager.State.PAUSED;
        int dot = roomOnline ? R.drawable.dot_status_connected
                : R.drawable.dot_status_disconnected;
        rtcCardStatusDot.setBackgroundResource(dot);
        rtcControlStatusDot.setBackgroundResource(dot);

        switch (state) {
            case READY:
                dialogueButton.setText("开始对话");
                dialogueButton.setEnabled(true);
                break;
            case AUDIO_LINK_STARTING:
                dialogueButton.setText("正在连接眼镜…");
                dialogueButton.setEnabled(false);
                break;
            case LISTENING:
                dialogueButton.setText("暂停对话");
                dialogueButton.setEnabled(true);
                break;
            case PAUSED:
                dialogueButton.setText("继续对话");
                dialogueButton.setEnabled(true);
                break;
            case ERROR:
                dialogueButton.setText("重试连接");
                dialogueButton.setEnabled(true);
                break;
            case RTC_CONNECTING:
                dialogueButton.setText("正在进入房间…");
                dialogueButton.setEnabled(false);
                break;
            case STOPPING:
                dialogueButton.setText("正在结束游览…");
                dialogueButton.setEnabled(false);
                break;
            case IDLE:
            default:
                dialogueButton.setText("等待开始游览");
                dialogueButton.setEnabled(false);
                break;
        }

        boolean effectiveVisionBusy = visionBusy || guideManager.isVisionOperationInProgress();
        if (effectiveVisionBusy) {
            dialogueButton.setText("识图中，请稍候");
            dialogueButton.setEnabled(false);
        }

        boolean photoAvailable = !effectiveVisionBusy
                && guideManager.isVisionEnabled()
                && (state == RealtimeGuideManager.State.READY
                || state == RealtimeGuideManager.State.PAUSED
                || state == RealtimeGuideManager.State.LISTENING);
        photoButton.setEnabled(photoAvailable);
        photoButton.setText(effectiveVisionBusy ? "正在拍照…" : getString(R.string.photo_hint));
    }

    /** 断开字幕合并锚点：下一条字幕将新建气泡，而不是追加进上方旧气泡。 */
    private void resetVolcSubtitleBubble() {
        volcBubbleIndex = -1;
        volcBubbleText.setLength(0);
    }

    private void upsertSubtitle(boolean fromSelf, String text,
                                boolean definite, long sequence) {
        if (!viewActive || text == null || text.trim().isEmpty()) return;
        if (!definite) return;

        String normalized = text.trim();
        DialogueMessage.Type type = fromSelf
                ? DialogueMessage.Type.VOICE : DialogueMessage.Type.AI_REPLY;
        String key = (fromSelf ? "self:" : "agent:") + sequence;
        if (!renderedFinalSubtitleKeys.add(key)) return;

        boolean sameSpeaker = volcBubbleIndex >= 0
                && volcBubbleIndex < messages.size()
                && volcBubbleFromSelf == fromSelf
                && messages.get(volcBubbleIndex).getType() == type;
        if (!sameSpeaker) {
            volcBubbleFromSelf = fromSelf;
            volcBubbleText.setLength(0);
            volcBubbleText.append(normalized);
            volcBubbleIndex = appendMessageDirect(new DialogueMessage(
                    type, volcBubbleText.toString(), System.currentTimeMillis()));
        } else {
            volcBubbleText.append(normalized);
            updateMessageDirect(volcBubbleIndex, volcBubbleText.toString());
        }
    }

    /** App 主动拍照：抢占眼镜硬件任务，图片返回后注入当前 RTC Agent。 */
    private boolean requestVisionCapture(@Nullable String requestedQuestion,
                                         @Nullable String commandId,
                                         boolean showFailure) {
        if (visionBusy || guideManager.isVisionOperationInProgress()) return false;
        if (!guideManager.isVisionEnabled()) {
            if (showFailure) showToast("当前场馆未开启拍照识别");
            return false;
        }
        RealtimeGuideManager.State state = guideManager.getState();
        if (state != RealtimeGuideManager.State.READY
                && state != RealtimeGuideManager.State.PAUSED
                && state != RealtimeGuideManager.State.LISTENING) {
            if (showFailure) showToast("AI 导览房间尚未就绪");
            return false;
        }

        BleService bleService = BleService.getInstance();
        CRPBleConnection connection = bleService.getConnection();
        if (!bleService.isConnected() || connection == null) {
            if (showFailure) showToast("拍照需要先连接眼镜");
            return false;
        }
        if (!guideManager.reserveVisionCapture(commandId)) {
            if (showFailure) showToast("已有识图任务，请稍候");
            return false;
        }

        visionBusy = true;
        visionImageAccepted = false;
        visionHardwareStageActive = true;
        visionCommandId = commandId;
        visionQuestion = requestedQuestion == null || requestedQuestion.trim().isEmpty()
                ? "请介绍我眼前的展品或产品"
                : requestedQuestion.trim();
        resumeAfterVisionFailure = state == RealtimeGuideManager.State.LISTENING;
        int operation = ++visionGeneration;
        visionConnection = connection;
        if (resumeAfterVisionFailure) guideManager.pauseGuidance();
        // 拍照发起即落一条状态气泡，作为对话时间轴锚点，确保后续 AI 识图讲解
        // （走 RTC 字幕通道）不会抢在“拍照—照片—讲解”之前。
        // 关键：重置字幕合并锚点，否则识图讲解字幕会 sameSpeaker 命中拍照前那条
        // 旧 AI 气泡、被追加到照片上方，造成“讲解跑到照片前”的乱序。
        resetVolcSubtitleBubble();
        appendMessageDirect(new DialogueMessage(
                DialogueMessage.Type.AI_REPLY,
                "正在拍照…",
                System.currentTimeMillis()));
        renderState(guideManager.getState(), "正在准备眼镜拍照…");

        long delay = resumeAfterVisionFailure ? HARDWARE_RELEASE_DELAY_MS : 0L;
        mainHandler.postDelayed(() -> beginVisionCapture(operation, connection), delay);
        return true;
    }

    private void beginVisionCapture(int operation, CRPBleConnection connection) {
        if (!viewActive || !visionBusy || operation != visionGeneration
                || connection != visionConnection) {
            return;
        }
        RealtimeGuideManager.State currentState = guideManager.getState();
        boolean reservationActive = guideManager.isVisionCaptureReserved(visionCommandId);
        if (!isVisionReadyState(currentState)
                || !TourSessionManager.get().isActive()
                || !reservationActive) {
            // 状态失效时 Manager 的状态机会取消 reservation；如果只是 tour
            // 已失效但房间状态尚未来得及变化，则主动归还仍存在的预留。
            cancelVisionCapture(isVisionReadyState(currentState) && reservationActive);
            return;
        }
        try {
            connection.setAiDialogueListener(new CRPAiDialogueListener() {
                @Override public void onDialogueStart() { }
                @Override public void onDialogueAudioChange(byte[] audioBytes) { }

                @Override
                public void onDialogueImageChange(File imageFile) {
                    if (imageFile == null) return;
                    postUi(() -> onVisionImage(operation, imageFile));
                }

                @Override public void onDialogueStop(boolean isTimeout) { }
            });
            connection.takePhoto(TakePhoto.PhotoMode.ModeAIRecognition);
            visionTimeout = () -> failVisionCapture(
                    operation, "没有收到眼镜图片，请重试", true);
            mainHandler.postDelayed(visionTimeout, VISION_CAPTURE_TIMEOUT_MS);
            renderState(guideManager.getState(), "正在等待眼镜返回图片…");
        } catch (RuntimeException error) {
            Log.e(TAG, "触发 AI 识图拍照失败", error);
            failVisionCapture(operation, "眼镜拍照启动失败", true);
        }
    }

    private void onVisionImage(int operation, File imageFile) {
        if (!viewActive || !visionBusy || visionImageAccepted
                || operation != visionGeneration) return;
        visionImageAccepted = true;
        visionHardwareStageActive = false;
        clearVisionHardwareListener();

        DialogueMessage photo = new DialogueMessage(
                DialogueMessage.Type.PHOTO, "眼镜拍照", System.currentTimeMillis());
        photo.setImageFile(imageFile);
        appendMessageDirect(photo);
        appendMessageDirect(new DialogueMessage(
                DialogueMessage.Type.AI_REPLY,
                "照片已收到，正在交给 AI 导览员讲解…",
                System.currentTimeMillis()));
        // 让紧随其后的识图讲解字幕独立成条，排在照片/状态提示之后，不被合并进上方气泡。
        resetVolcSubtitleBubble();

        boolean wasListening = resumeAfterVisionFailure;
        String question = visionQuestion;
        String commandId = visionCommandId;
        guideManager.injectVisionImage(imageFile, question, commandId,
                (success, message) -> {
                    if (operation != visionGeneration) return;
                    visionBusy = false;
                    visionImageAccepted = false;
                    visionHardwareStageActive = false;
                    resumeAfterVisionFailure = false;
                    visionQuestion = null;
                    visionCommandId = null;
                    renderState(guideManager.getState(), guideManager.getStateMessage());
                    if (!success) {
                        appendMessageDirect(new DialogueMessage(
                                DialogueMessage.Type.AI_REPLY,
                                message,
                                System.currentTimeMillis()));
                    }
                    // FC 链路：func 回填后火山在同一会话继续讲解，不再割裂交互。
                    // 拍照时为释放眼镜硬件通道短暂 pause 了收音，这里自动恢复，
                    // 无需用户手动点“继续对话”。
                    if (wasListening && !guideManager.hasPendingVisionRequest()) {
                        guideManager.startGuidance();
                    }
                });
    }

    private void failVisionCapture(int operation, String message, boolean resumeIfNeeded) {
        if (operation != visionGeneration) return;
        boolean shouldResume = resumeIfNeeded && resumeAfterVisionFailure;
        String failedCommandId = visionCommandId;
        clearVisionHardwareListener();
        visionBusy = false;
        visionImageAccepted = false;
        visionHardwareStageActive = false;
        resumeAfterVisionFailure = false;
        visionQuestion = null;
        visionCommandId = null;
        guideManager.abandonVisionCapture(
                failedCommandId, true, message);
        renderState(guideManager.getState(), guideManager.getStateMessage());
        appendMessageDirect(new DialogueMessage(
                DialogueMessage.Type.AI_REPLY, message, System.currentTimeMillis()));
        if (shouldResume && !guideManager.hasPendingVisionRequest()) {
            guideManager.startGuidance();
        }
    }

    private void clearVisionHardwareListener() {
        Runnable timeout = visionTimeout;
        visionTimeout = null;
        if (timeout != null) mainHandler.removeCallbacks(timeout);

        CRPBleConnection connection = visionConnection;
        visionConnection = null;
        if (connection != null) {
            try {
                connection.setAiDialogueListener(null);
            } catch (RuntimeException error) {
                Log.w(TAG, "释放 AI 图片监听器失败", error);
            }
        }
    }

    private void cancelVisionCapture() {
        cancelVisionCapture(true);
    }

    private void cancelVisionCapture(boolean abandonManagerReservation) {
        boolean abandonHardwareStage = visionHardwareStageActive;
        String cancelledCommandId = visionCommandId;
        ++visionGeneration;
        visionBusy = false;
        visionImageAccepted = false;
        visionHardwareStageActive = false;
        resumeAfterVisionFailure = false;
        visionQuestion = null;
        visionCommandId = null;
        clearVisionHardwareListener();
        if (abandonManagerReservation && abandonHardwareStage) {
            guideManager.abandonVisionCapture(
                    cancelledCommandId, true, "拍照流程已取消");
        }
    }

    private boolean isVisionReadyState(RealtimeGuideManager.State state) {
        return state == RealtimeGuideManager.State.READY
                || state == RealtimeGuideManager.State.PAUSED
                || state == RealtimeGuideManager.State.LISTENING;
    }

    private int appendMessageDirect(DialogueMessage message) {
        if (!viewActive || messageAdapter == null || recyclerMessages == null) return -1;
        messages.add(message);
        int index = messages.size() - 1;
        messageAdapter.notifyItemInserted(index);
        recyclerMessages.smoothScrollToPosition(index);
        return index;
    }

    private void updateMessageDirect(int index, String text) {
        if (!viewActive || messageAdapter == null || index < 0 || index >= messages.size()) return;
        messages.get(index).setText(text);
        messageAdapter.notifyItemChanged(index);
        recyclerMessages.smoothScrollToPosition(index);
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

    @Override
    public void onDestroyView() {
        guideManager.removeListener(realtimeListener);
        cancelVisionCapture();
        mainHandler.removeCallbacksAndMessages(null);
        viewActive = false;
        dialogueButton = null;
        photoButton = null;
        rtcStatusText = null;
        rtcSessionBody = null;
        rtcCardStatusDot = null;
        rtcControlStatusDot = null;
        recyclerMessages = null;
        messageAdapter = null;
        super.onDestroyView();
    }
}
