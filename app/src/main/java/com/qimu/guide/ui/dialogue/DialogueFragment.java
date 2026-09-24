package com.qimu.guide.ui.dialogue;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
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
import com.qimu.guide.service.SubtitleTranscript;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
    private View rtcControlStatusDot;
    private boolean startGuidanceAfterAudioPermission;
    private boolean waitingForAudioPermissionSettings;

    private final ActivityResultLauncher<String> recordAudioPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    boolean shouldStart = startGuidanceAfterAudioPermission;
                    startGuidanceAfterAudioPermission = false;
                    if (shouldStart) startGuidanceIfReady();
                    return;
                }
                startGuidanceAfterAudioPermission = false;
                if (!isAdded()) return;
                if (ActivityCompat.shouldShowRequestPermissionRationale(
                        requireActivity(), Manifest.permission.RECORD_AUDIO)) {
                    Toast.makeText(requireContext(), R.string.audio_permission_denied,
                            Toast.LENGTH_LONG).show();
                } else {
                    showAudioPermissionSettingsDialog();
                }
            });

    private final SubtitleTimeline subtitleTimeline = new SubtitleTimeline();

    private int visionGeneration;
    private boolean visionBusy;
    private boolean visionImageAccepted;
    private boolean visionHardwareStageActive;
    private boolean resumeAfterVisionFailure;
    private String visionCommandId;
    private CRPBleConnection visionConnection;
    private Runnable visionTimeout;

    private final RealtimeGuideManager.Listener realtimeListener =
            new RealtimeGuideManager.Listener() {
                @Override
                public void onStateChanged(RealtimeGuideManager.State state, String message) {
                    postUi(() -> {
                        if (!isVisionReadyState(state) || !guideManager.hasVisionSession()) {
                            // Manager 负责取消自己的 reservation/HTTP；这里只立即释放
                            // Fragment 的延迟拍照、BLE listener 和硬件阶段。
                            cancelVisionCapture(false);
                        }
                        renderState(state, message);
                    });
                }

                @Override
                public void onSubtitle(SubtitleTranscript.Entry entry) {
                    postUi(() -> {
                        subtitleTimeline.upsert(entry);
                        renderTimeline();
                    });
                }

                @Override
                public boolean onVisionCaptureRequested(String commandId) {
                    if (!viewActive || getActivity() == null || getView() == null) return false;
                    return requestVisionCapture(commandId, false);
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
        rtcControlStatusDot = view.findViewById(R.id.rtc_control_status_dot);

        dialogueButton.setOnClickListener(clicked -> handleDialogueButton());
        photoButton.setOnClickListener(clicked -> requestVisionCapture(null, true));

        // 先注册再取快照，避免视图重建时字幕恰好到达而漏掉一条。
        guideManager.addListener(realtimeListener);
        for (SubtitleTranscript.Entry entry : guideManager.getTranscriptSnapshot()) {
            subtitleTimeline.upsert(entry);
        }
        renderTimeline();

        renderState(guideManager.getState(), guideManager.getStateMessage());

        if (TourSessionManager.get().consumeFirstTutorial()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.dialogue_tutorial_title)
                    .setMessage(R.string.dialogue_tutorial_body)
                    .setPositiveButton(R.string.dialogue_tutorial_action, null)
                    .show();
        }
    }

    private void handleDialogueButton() {
        if (guideManager.isRecovering()) {
            if (guideManager.wantsAudioAfterRecovery()) guideManager.pauseGuidance();
            else startGuidanceWithAudioPermission();
            return;
        }
        switch (guideManager.getState()) {
            case READY:
            case PAUSED:
                startGuidanceWithAudioPermission();
                break;
            case LISTENING:
            case AUDIO_LINK_STARTING:
                guideManager.pauseGuidance();
                break;
            case ERROR:
                if (guideManager.requiresTourRestart()) {
                    com.google.android.material.bottomnavigation.BottomNavigationView navigation =
                            requireActivity().findViewById(R.id.bottom_navigation);
                    if (navigation != null) navigation.setSelectedItemId(R.id.nav_export);
                } else {
                    guideManager.retryCurrentTour();
                }
                break;
            default:
                break;
        }
    }

    private void startGuidanceWithAudioPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            guideManager.startGuidance();
            return;
        }

        startGuidanceAfterAudioPermission = true;
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                requireActivity(), Manifest.permission.RECORD_AUDIO)) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.audio_permission_title)
                    .setMessage(R.string.audio_permission_rationale)
                    .setNegativeButton(R.string.cancel, (dialog, which) ->
                            startGuidanceAfterAudioPermission = false)
                    .setPositiveButton(R.string.audio_permission_continue, (dialog, which) ->
                            recordAudioPermissionLauncher.launch(
                                    Manifest.permission.RECORD_AUDIO))
                    .show();
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startGuidanceIfReady() {
        if (!isAdded() || !viewActive) return;
        RealtimeGuideManager.State state = guideManager.getState();
        if (guideManager.isRecovering() || state == RealtimeGuideManager.State.READY
                || state == RealtimeGuideManager.State.PAUSED) {
            guideManager.startGuidance();
        }
    }

    private void showAudioPermissionSettingsDialog() {
        if (!isAdded()) return;
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.audio_permission_settings_title)
                .setMessage(R.string.audio_permission_settings_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.audio_permission_open_settings, (dialog, which) -> {
                    waitingForAudioPermissionSettings = true;
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", requireContext().getPackageName(), null));
                    startActivity(intent);
                })
                .show();
    }

    private void renderState(RealtimeGuideManager.State state, String message) {
        if (!viewActive || dialogueButton == null) return;

        String safeMessage = message == null || message.trim().isEmpty()
                ? getString(R.string.dialogue_preparing) : message;
        rtcStatusText.setText(safeMessage);

        boolean roomOnline = state == RealtimeGuideManager.State.READY
                || state == RealtimeGuideManager.State.AUDIO_LINK_STARTING
                || state == RealtimeGuideManager.State.LISTENING
                || state == RealtimeGuideManager.State.PAUSED;
        int dot = roomOnline ? R.drawable.dot_status_connected
                : R.drawable.dot_status_disconnected;
        rtcControlStatusDot.setBackgroundResource(dot);

        switch (state) {
            case READY:
                dialogueButton.setText(R.string.dialogue_action_start);
                dialogueButton.setEnabled(true);
                break;
            case AUDIO_LINK_STARTING:
                dialogueButton.setText(R.string.dialogue_connecting);
                dialogueButton.setEnabled(false);
                break;
            case LISTENING:
                dialogueButton.setText(R.string.dialogue_action_pause);
                dialogueButton.setEnabled(true);
                break;
            case PAUSED:
                dialogueButton.setText(R.string.dialogue_action_continue);
                dialogueButton.setEnabled(true);
                break;
            case ERROR:
                if (guideManager.requiresTourRestart()) dialogueButton.setText("前往结束导览");
                else dialogueButton.setText(R.string.dialogue_action_retry);
                dialogueButton.setEnabled(true);
                break;
            case RTC_CONNECTING:
                dialogueButton.setText(R.string.dialogue_connecting);
                dialogueButton.setEnabled(false);
                break;
            case STOPPING:
                dialogueButton.setText(R.string.dialogue_action_stopping);
                dialogueButton.setEnabled(false);
                break;
            case IDLE:
            default:
                dialogueButton.setText(R.string.dialogue_action_waiting);
                dialogueButton.setEnabled(false);
                break;
        }

        if (guideManager.isRecovering()) {
            dialogueButton.setText(guideManager.wantsAudioAfterRecovery()
                    ? R.string.dialogue_action_pause : R.string.dialogue_action_continue);
            dialogueButton.setEnabled(true);
        }

        boolean effectiveVisionBusy = visionBusy || guideManager.isVisionOperationInProgress();
        if (effectiveVisionBusy) {
            dialogueButton.setText(R.string.dialogue_action_vision_busy);
            dialogueButton.setEnabled(false);
        }

        boolean photoAvailable = !effectiveVisionBusy
                && guideManager.hasVisionSession()
                && (state == RealtimeGuideManager.State.READY
                || state == RealtimeGuideManager.State.PAUSED
                || state == RealtimeGuideManager.State.LISTENING);
        photoButton.setEnabled(photoAvailable);
        photoButton.setText(effectiveVisionBusy
                ? R.string.dialogue_action_photo_busy : R.string.photo_hint);
    }

    /** App 主动拍照：抢占眼镜硬件任务，图片返回后交给 RTC Agent 讲解（与模型 take_photo 同一链路）。 */
    private boolean requestVisionCapture(@Nullable String commandId,
                                         boolean showFailure) {
        if (visionBusy || guideManager.isVisionOperationInProgress()) return false;
        if (!guideManager.hasVisionSession()) {
            if (showFailure) showToast(getString(R.string.dialogue_not_ready));
            return false;
        }
        RealtimeGuideManager.State state = guideManager.getState();
        if (state != RealtimeGuideManager.State.READY
                && state != RealtimeGuideManager.State.PAUSED
                && state != RealtimeGuideManager.State.LISTENING) {
            if (showFailure) showToast(getString(R.string.dialogue_not_ready));
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
        resumeAfterVisionFailure = state == RealtimeGuideManager.State.LISTENING;
        int operation = ++visionGeneration;
        visionConnection = connection;
        if (resumeAfterVisionFailure) guideManager.pauseGuidance();
        // 拍照发起即落一条状态气泡，作为对话时间轴锚点，确保后续 AI 识图讲解
        // （走 RTC 字幕通道）不会抢在“拍照—照片—讲解”之前。
        // 照片/状态消息在统一投影中自然形成边界，后续字幕不能合并到照片上方。
        appendMessageDirect(new DialogueMessage(
                DialogueMessage.Type.STATUS_HINT,
                "正在拍照",
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
                DialogueMessage.Type.STATUS_HINT,
                "照片已收到，正在交给 AI 导览员讲解",
                System.currentTimeMillis()));

        boolean wasListening = resumeAfterVisionFailure;
        String commandId = visionCommandId;
        guideManager.injectVisionImage(imageFile, commandId,
                (success, message) -> {
                    if (operation != visionGeneration) return;
                    visionBusy = false;
                    visionImageAccepted = false;
                    visionHardwareStageActive = false;
                    resumeAfterVisionFailure = false;
                    visionCommandId = null;
                    renderState(guideManager.getState(), guideManager.getStateMessage());
                    if (!success) {
                        appendMessageDirect(new DialogueMessage(
                                DialogueMessage.Type.AI_REPLY,
                                message,
                                System.currentTimeMillis()));
                    }
                    // FC 链路：func 回填后火山在同一会话继续讲解，拍照时为释放眼镜硬件
                    // 通道短暂 pause 的收音在此自动恢复，无需用户手动点“继续对话”。
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

    private void appendMessageDirect(DialogueMessage message) {
        if (!viewActive) return;
        subtitleTimeline.append(message);
        renderTimeline();
    }

    private void renderTimeline() {
        if (!viewActive || messageAdapter == null || recyclerMessages == null) return;
        messages.clear();
        messages.addAll(subtitleTimeline.snapshot());
        messageAdapter.notifyDataSetChanged();
        if (!messages.isEmpty()) recyclerMessages.smoothScrollToPosition(messages.size() - 1);
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
    public void onResume() {
        super.onResume();
        if (!waitingForAudioPermissionSettings) return;
        waitingForAudioPermissionSettings = false;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startGuidanceIfReady();
        }
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
        rtcControlStatusDot = null;
        recyclerMessages = null;
        messageAdapter = null;
        super.onDestroyView();
    }
}
