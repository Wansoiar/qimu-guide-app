package com.qimu.guide.service;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.moyoung.glasses.conn.CRPBleConnection;
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener;
import com.qimu.guide.QimuApplication;
import com.qimu.guide.net.GuideApiClient;
import com.qimu.guide.net.TourSessionManager;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 一次游览范围内的实时 AI 导览总控。
 *
 * 生命周期：开始游览进房，App 点击开始/继续后才打开眼镜麦克风，暂停只关闭收音，
 * 正常交互中只有结束游览才退房；不可恢复的 RTC 错误会立即释放坏房间与 Agent，
 * 避免空转计费。注意：暂停并不等于停止 RTC/Agent 计费。
 */
public final class RealtimeGuideManager {

    private static final String TAG = "RealtimeGuide";
    private static final RealtimeGuideManager INSTANCE = new RealtimeGuideManager();
    private static final long AUDIO_LINK_START_TIMEOUT_MS = 8_000L;
    private static final long RTC_READY_TIMEOUT_MS = 20_000L;
    private static final long VISION_COMMAND_TTL_MS = 30_000L;
    private static final long MEDIA_AUDIO_RELEASE_GRACE_MS = 1_000L;
    private static final long SUBTITLE_CROSS_CHANNEL_DEDUP_MS = 1_500L;

    public enum State {
        IDLE,
        RTC_CONNECTING,
        READY,
        AUDIO_LINK_STARTING,
        LISTENING,
        PAUSED,
        STOPPING,
        ERROR
    }

    public interface Listener {
        void onStateChanged(State state, String message);
        void onSubtitle(boolean fromSelf, String text, boolean definite, long sequence);
        default boolean onVisionCaptureRequested(String commandId, String question) {
            return false;
        }
        default void onVisionOperationChanged(boolean inProgress, String message) { }
    }

    public interface OperationCallback {
        void onComplete(boolean success, String message);
    }

    public static final class TranscriptEntry {
        public final boolean fromSelf;
        public final String text;
        public final boolean definite;
        public final long sequence;

        TranscriptEntry(boolean fromSelf, String text, boolean definite, long sequence) {
            this.fromSelf = fromSelf;
            this.text = text;
            this.definite = definite;
            this.sequence = sequence;
        }
    }

    private static final class PendingVisionRequest {
        final String commandId;
        final String question;
        final long createdElapsedMs;
        int attempts;

        PendingVisionRequest(String commandId, String question) {
            this.commandId = commandId;
            this.question = question;
            this.createdElapsedMs = SystemClock.elapsedRealtime();
        }
    }

    private static final class RecentSubtitle {
        final String transcriptKey;
        long seenElapsedMs;

        RecentSubtitle(String transcriptKey, long seenElapsedMs) {
            this.transcriptKey = transcriptKey;
            this.seenElapsedMs = seenElapsedMs;
        }
    }

    public static RealtimeGuideManager get() {
        return INSTANCE;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tour-rtc-api");
        thread.setDaemon(true);
        return thread;
    });
    // StopVoiceChat 不能排在最长 90 秒的图片上传之后，否则“结束游览”会继续计费。
    private final ExecutorService stopExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tour-rtc-stop");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Object transcriptLock = new Object();
    private final Map<String, TranscriptEntry> transcript = new LinkedHashMap<>();
    private final Map<String, RecentSubtitle> recentSubtitlesByContent = new HashMap<>();
    private final Set<String> handledCommandIds = new HashSet<>();
    private final GuideApiClient apiClient = new GuideApiClient();
    private final GlassesPcmAudioSource glassesAudioSource = new GlassesPcmAudioSource();

    private volatile State state = State.IDLE;
    private volatile String stateMessage = "尚未开始游览";
    private volatile RtcVoiceChatManager rtc;

    private volatile int generation;
    private String tourSessionId;
    private String transcriptTourSessionId;
    private TourSessionManager.TourSession tourSession;
    private GuideApiClient.RtcSessionInfo rtcSession;
    private boolean bleListenerRegistered;
    // 仅在主线程读写。RTC 进房和 VoiceChat Agent 进房是两个独立事件，
    // 二者都完成后才允许打开眼镜 PCM，避免用户第一句话发进“空房”。
    private boolean rtcRoomJoined;
    private boolean agentOnline;
    private int audioStartAttempt;
    private int rtcReadyAttempt;
    // 从导出点击开始到 Wi-Fi 传输收尾前始终拦截收音重启，
    // 覆盖 stopTranslation 后的 1 s 释放窗口。
    private boolean mediaTransferAudioHold;
    // 让“重试连接”的延迟重建可被结束游览或后续重试失效，避免旧 runnable
    // 在房间已经关闭后再次启动 Agent。
    private int rtcRetryAttempt;
    private volatile boolean visionOperationInProgress;
    private volatile int visionOperationId;
    private OperationCallback activeVisionCallback;
    private volatile String activeVisionCommandId;
    private PendingVisionRequest pendingVisionRequest;

    private final BleService.BleListener bleListener = new BleService.BleListener() {
        @Override
        public void onConnectionStateChanged(int connectionState) {
            if (connectionState != CRPBleConnectionStateListener.STATE_CONNECTED) {
                mainHandler.post(() -> pauseForGlassesDisconnect("眼镜已断开，对话收音已暂停"));
            } else {
                mainHandler.post(RealtimeGuideManager.this::deliverPendingVisionRequest);
            }
        }

        @Override public void onBatteryUpdate(int level, boolean charging) { }
        @Override public void onFirmwareVersion(String version) { }
        @Override public void onMediaFileChanged(int photoCount, int videoCount, int audioCount) { }
        @Override public void onWifiStateChange(int wifiState) { }
        @Override public void onWifiConnectionChanged(boolean connected) { }
        @Override public void onLog(String tag, String message) { }
        @Override public void onError(String message) { }
    };

    private RealtimeGuideManager() {
    }

    public void addListener(@Nullable Listener listener) {
        if (listener == null) return;
        listeners.add(listener);
        mainHandler.post(() -> {
            if (!listeners.contains(listener)) return;
            listener.onStateChanged(state, stateMessage);
            listener.onVisionOperationChanged(visionOperationInProgress,
                    visionOperationInProgress ? "照片正在交给 AI 讲解…" : "");
            deliverPendingVisionRequest();
        });
    }

    public void removeListener(@Nullable Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    public State getState() {
        return state;
    }

    public String getStateMessage() {
        return stateMessage;
    }

    public List<TranscriptEntry> getTranscriptSnapshot() {
        synchronized (transcriptLock) {
            return new ArrayList<>(transcript.values());
        }
    }

    public boolean isVisionEnabled() {
        GuideApiClient.RtcSessionInfo current = rtcSession;
        return current != null && current.photoEnabled;
    }

    public boolean isVisionOperationInProgress() {
        return visionOperationInProgress;
    }

    /** Fragment 在真正触发硬件拍照前复核预留仍属于当前任务。 */
    public boolean isVisionCaptureReserved(@Nullable String commandId) {
        return visionOperationInProgress && sameCommandId(activeVisionCommandId, commandId);
    }

    public boolean hasPendingVisionRequest() {
        return pendingVisionRequest != null;
    }

    public void retryPendingVisionRequest() {
        mainHandler.post(this::deliverPendingVisionRequest);
    }

    /** 在真正占用眼镜 AIRecognition 前预留整条识图链路。仅主线程调用。 */
    public boolean reserveVisionCapture(@Nullable String commandId) {
        if (Looper.myLooper() != Looper.getMainLooper() || visionOperationInProgress) {
            return false;
        }
        if (mediaTransferAudioHold || BleService.getInstance().isMediaDownloadActive()) {
            return false;
        }
        if (commandId == null) {
            // 服务端语音指令优先于用户此刻新点的手动拍照。
            if (pendingVisionRequest != null) return false;
        } else {
            PendingVisionRequest pending = pendingVisionRequest;
            if (pending == null || !commandId.equals(pending.commandId)) return false;
            pending.attempts++;
        }

        visionOperationId++;
        activeVisionCommandId = commandId;
        activeVisionCallback = null;
        apiClient.beginVisionCalls();
        publishVisionOperation(true, "正在调用眼镜拍照…");
        return true;
    }

    public void abandonVisionCapture(@Nullable String commandId,
                                     boolean retryRemoteCommand,
                                     String message) {
        Runnable abandon = () -> abandonVisionCaptureOnMain(
                commandId, retryRemoteCommand, message);
        if (Looper.myLooper() == Looper.getMainLooper()) abandon.run();
        else mainHandler.post(abandon);
    }

    private void abandonVisionCaptureOnMain(@Nullable String commandId,
                                            boolean retryRemoteCommand,
                                            String message) {
        if (!visionOperationInProgress || !sameCommandId(activeVisionCommandId, commandId)) {
            return;
        }
        OperationCallback callback = activeVisionCallback;
        activeVisionCallback = null;
        visionOperationId++;
        apiClient.cancelVisionCalls();

        PendingVisionRequest pending = pendingVisionRequest;
        boolean canRetry = retryRemoteCommand && commandId != null && pending != null
                && commandId.equals(pending.commandId)
                && pending.attempts < 2
                && SystemClock.elapsedRealtime() - pending.createdElapsedMs
                <= VISION_COMMAND_TTL_MS;
        if (commandId != null && !canRetry && pending != null
                && commandId.equals(pending.commandId)) {
            pendingVisionRequest = null;
        }
        activeVisionCommandId = null;
        publishVisionOperation(false, message);
        if (callback != null) callback.onComplete(false, message);
        if (canRetry) {
            mainHandler.postDelayed(this::deliverPendingVisionRequest, 750L);
        } else {
            deliverPendingVisionRequest();
        }
    }

    private boolean sameCommandId(@Nullable String first, @Nullable String second) {
        return first == null ? second == null : first.equals(second);
    }

    /** TourSessionManager 成功提交会话后调用。 */
    public void startForTour(@NonNull TourSessionManager.TourSession session) {
        mainHandler.post(() -> startForTourOnMain(session));
    }

    private void startForTourOnMain(TourSessionManager.TourSession session) {
        // 任何直接启动都会使此前排队的“重试连接”失效；延迟重试本身会先完成校验，
        // 再进入这里建立唯一的新房间。
        rtcRetryAttempt++;
        TourSessionManager.TourSession activeTour = TourSessionManager.get().current();
        if (TourReturnCoordinator.get().isInProgress() || activeTour != session) {
            Log.w(TAG, "游览已失效或正在归还，拒绝创建 RTC 房间");
            return;
        }
        if (session.sessionId.equals(tourSessionId)
                && state != State.IDLE && state != State.ERROR) {
            return;
        }
        if (state == State.ERROR) {
            // ERROR 可能仍持有旧 room/task。先完整释放再重建，避免 Activity 重建或
            // 用户重试时覆盖引用，留下继续计费的孤儿 Agent。
            stopForTourOnMain(tourSessionId, false);
        }
        if (state != State.IDLE && state != State.ERROR) {
            Log.w(TAG, "已有 RTC 会话，拒绝覆盖: " + state);
            return;
        }

        int requestGeneration = ++generation;
        tourSession = session;
        tourSessionId = session.sessionId;
        rtcRoomJoined = false;
        agentOnline = false;
        audioStartAttempt++;
        rtcReadyAttempt++;
        if (!session.sessionId.equals(transcriptTourSessionId)) {
            transcriptTourSessionId = session.sessionId;
            synchronized (transcriptLock) {
                transcript.clear();
                recentSubtitlesByContent.clear();
            }
            handledCommandIds.clear();
        }
        registerBleListener();
        updateState(State.RTC_CONNECTING, "AI 导览员正在上线…");

        ioExecutor.execute(() -> {
            // 对齐 feat/volc-main-dialogue 已跑通的 RTC 编排契约：这里只传场馆。
            // Tour Session 属于 App 游览生命周期，不作为 RTC 接口的 session_id。
            GuideApiClient.RtcSessionInfo created =
                    apiClient.createRtcSession(session.venueId);
            mainHandler.post(() -> onRtcSessionCreated(requestGeneration, session, created));
        });
    }

    private void onRtcSessionCreated(int requestGeneration,
                                     TourSessionManager.TourSession requestedTour,
                                     @Nullable GuideApiClient.RtcSessionInfo created) {
        if (requestGeneration != generation
                || tourSession == null
                || !requestedTour.sessionId.equals(tourSessionId)
                || TourReturnCoordinator.get().isInProgress()
                || TourSessionManager.get().current() != requestedTour) {
            if (created != null) stopServerSessionAsync(created);
            return;
        }
        if (created == null) {
            updateState(State.ERROR, "AI 导览员连接失败，点击重试");
            return;
        }

        rtcSession = created;
        RtcVoiceChatManager manager = new RtcVoiceChatManager(QimuApplication.getAppContext());
        rtc = manager;
        updateState(State.RTC_CONNECTING,
                created.mocked ? "当前为 RTC 模拟模式，AI 导览员不会响应" : "正在连接 AI 导览员…");
        manager.start(created, createRtcListener(requestGeneration, manager));
        scheduleRtcReadyTimeout(requestGeneration, manager,
                "AI 导览员连接超时，当前房间已停止；点击重试");
    }

    /** App “开始语音导览/继续语音导览”。RTC 已在房内，仅开启眼镜麦克风链路。 */
    public void startGuidance() {
        mainHandler.post(this::startGuidanceOnMain);
    }

    private void startGuidanceOnMain() {
        if (state != State.READY && state != State.PAUSED) return;
        if (mediaTransferAudioHold || BleService.getInstance().isMediaDownloadActive()) {
            updateState(State.PAUSED, "照片导出中，完成后可继续对话");
            return;
        }
        GuideApiClient.RtcSessionInfo currentSession = rtcSession;
        if (!rtcRoomJoined || currentSession == null
                || (!currentSession.mocked && !agentOnline)) {
            updateState(State.RTC_CONNECTING, "已进入 RTC 房间，正在等待 AI 导览员…");
            return;
        }
        BleService bleService = BleService.getInstance();
        CRPBleConnection connection = bleService.getConnection();
        if (!bleService.isConnected() || connection == null) {
            updateState(State.PAUSED, "眼镜未连接，连接后可继续语音导览");
            return;
        }
        RtcVoiceChatManager currentRtc = rtc;
        if (currentRtc == null) {
            updateState(State.ERROR, "RTC 房间不可用，点击重试");
            return;
        }

        int startGeneration = generation;
        int startAttempt = ++audioStartAttempt;
        currentRtc.setInputEnabled(false);
        updateState(State.AUDIO_LINK_STARTING, "正在连接眼镜麦克风…");
        glassesAudioSource.start(connection, new GlassesPcmAudioSource.Listener() {
            @Override
            public void onStarted() {
                mainHandler.post(() -> {
                    if (startGeneration != generation || startAttempt != audioStartAttempt
                            || state != State.AUDIO_LINK_STARTING) {
                        glassesAudioSource.pause();
                        return;
                    }
                    RtcVoiceChatManager joinedRtc = rtc;
                    if (joinedRtc == null) {
                        glassesAudioSource.pause();
                        updateState(State.ERROR, "AI 导览连接已断开");
                        return;
                    }
                    joinedRtc.setInputEnabled(true);
                    updateState(State.LISTENING, "AI 导览员正在聆听");
                });
            }

            @Override
            public void onPcm(byte[] pcm) {
                RtcVoiceChatManager activeRtc = rtc;
                if (state == State.LISTENING && activeRtc != null) {
                    activeRtc.pushExternalPcm(pcm);
                }
            }

            @Override
            public void onError(int errorCode, String message) {
                mainHandler.post(() -> {
                    if (startGeneration != generation || startAttempt != audioStartAttempt) return;
                    RtcVoiceChatManager joinedRtc = rtc;
                    if (joinedRtc != null) joinedRtc.setInputEnabled(false);
                    updateState(State.PAUSED,
                            message + "（" + errorCode + "），点击重试");
                });
            }
        });
        mainHandler.postDelayed(() -> {
            if (startGeneration != generation || startAttempt != audioStartAttempt
                    || state != State.AUDIO_LINK_STARTING) {
                return;
            }
            audioStartAttempt++;
            RtcVoiceChatManager joinedRtc = rtc;
            if (joinedRtc != null) joinedRtc.setInputEnabled(false);
            glassesAudioSource.pause();
            updateState(State.PAUSED, "眼镜麦克风连接超时，点击继续重试");
        }, AUDIO_LINK_START_TIMEOUT_MS);
    }

    /** App “暂停收音”。仅停止眼镜音频；AI 导览员仍保持在线。 */
    public void pauseGuidance() {
        mainHandler.post(() -> pauseGuidanceOnMain("已暂停收音 · AI 导览员仍在线"));
    }

    /**
     * 照片导出会独占眼镜的高带宽任务通道。这里只释放眼镜收音任务和
     * RTC 输入 gate，不退 RTC 房间、不停服务端 Agent。导出完成后保持暂停，
     * 由用户回到对话页主动点“继续对话”。
     */
    public void suspendForMediaTransfer(@NonNull Runnable onAudioReleased) {
        mainHandler.post(() -> {
            boolean audioTaskMayBeActive = state == State.LISTENING
                    || state == State.AUDIO_LINK_STARTING || state == State.PAUSED;
            mediaTransferAudioHold = true;
            audioStartAttempt++;
            RtcVoiceChatManager currentRtc = rtc;
            if (currentRtc != null) currentRtc.setInputEnabled(false);

            // pauseTranslation 只暂停 PCM，固件仍可能保留 aiTranslate 任务；
            // Wi-Fi FILE 传输前必须 stopTranslation 完整释放它。
            glassesAudioSource.stop();
            if (audioTaskMayBeActive) {
                updateState(State.PAUSED, "已暂停收音 · 正在导出眼镜照片");
                mainHandler.postDelayed(onAudioReleased, MEDIA_AUDIO_RELEASE_GRACE_MS);
            } else {
                onAudioReleased.run();
            }
        });
    }

    /** 传输已成功、失败或未能启动；允许用户再次主动开启收音。 */
    public void completeMediaTransferHold() {
        mainHandler.post(() -> {
            mediaTransferAudioHold = false;
            if (state == State.PAUSED) {
                updateState(State.PAUSED, "已暂停收音 · 点击继续对话");
            }
            deliverPendingVisionRequest();
        });
    }

    private void pauseGuidanceOnMain(String message) {
        if (state != State.LISTENING && state != State.AUDIO_LINK_STARTING) return;
        audioStartAttempt++;
        RtcVoiceChatManager currentRtc = rtc;
        if (currentRtc != null) currentRtc.setInputEnabled(false);
        glassesAudioSource.pause();
        updateState(State.PAUSED, message);
    }

    private void pauseForGlassesDisconnect(String message) {
        if (state != State.LISTENING && state != State.AUDIO_LINK_STARTING) return;
        audioStartAttempt++;
        RtcVoiceChatManager currentRtc = rtc;
        if (currentRtc != null) currentRtc.setInputEnabled(false);
        glassesAudioSource.stop();
        updateState(State.PAUSED, message);
    }

    public void retryCurrentTour() {
        mainHandler.post(() -> {
            TourSessionManager.TourSession current = TourSessionManager.get().current();
            if (current == null || TourReturnCoordinator.get().isInProgress()) return;
            stopForTourOnMain(current.sessionId, false);
            if (state != State.IDLE || rtcSession != null || rtc != null) return;

            int attempt = ++rtcRetryAttempt;
            mainHandler.postDelayed(() -> {
                TourSessionManager.TourSession active = TourSessionManager.get().current();
                if (attempt != rtcRetryAttempt
                        || TourReturnCoordinator.get().isInProgress()
                        || active != current
                        || state != State.IDLE || rtcSession != null || rtc != null) {
                    return;
                }
                startForTourOnMain(active);
            }, 300L);
        });
    }

    /** 点击结束游览后应立即调用；先停眼镜音频，再退房并停止后端 Agent。 */
    public void stopForTour(@Nullable String expectedTourSessionId) {
        mainHandler.post(() -> stopForTourOnMain(expectedTourSessionId, true));
    }

    private void stopForTourOnMain(@Nullable String expectedTourSessionId,
                                   boolean publishStopping) {
        // 即使当前已经是 IDLE，也必须先让排队中的延迟重试失效。
        rtcRetryAttempt++;
        if (expectedTourSessionId != null && tourSessionId != null
                && !expectedTourSessionId.equals(tourSessionId)) {
            return;
        }
        if (state == State.IDLE && rtcSession == null && rtc == null) return;

        ++generation;
        audioStartAttempt++;
        rtcReadyAttempt++;
        if (publishStopping) updateState(State.STOPPING, "正在关闭 AI 导览房间…");
        cancelVisionOperationOnMain(publishStopping
                ? "游览已结束，识图任务已取消"
                : "RTC 正在重连，请重新拍照");
        unregisterBleListener();

        RtcVoiceChatManager currentRtc = rtc;
        if (currentRtc != null) currentRtc.setInputEnabled(false);
        glassesAudioSource.stop();

        rtc = null;
        if (currentRtc != null) currentRtc.stop();

        GuideApiClient.RtcSessionInfo currentSession = rtcSession;
        rtcSession = null;
        tourSession = null;
        tourSessionId = null;
        pendingVisionRequest = null;
        mediaTransferAudioHold = false;
        rtcRoomJoined = false;
        agentOnline = false;
        if (publishStopping) {
            transcriptTourSessionId = null;
            synchronized (transcriptLock) {
                transcript.clear();
                recentSubtitlesByContent.clear();
            }
            handledCommandIds.clear();
        }
        if (currentSession != null) stopServerSessionAsync(currentSession);
        updateState(State.IDLE, "RTC 已关闭");
    }

    /** 上传眼镜照片并注入同一 RTC Agent，供语音触发视觉问答使用。 */
    public void injectVisionImage(@NonNull File imageFile,
                                  @Nullable String userQuestion,
                                  @Nullable String commandId,
                                  @Nullable OperationCallback callback) {
        mainHandler.post(() -> injectVisionImageOnMain(
                imageFile, userQuestion, commandId, callback));
    }

    private void injectVisionImageOnMain(@NonNull File imageFile,
                                         @Nullable String userQuestion,
                                         @Nullable String commandId,
                                         @Nullable OperationCallback callback) {
        GuideApiClient.RtcSessionInfo currentSession = rtcSession;
        if (currentSession == null || (state != State.READY && state != State.PAUSED
                && state != State.LISTENING)) {
            dispatchOperation(callback, false, "RTC 对话尚未就绪");
            return;
        }
        if (!currentSession.photoEnabled) {
            dispatchOperation(callback, false, "当前场馆未开启拍照识别");
            return;
        }
        if (!visionOperationInProgress
                || !sameCommandId(activeVisionCommandId, commandId)) {
            dispatchOperation(callback, false, "识图任务已取消，请重新拍照");
            return;
        }
        if (activeVisionCallback != null) {
            dispatchOperation(callback, false, "已有照片正在识别，请稍候");
            return;
        }

        int operationId = visionOperationId;
        int operationGeneration = generation;
        activeVisionCallback = callback;
        publishVisionOperation(true, "照片正在交给 AI 讲解…");
        ioExecutor.execute(() -> {
            GuideApiClient.UploadedImage uploaded = apiClient.uploadImage(imageFile);
            if (uploaded == null) {
                finishVisionOperation(operationId, callback, false, "照片上传失败");
                return;
            }
            if (operationId != visionOperationId || operationGeneration != generation
                    || rtcSession != currentSession
                    || !sameCommandId(activeVisionCommandId, commandId)) {
                finishVisionOperation(operationId, callback, false,
                        "RTC 会话已变化，请重新拍照");
                return;
            }
            String question = userQuestion == null || userQuestion.trim().isEmpty()
                    ? "请介绍这张照片中的展品或产品"
                    : userQuestion.trim();
            String prompt = "[VISION_IMAGE] 用户的问题：" + question
                    + "。眼镜刚拍摄的 image_url 是 " + uploaded.url
                    + "。请查看图片并直接用中文讲解，不要猜测未看见的内容。";
            boolean injected = apiClient.injectRtcMessage(
                    currentSession.roomId, currentSession.taskId, prompt);
            finishVisionOperation(operationId, callback, injected,
                    injected ? "照片已交给 AI，正在讲解" : "照片注入 AI 失败");
        });
    }

    private void publishVisionOperation(boolean inProgress, String message) {
        visionOperationInProgress = inProgress;
        for (Listener listener : listeners) {
            listener.onVisionOperationChanged(inProgress, message);
        }
    }

    private void finishVisionOperation(int operationId,
                                       @Nullable OperationCallback callback,
                                       boolean success, String message) {
        mainHandler.post(() -> {
            if (operationId != visionOperationId) return;
            String completedCommandId = activeVisionCommandId;
            activeVisionCallback = null;
            activeVisionCommandId = null;
            if (completedCommandId != null) {
                PendingVisionRequest pending = pendingVisionRequest;
                if (success) handledCommandIds.add(completedCommandId);
                if (pending != null && completedCommandId.equals(pending.commandId)) {
                    // 成功才记幂等；失败则清 pending，允许服务端用同 command_id 重发。
                    pendingVisionRequest = null;
                }
            }
            publishVisionOperation(false, message);
            if (callback != null) callback.onComplete(success, message);
            deliverPendingVisionRequest();
        });
    }

    private void cancelVisionOperationOnMain(String message) {
        OperationCallback callback = activeVisionCallback;
        String commandId = activeVisionCommandId;
        activeVisionCallback = null;
        activeVisionCommandId = null;
        visionOperationId++;
        apiClient.cancelVisionCalls();
        if (commandId != null && pendingVisionRequest != null
                && commandId.equals(pendingVisionRequest.commandId)) {
            pendingVisionRequest = null;
        }
        if (!visionOperationInProgress && callback == null) return;
        publishVisionOperation(false, message);
        if (callback != null) callback.onComplete(false, message);
        deliverPendingVisionRequest();
    }

    private void dispatchOperation(@Nullable OperationCallback callback,
                                   boolean success, String message) {
        if (callback != null) mainHandler.post(() -> callback.onComplete(success, message));
    }

    private void stopServerSessionAsync(GuideApiClient.RtcSessionInfo session) {
        stopExecutor.execute(() -> {
            for (int attempt = 1; attempt <= 3; attempt++) {
                if (apiClient.stopRtcSession(session.roomId, session.taskId)) {
                    return;
                }
                if (attempt < 3) {
                    try {
                        Thread.sleep(250L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            Log.e(TAG, "后端 VoiceChat 停止未确认，等待服务端 IdleTimeout 兜底: room="
                    + session.roomId + " task=" + session.taskId);
        });
    }

    /** 不可恢复错误不属于“暂停”：立即释放坏房间与 Agent，避免空转计费。 */
    private void terminateRtcOnError(RtcVoiceChatManager expectedRtc, String message) {
        if (rtc != expectedRtc) return;
        ++generation;
        audioStartAttempt++;
        rtcReadyAttempt++;
        updateState(State.ERROR, message);
        cancelVisionOperationOnMain("RTC 已不可用，识图任务已取消");
        pendingVisionRequest = null;
        unregisterBleListener();

        expectedRtc.setInputEnabled(false);
        glassesAudioSource.stop();
        rtc = null;
        expectedRtc.stop();

        GuideApiClient.RtcSessionInfo failedSession = rtcSession;
        rtcSession = null;
        rtcRoomJoined = false;
        agentOnline = false;
        if (failedSession != null) {
            stopServerSessionAsync(failedSession);
        }
    }

    private void registerBleListener() {
        if (bleListenerRegistered) return;
        BleService.getInstance().addListener(bleListener);
        bleListenerRegistered = true;
    }

    private void unregisterBleListener() {
        if (!bleListenerRegistered) return;
        BleService.getInstance().removeListener(bleListener);
        bleListenerRegistered = false;
    }

    private void updateState(State nextState, String message) {
        state = nextState;
        stateMessage = message;
        for (Listener listener : listeners) {
            listener.onStateChanged(nextState, message);
        }
        if (nextState == State.READY || nextState == State.PAUSED
                || nextState == State.LISTENING) {
            deliverPendingVisionRequest();
        }
    }

    private boolean isExpectedAgent(@Nullable String uid) {
        GuideApiClient.RtcSessionInfo current = rtcSession;
        if (current == null || uid == null || uid.trim().isEmpty()) return false;
        if (current.botUid == null || current.botUid.trim().isEmpty()) {
            // 兼容旧后端未返回 bot_uid 的情况；VoiceChat 房间只有本端与 Agent。
            return !uid.equals(current.uid);
        }
        return uid.equals(current.botUid);
    }

    private void publishReadyIfComplete() {
        GuideApiClient.RtcSessionInfo current = rtcSession;
        if (!rtcRoomJoined || current == null) return;
        if (!current.mocked && !agentOnline) {
            if (state == State.RTC_CONNECTING) {
                updateState(State.RTC_CONNECTING, "已进入 RTC 房间，正在等待 AI 导览员…");
            }
            return;
        }
        // 重复/迟到的 room 或 bot 回调只更新 flags，不能把 LISTENING、拍照暂停
        // 或 ERROR 覆盖回 READY，更不能让 PCM gate 与 UI 状态失配。
        if (state != State.RTC_CONNECTING) return;
        rtcReadyAttempt++;
        updateState(State.READY, current.mocked
                ? "当前为 RTC 模拟模式，AI 导览员不会响应"
                : "AI 导览员已就绪 · 点击开始语音导览");
    }

    private void scheduleRtcReadyTimeout(int expectedGeneration,
                                         RtcVoiceChatManager expectedRtc,
                                         String timeoutMessage) {
        int attempt = ++rtcReadyAttempt;
        mainHandler.postDelayed(() -> {
            if (attempt != rtcReadyAttempt || expectedGeneration != generation
                    || rtc != expectedRtc || state != State.RTC_CONNECTING) {
                return;
            }
            terminateRtcOnError(expectedRtc, timeoutMessage);
        }, RTC_READY_TIMEOUT_MS);
    }

    @Nullable
    private TranscriptEntry recordTranscript(boolean fromSelf, String text,
                                             boolean definite, long sequence) {
        String key = (fromSelf ? "self:" : "agent:") + sequence;
        String contentKey = (fromSelf ? "self:\u0000" : "agent:\u0000") + text;
        long now = SystemClock.elapsedRealtime();
        synchronized (transcriptLock) {
            // AIGC 字幕可能同时从 SDK subtitle callback 与 subv 二进制消息到达，
            // 两条链路的 sequence 不同。短时间内同说话人、同文本应归并到第一条，
            // 但窗口外仍允许用户真实地重复说同一句话。
            RecentSubtitle recent = recentSubtitlesByContent.get(contentKey);
            if (recent != null
                    && now - recent.seenElapsedMs <= SUBTITLE_CROSS_CHANNEL_DEDUP_MS) {
                TranscriptEntry canonical = transcript.get(recent.transcriptKey);
                recent.seenElapsedMs = now;
                if (canonical != null) {
                    if (canonical.definite) return null;
                    if (!definite) return null;
                    TranscriptEntry finalized = new TranscriptEntry(
                            canonical.fromSelf, canonical.text, true, canonical.sequence);
                    transcript.put(recent.transcriptKey, finalized);
                    return finalized;
                }
            }

            TranscriptEntry previous = transcript.get(key);
            if (previous != null) {
                // final 后忽略 SDK 的重复包或迟到 interim，避免重复气泡与文本回退。
                if (previous.definite) return null;
                if (previous.text.equals(text) && previous.definite == definite) return null;
            }
            TranscriptEntry recorded = new TranscriptEntry(fromSelf, text, definite, sequence);
            transcript.put(key, recorded);
            recentSubtitlesByContent.put(contentKey, new RecentSubtitle(key, now));
            return recorded;
        }
    }

    /**
     * 服务端意图识别后的 RTC 控制协议：
     * {"type":"capture_view","command_id":"uuid","question":"用户原问题"}
     */
    private void handleRtcCommand(@Nullable String senderUid, @Nullable String payload) {
        if (!isExpectedAgent(senderUid) || payload == null) return;
        try {
            JSONObject command = new JSONObject(payload);
            if (!"capture_view".equals(command.optString("type"))) return;
            String commandId = command.optString("command_id", "").trim();
            // 四套 RTC 文本消息回调在不同 SDK 版本可能重复触发；command_id 是
            // 幂等与服务端重试的必要条件，缺失时拒绝执行而不是冒险拍两次。
            if (commandId.isEmpty() || handledCommandIds.contains(commandId)) return;
            if (pendingVisionRequest != null) {
                if (commandId.equals(pendingVisionRequest.commandId)) {
                    deliverPendingVisionRequest();
                } else {
                    Log.w(TAG, "已有待处理识图指令，暂不覆盖: " + commandId);
                }
                return;
            }

            String question = command.optString("question", "").trim();
            if (question.isEmpty()) question = "请介绍我眼前的展品或产品";
            pendingVisionRequest = new PendingVisionRequest(commandId, question);
            deliverPendingVisionRequest();
        } catch (Exception parseError) {
            Log.w(TAG, "忽略无法解析的 RTC 控制消息", parseError);
        }
    }

    /**
     * 处理火山 client-side FC 指令。
     * 阶段1（当前）：仅对 take_photo 做假结果回填，验证 FC 通路（收 tool → 回填 func → 火山播报）。
     * 阶段2a 再改为：触发真实拍照 + describe-image + 回填识图结果。
     */
    private void handleFunctionCall(RtcVoiceChatManager rtc, String senderUid,
                                    String toolCallId, String functionName) {
        if (!"take_photo".equals(functionName)) {
            Log.w(TAG, "收到未知 FC: " + functionName);
            return;
        }
        GuideApiClient.RtcSessionInfo currentSession = rtcSession;
        if (currentSession == null || rtc == null) {
            Log.w(TAG, "FC take_photo 跳过：会话未就绪");
            return;
        }
        // 阶段1 假回填：先不真拍照，验证 func 回填能否让火山用同一把声音讲出来。
        String fakeResult = "（联调假数据）眼前这件是一尊青铜方鼎，方口、四足、腹部有兽面纹，"
                + "是商周时期祭祀用的礼器。请用讲解员的口吻向游客介绍它。";
        String botUid = currentSession.botUid != null && !currentSession.botUid.isEmpty()
                ? currentSession.botUid : senderUid;
        rtc.sendFunctionResult(botUid, toolCallId, fakeResult);
        Log.i(TAG, "FC take_photo 已回填假结果 id=" + toolCallId);
    }

    private void deliverPendingVisionRequest() {
        PendingVisionRequest pending = pendingVisionRequest;
        if (pending == null || visionOperationInProgress) return;
        if (SystemClock.elapsedRealtime() - pending.createdElapsedMs > VISION_COMMAND_TTL_MS) {
            pendingVisionRequest = null;
            Log.w(TAG, "识图指令等待超时，允许服务端使用同 command_id 重试");
            return;
        }
        if (state != State.READY && state != State.PAUSED && state != State.LISTENING) return;
        for (Listener listener : listeners) {
            if (listener.onVisionCaptureRequested(pending.commandId, pending.question)) {
                if (!visionOperationInProgress
                        || !pending.commandId.equals(activeVisionCommandId)) {
                    Log.e(TAG, "识图消费者返回已接单，但没有预留 manager 任务");
                }
                return;
            }
        }
    }

    /** 每个 RTC 实例绑定自己的 generation，旧房间的迟到回调不能影响新房间。 */
    private RtcVoiceChatManager.Listener createRtcListener(
            int rtcGeneration, RtcVoiceChatManager expectedRtc) {
        return new RtcVoiceChatManager.Listener() {
            private boolean hasJoinedRoom;

            private void postIfCurrent(Runnable action) {
                mainHandler.post(() -> {
                    if (rtcGeneration != generation || rtc != expectedRtc) return;
                    action.run();
                });
            }

            @Override
            public void onRoomJoined(boolean success, String reason) {
                postIfCurrent(() -> {
                    if (success) {
                        hasJoinedRoom = true;
                        rtcRoomJoined = true;
                        publishReadyIfComplete();
                        return;
                    }

                    if (state != State.RTC_CONNECTING) return;
                    terminateRtcOnError(expectedRtc,
                            "RTC 进房失败：" + (reason == null ? "未知错误" : reason));
                });
            }

            @Override
            public void onRoomInterrupted(boolean recoverable, String reason) {
                postIfCurrent(() -> {
                    if (state == State.IDLE || state == State.STOPPING || state == State.ERROR) {
                        return;
                    }
                    audioStartAttempt++;
                    expectedRtc.setInputEnabled(false);
                    glassesAudioSource.pause();
                    rtcRoomJoined = false;
                    if (recoverable && hasJoinedRoom) {
                        updateState(State.RTC_CONNECTING,
                                "RTC 连接暂时中断，正在保持房间并重连…");
                        cancelVisionOperationOnMain("RTC 正在重连，识图任务已取消");
                        scheduleRtcReadyTimeout(rtcGeneration, expectedRtc,
                                "RTC 重连超时，当前房间已停止；点击重试");
                        return;
                    }

                    terminateRtcOnError(expectedRtc,
                            "RTC 房间连接失败：" + (reason == null ? "未知错误" : reason));
                });
            }

            @Override
            public void onTokenWillExpire() {
                postIfCurrent(() -> {
                    terminateRtcOnError(expectedRtc,
                            "RTC 凭证即将过期，当前房间已停止；点击重试");
                });
            }

            @Override
            public void onAgentJoined(String uid) {
                postIfCurrent(() -> {
                    if (!isExpectedAgent(uid)) return;
                    agentOnline = true;
                    publishReadyIfComplete();
                });
            }

            @Override
            public void onUserLeave(String uid) {
                postIfCurrent(() -> {
                    if (!isExpectedAgent(uid)) return;
                    agentOnline = false;
                    audioStartAttempt++;
                    expectedRtc.setInputEnabled(false);
                    glassesAudioSource.pause();
                    if (state != State.IDLE && state != State.STOPPING && state != State.ERROR) {
                        updateState(State.RTC_CONNECTING,
                                "AI 导览员暂时离线，房间仍保持并等待重连…");
                        cancelVisionOperationOnMain("AI 导览员暂时离线，识图任务已取消");
                        scheduleRtcReadyTimeout(rtcGeneration, expectedRtc,
                                "AI 导览员重连超时，当前房间已停止；点击重试");
                    }
                });
            }

            @Override
            public void onSubtitle(boolean fromSelf, String text,
                                   boolean definite, int sequence) {
                if (text == null || text.trim().isEmpty()) return;
                String normalized = text.trim();
                long stableSequence = ((long) rtcGeneration << 32)
                        | (sequence & 0xffffffffL);
                postIfCurrent(() -> {
                    TranscriptEntry recorded = recordTranscript(
                            fromSelf, normalized, definite, stableSequence);
                    if (recorded == null) return;
                    for (Listener listener : listeners) {
                        listener.onSubtitle(recorded.fromSelf, recorded.text,
                                recorded.definite, recorded.sequence);
                    }
                });
            }

            @Override
            public void onCommand(String senderUid, String payload) {
                postIfCurrent(() -> handleRtcCommand(senderUid, payload));
            }

            @Override
            public void onFunctionCall(String senderUid, String toolCallId, String functionName) {
                postIfCurrent(() -> handleFunctionCall(expectedRtc, senderUid, toolCallId, functionName));
            }

            @Override
            public void onError(int code, String description) {
                postIfCurrent(() -> {
                    terminateRtcOnError(expectedRtc,
                            "AI 导览连接异常（" + code + "）：" + description);
                });
            }
        };
    }
}
