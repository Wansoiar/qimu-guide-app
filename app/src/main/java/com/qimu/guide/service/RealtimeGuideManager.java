package com.qimu.guide.service;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.moyoung.glasses.conn.CRPBleConnection;
import com.moyoung.glasses.conn.callback.CRPDeviceVolumeCallback;
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener;
import com.qimu.guide.QimuApplication;
import com.qimu.guide.net.AppAuthInterceptor;
import com.qimu.guide.net.AppContextHeaders;
import com.qimu.guide.net.GuideApiClient;
import com.qimu.guide.net.TourSessionManager;

import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private static final long VISION_COMMAND_TTL_MS = 30_000L;
    private static final long MEDIA_AUDIO_RELEASE_GRACE_MS = 1_000L;
    // 崩溃兜底时等待后端停止请求发出/确认的最长时间，避免拖慢系统杀进程。
    private static final long EXIT_STOP_GRACE_MS = 1_500L;

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
        void onSubtitle(SubtitleTranscript.Entry entry);
        default boolean onVisionCaptureRequested(String commandId) {
            return false;
        }
        default void onVisionOperationChanged(boolean inProgress, String message) { }
    }

    public interface OperationCallback {
        void onComplete(boolean success, String message);
    }

    private static final class PendingVisionRequest {
        final String commandId;
        final int roundId;
        final long createdElapsedMs;
        int attempts;

        PendingVisionRequest(String commandId, int roundId) {
            this.commandId = commandId;
            this.roundId = roundId;
            this.createdElapsedMs = SystemClock.elapsedRealtime();
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
    private final ExecutorService recoveryExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tour-rtc-recovery");
        thread.setDaemon(true);
        return thread;
    });
    private final RtcRecoveryController recovery = new RtcRecoveryController();
    private State recoveryDisplayState = State.READY;
    private String recoveryDisplayMessage = "齐目 AI 已准备好，点击开始对话";
    private boolean hasConnectedInTour;
    private boolean quietAudioResume;
    private boolean networkConnected = true;
    private boolean requiresNewTour;
    private final RtcTokenRenewalController tokenRenewal = new RtcTokenRenewalController();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final SubtitleTranscript transcript = new SubtitleTranscript();
    private final Set<String> handledCommandIds = new HashSet<>();
    private final GuideApiClient apiClient = new GuideApiClient();
    // 收音源：眼镜当标准蓝牙耳机走系统 SCO 全双工（外放时仍收音→可打断），
    // 替换旧的眼镜私有 BLE Translation 通道（已删除的 GlassesPcmAudioSource，外放时收不到音打不断）。
    private final ScoMicAudioSource glassesAudioSource =
            new ScoMicAudioSource(QimuApplication.getAppContext());

    private volatile State state = State.IDLE;
    private volatile String stateMessage = "尚未开始游览";
    private volatile RtcVoiceChatManager rtc;

    private volatile int generation;
    private volatile String tourSessionId;
    // Also read by the crash path when the main Looper may no longer be usable.
    private volatile String endingTourSessionId;
    private String transcriptTourSessionId;
    private TourSessionManager.TourSession tourSession;
    private volatile GuideApiClient.RtcSessionInfo rtcSession;
    private boolean bleListenerRegistered;
    // 仅在主线程读写。RTC 进房和 VoiceChat Agent 进房是两个独立事件，
    // 二者都完成后才允许打开眼镜 PCM，避免用户第一句话发进“空房”。
    private boolean rtcRoomJoined;
    private boolean agentOnline;
    private volatile int audioStartAttempt;
    // 从导出点击开始到 Wi-Fi 传输收尾前始终拦截收音重启，
    // 覆盖 stopTranslation 后的 1 s 释放窗口。
    private boolean mediaTransferAudioHold;
    private volatile boolean visionOperationInProgress;
    private volatile int visionOperationId;
    private OperationCallback activeVisionCallback;
    private volatile String activeVisionCommandId;
    // 当前识图任务对应的语音触发轮次（火山 roundId，0=手动拍照/未知）。
    private volatile int activeVisionRoundId;
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
            listener.onStateChanged(getState(), getStateMessage());
            listener.onVisionOperationChanged(visionOperationInProgress,
                    visionOperationInProgress ? "照片正在交给 AI 讲解…" : "");
            deliverPendingVisionRequest();
        });
    }

    public void removeListener(@Nullable Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    public State getState() {
        if (quietAudioResume) return recoveryDisplayState;
        if (isRecovering() && !recovery.showIssue(SystemClock.elapsedRealtime())) return recoveryDisplayState;
        return state;
    }

    public String getStateMessage() {
        if (quietAudioResume) return recoveryDisplayMessage;
        if (isRecovering()) return recovery.showIssue(SystemClock.elapsedRealtime())
                ? recovery.issueMessage() : recoveryDisplayMessage;
        return stateMessage;
    }

    public boolean isRecovering() { return hasConnectedInTour && recovery.isRecovering(); }
    public boolean requiresTourRestart() { return requiresNewTour; }
    public boolean wantsAudioAfterRecovery() {
        return recovery.intent() == RtcRecoveryController.Intent.LISTENING;
    }

    public List<SubtitleTranscript.Entry> getTranscriptSnapshot() {
        return transcript.snapshot();
    }

    /** Camera availability depends on a live session, not a retired venue setting. */
    public boolean hasVisionSession() {
        return rtcSession != null && !recovery.isRecovering() && rtcRoomJoined
                && (rtcSession.mocked || agentOnline);
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

    /** 在真正占用眼镜 AIRecognition 前预留整条识图链路。仅主线程调用。 */
    public boolean reserveVisionCapture(@Nullable String commandId) {
        if (Looper.myLooper() != Looper.getMainLooper() || visionOperationInProgress
                || !hasVisionSession()) {
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
        activeVisionRoundId = 0;
        if (commandId != null && pendingVisionRequest != null
                && commandId.equals(pendingVisionRequest.commandId)) {
            activeVisionRoundId = pendingVisionRequest.roundId;
        }
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
        activeVisionRoundId = 0;
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
        TourSessionManager.TourSession activeTour = TourSessionManager.get().current();
        if (TourReturnCoordinator.get().isInProgress() || activeTour != session
                || session.sessionId.equals(endingTourSessionId)) {
            Log.w(TAG, "游览已失效或正在归还，拒绝创建 RTC 房间");
            return;
        }
        if (session.sessionId.equals(tourSessionId)
                && state != State.IDLE && state != State.ERROR) {
            return;
        }
        if (state == State.ERROR && rtcSession != null) {
            // A retained RTC identity must never fall back to an unfenced start.
            retryCurrentTour();
            return;
        }
        if (state != State.IDLE && state != State.ERROR) {
            Log.w(TAG, "已有 RTC 会话，拒绝覆盖: " + state);
            return;
        }

        int requestGeneration = ++generation;
        recovery.start();
        tokenRenewal.newCredentials();
        requiresNewTour = false;
        quietAudioResume = false;
        tourSession = session;
        tourSessionId = session.sessionId;
        rtcRoomJoined = false;
        agentOnline = false;
        audioStartAttempt++;
        if (!session.sessionId.equals(transcriptTourSessionId)) {
            transcriptTourSessionId = session.sessionId;
            transcript.clear();
            handledCommandIds.clear();
            hasConnectedInTour = false;
        }
        registerBleListener();
        updateState(State.RTC_CONNECTING, "正在准备齐目 AI…");

        // 全链路只有一个 session_id（/v1/session/start 返回，见 04-Session 改造方案）：
        // 带着它调 /v1/rtc/session 进房，venue/设备由后端自取。
        // This unfenced request is only used for an initial start with no prior RTC identity.
        requestInitialRtcSession(requestGeneration, session, 0);
    }

    private void requestInitialRtcSession(int requestGeneration,
                                          TourSessionManager.TourSession requestedTour, int attempt) {
        if (requestGeneration != generation || !currentTourAllowsRecovery()) return;
        final long callEpoch = apiClient.rtcCallEpoch();
        ioExecutor.execute(() -> {
            if (requestGeneration != generation) return;
            GuideApiClient.RtcResult result =
                    apiClient.createRtcSession(requestedTour.sessionId, null, callEpoch);
            mainHandler.post(() -> {
                if (requestGeneration != generation || !currentTourAllowsRecovery()) {
                    if (GuideApiClient.shouldCleanUpRtcResponse(result.session, rtcSession)) {
                        stopServerSessionAsync(result.session, requestedTour.sessionId, false);
                    }
                    return;
                }
                if (result.session == null && result.retryable() && attempt < 2) {
                    mainHandler.postDelayed(() -> requestInitialRtcSession(requestGeneration,
                            requestedTour, attempt + 1), 1_000L << attempt);
                    return;
                }
                if (result.session == null && result.identityRejected()) {
                    requiresNewTour = true;
                    failRecovery("当前导览连接已失效，请重新开始导览", false);
                    return;
                }
                onRtcSessionCreated(requestGeneration, requestedTour, result.session);
            });
        });
    }

    private void onRtcSessionCreated(int requestGeneration,
                                     TourSessionManager.TourSession requestedTour,
                                     @Nullable GuideApiClient.RtcSessionInfo created) {
        if (requestGeneration != generation
                || tourSession == null
                || !requestedTour.sessionId.equals(tourSessionId)
                || requestedTour.sessionId.equals(endingTourSessionId)
                || TourReturnCoordinator.get().isInProgress()
                || TourSessionManager.get().current() != requestedTour) {
            if (GuideApiClient.shouldCleanUpRtcResponse(created, rtcSession)) {
                stopServerSessionAsync(created, requestedTour.sessionId, false);
            }
            return;
        }
        if (created == null) {
            updateState(State.ERROR, AppAuthInterceptor.consumeAuthError()
                    ? "配置错误，请联系运维" : "齐目 AI 暂时不可用，请重试");
            return;
        }

        rtcSession = created;
        // 留存本会话的火山 room/task，供异常退出后「结束上次订单」停 RTC 用。
        TourSessionManager.get().rememberRtcIds(requestedTour.sessionId, created.roomId, created.taskId);
        RtcVoiceChatManager manager = new RtcVoiceChatManager(QimuApplication.getAppContext());
        rtc = manager;
        updateState(State.RTC_CONNECTING,
                created.mocked ? "当前为 RTC 模拟模式，齐目 AI 不会响应" : "正在连接齐目 AI…");
        recovery.interrupt(RtcRecoveryController.Cause.AGENT, RtcRecoveryController.Intent.READY,
                SystemClock.elapsedRealtime());
        recoveryDisplayState = state;
        recoveryDisplayMessage = stateMessage;
        manager.start(created, createRtcListener(requestGeneration, manager));
        scheduleRecoveryTimers();
        scheduleTokenRenewal();
    }

    /** App “开始语音导览/继续语音导览”。RTC 已在房内，仅开启眼镜麦克风链路。 */
    public void startGuidance() {
        mainHandler.post(this::startGuidanceOnMain);
    }

    private void startGuidanceOnMain() {
        if (recovery.isRecovering()) {
            recovery.setIntent(RtcRecoveryController.Intent.LISTENING);
            recoveryDisplayState = State.LISTENING;
            recoveryDisplayMessage = "正在聆听，请直接说话";
            publishDisplayState();
            return;
        }
        if (state != State.READY && state != State.PAUSED) return;
        if (mediaTransferAudioHold || BleService.getInstance().isMediaDownloadActive()) {
            pauseAudioStart("照片导出中，完成后可继续对话");
            return;
        }
        if (!hasAudioPermission()) {
            pauseAudioStart("收音权限不可用，请点击继续对话");
            return;
        }
        GuideApiClient.RtcSessionInfo currentSession = rtcSession;
        if (!rtcRoomJoined || currentSession == null
                || (!currentSession.mocked && !agentOnline)) {
            beginRtcRecovery(networkConnected ? RtcRecoveryController.Cause.AGENT
                    : RtcRecoveryController.Cause.NETWORK);
            return;
        }
        BleService bleService = BleService.getInstance();
        CRPBleConnection connection = bleService.getConnection();
        if (!bleService.isConnected() || connection == null) {
            pauseAudioStart("眼镜未连接，连接后可继续语音导览");
            return;
        }
        RtcVoiceChatManager currentRtc = rtc;
        if (currentRtc == null) {
            quietAudioResume = false;
            updateState(State.ERROR, "齐目 AI 暂时不可用，请重试");
            return;
        }

        int startGeneration = generation;
        int startAttempt = ++audioStartAttempt;
        recovery.setIntent(RtcRecoveryController.Intent.LISTENING);
        currentRtc.setInputEnabled(false);
        updateState(State.AUDIO_LINK_STARTING, "正在连接眼镜麦克风…");
        glassesAudioSource.start(QimuApplication.getAppContext(), new ScoMicAudioSource.Listener() {
            @Override
            public void onStarted() {
                mainHandler.post(() -> {
                    if (startGeneration != generation || startAttempt != audioStartAttempt
                            || state != State.AUDIO_LINK_STARTING || !currentTourAllowsRecovery()) {
                        // The invalidating action already stopped its source. A late callback
                        // must not pause the shared source belonging to a newer start attempt.
                        return;
                    }
                    RtcVoiceChatManager joinedRtc = rtc;
                    if (joinedRtc == null || !hasVisionSession()) {
                        pauseAudioStart("AI 导览连接暂时不可用");
                        return;
                    }
                    if (!hasAudioPermission() || !BleService.getInstance().isConnected()
                            || mediaTransferAudioHold || BleService.getInstance().isMediaDownloadActive()
                            || recovery.intent() == RtcRecoveryController.Intent.PAUSED) {
                        pauseAudioStart("收音已暂停，条件就绪后可继续对话");
                        return;
                    }
                    joinedRtc.setInputEnabled(true);
                    // SCO 已 connected（call mode 就绪）→ 通知 SDK 走蓝牙路由，
                    // 避免 SDK 把内部播放 track 音量掐到 ~0.0075（近静音）。
                    // 必须在此处（SCO 起来后）调，进房时调会被系统路由覆盖。
                    joinedRtc.routeToBluetooth();
                    setGlassesVolumeMax();
                    quietAudioResume = false;
                    recovery.setIntent(RtcRecoveryController.Intent.LISTENING);
                    updateState(State.LISTENING, "正在聆听，请直接说话");
                });
            }

            @Override
            public void onPcm(byte[] pcm) {
                RtcVoiceChatManager activeRtc = rtc;
                if (startGeneration == generation && startAttempt == audioStartAttempt
                        && state == State.LISTENING && activeRtc != null) {
                    activeRtc.pushExternalPcm(pcm);
                }
            }

            @Override
            public void onError(int errorCode, String message) {
                mainHandler.post(() -> {
                    if (startGeneration != generation || startAttempt != audioStartAttempt) return;
                    RtcVoiceChatManager joinedRtc = rtc;
                    if (joinedRtc != null) joinedRtc.setInputEnabled(false);
                    quietAudioResume = false;
                    recovery.setIntent(RtcRecoveryController.Intent.PAUSED);
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
            quietAudioResume = false;
            recovery.setIntent(RtcRecoveryController.Intent.PAUSED);
            updateState(State.PAUSED, "眼镜麦克风连接超时，点击继续重试");
        }, AUDIO_LINK_START_TIMEOUT_MS);
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(QimuApplication.getAppContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void pauseAudioStart(String message) {
        quietAudioResume = false;
        audioStartAttempt++;
        if (rtc != null) rtc.setInputEnabled(false);
        glassesAudioSource.pause();
        recovery.setIntent(RtcRecoveryController.Intent.PAUSED);
        updateState(State.PAUSED, message);
    }

    /** App “暂停收音”。仅停止眼镜音频；AI 导览员仍保持在线。 */
    public void pauseGuidance() {
        mainHandler.post(() -> pauseGuidanceOnMain("已暂停收音 · 点击继续对话即可恢复"));
    }

    /**
     * 照片导出会独占眼镜的高带宽任务通道。这里只释放眼镜收音任务和
     * RTC 输入 gate，不退 RTC 房间、不停服务端 Agent。导出完成后保持暂停，
     * 由用户回到对话页主动点“继续对话”。
     */
    public void suspendForMediaTransfer(@NonNull Runnable onAudioReleased) {
        mainHandler.post(() -> {
            boolean audioTaskMayBeActive = state == State.LISTENING
                    || state == State.AUDIO_LINK_STARTING || state == State.PAUSED || recovery.isRecovering();
            mediaTransferAudioHold = true;
            quietAudioResume = false;
            setPausedRecoveryIntent("已暂停收音 · 正在导出眼镜照片");
            audioStartAttempt++;
            RtcVoiceChatManager currentRtc = rtc;
            if (currentRtc != null) currentRtc.setInputEnabled(false);

            // pauseTranslation 只暂停 PCM，固件仍可能保留 aiTranslate 任务；
            // Wi-Fi FILE 传输前必须 stopTranslation 完整释放它。
            glassesAudioSource.stop();
            if (audioTaskMayBeActive) {
                if (!recovery.isRecovering()) updateState(State.PAUSED, "已暂停收音 · 正在导出眼镜照片");
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
            if (recovery.isRecovering() && recovery.intent() == RtcRecoveryController.Intent.PAUSED) {
                recoveryDisplayMessage = "已暂停收音 · 点击继续对话";
                publishDisplayState();
            }
            if (state == State.PAUSED) {
                updateState(State.PAUSED, "已暂停收音 · 点击继续对话");
            }
            deliverPendingVisionRequest();
        });
    }

    /** 眼镜设备音量范围（真机 queryDeviceVolume 实测 0-16）。 */
    private static final int GLASSES_VOLUME_MAX = 16;

    /**
     * 用眼镜 SDK 设置设备侧音量（sendDeviceVolume，范围 0-{@link #GLASSES_VOLUME_MAX}）。
     * 这是眼镜喇叭增益，独立于蓝牙链路。供「语音改音量」直接复用。
     *
     * <p>注：真机验证走 SCO 时设备音量本就已在最大 16/16，故它不是 SCO 下行偏小的瓶颈；
     * 此接口主要留给用户主动调节音量（含未来语音指令）。
     *
     * @param volume 目标音量；<0 表示拉到最大。自动 clamp 到 [0, 16]。
     */
    public void setGlassesVolume(int volume) {
        BleService bleService = BleService.getInstance();
        CRPBleConnection connection = bleService.getConnection();
        if (!bleService.isConnected() || connection == null) {
            Log.w(TAG, "setGlassesVolume 跳过：眼镜未连接");
            return;
        }
        int target = volume < 0 ? GLASSES_VOLUME_MAX
                : Math.max(0, Math.min(GLASSES_VOLUME_MAX, volume));
        try {
            connection.sendDeviceVolume(target);
            Log.i(TAG, "sendDeviceVolume(" + target + ") 已发");
        } catch (RuntimeException e) {
            Log.w(TAG, "sendDeviceVolume 失败", e);
        }
    }

    /** 查询眼镜当前设备音量，结果经回调返回（供 UI/语音改音量读当前值）。 */
    public void queryGlassesVolume(CRPDeviceVolumeCallback callback) {
        BleService bleService = BleService.getInstance();
        CRPBleConnection connection = bleService.getConnection();
        if (!bleService.isConnected() || connection == null) return;
        try {
            connection.queryDeviceVolume(callback);
        } catch (RuntimeException e) {
            Log.w(TAG, "queryDeviceVolume 失败", e);
        }
    }

    /** 把眼镜设备音量拉到最大。 */
    public void setGlassesVolumeMax() {
        setGlassesVolume(-1);
    }

    private void pauseGuidanceOnMain(String message) {
        if (setPausedRecoveryIntent(message)) return;
        if (state != State.LISTENING && state != State.AUDIO_LINK_STARTING) return;
        audioStartAttempt++;
        RtcVoiceChatManager currentRtc = rtc;
        if (currentRtc != null) currentRtc.setInputEnabled(false);
        glassesAudioSource.pause();
        quietAudioResume = false;
        recovery.setIntent(RtcRecoveryController.Intent.PAUSED);
        updateState(State.PAUSED, message);
    }

    private void pauseForGlassesDisconnect(String message) {
        if (setPausedRecoveryIntent(message)) {
            glassesAudioSource.stop();
            return;
        }
        if (state != State.LISTENING && state != State.AUDIO_LINK_STARTING) return;
        audioStartAttempt++;
        RtcVoiceChatManager currentRtc = rtc;
        if (currentRtc != null) currentRtc.setInputEnabled(false);
        glassesAudioSource.stop();
        quietAudioResume = false;
        recovery.setIntent(RtcRecoveryController.Intent.PAUSED);
        updateState(State.PAUSED, message);
    }

    private boolean setPausedRecoveryIntent(String message) {
        recovery.setIntent(RtcRecoveryController.Intent.PAUSED);
        if (!recovery.isRecovering()) return false;
        recoveryDisplayState = State.PAUSED;
        recoveryDisplayMessage = message;
        publishDisplayState();
        return true;
    }

    public void retryCurrentTour() {
        mainHandler.post(() -> {
            TourSessionManager.TourSession current = TourSessionManager.get().current();
            if (current == null || TourReturnCoordinator.get().isInProgress()) return;
            if (recovery.isRecovering()) return;
            if (requiresNewTour) {
                updateState(State.ERROR, "导览连接已失效，请到导出页结束后重新开始");
                return;
            }
            if (rtcSession == null) {
                if (!hasConnectedInTour) startForTourOnMain(current);
                else updateState(State.ERROR, "连接已失效，请重新开始导览");
                return;
            }
            recovery.retryExisting(recovery.intent(), SystemClock.elapsedRealtime());
            rejoinExistingRtc();
        });
    }

    /** 点击结束游览后应立即调用；先停眼镜音频，再退房并停止后端 Agent。 */
    public void stopForTour(@Nullable String expectedTourSessionId) {
        Map<String, String> stopHeaders = AppContextHeaders.dialogue();
        String endingId = expectedTourSessionId != null ? expectedTourSessionId : tourSessionId;
        if (endingId != null && (tourSessionId == null || endingId.equals(tourSessionId))) {
            endingTourSessionId = endingId;
            ++generation;
            apiClient.cancelRtcCalls();
        }
        mainHandler.post(() -> stopForTourOnMain(expectedTourSessionId, true, stopHeaders));
    }

    private void stopForTourOnMain(@Nullable String expectedTourSessionId,
                                   boolean publishStopping, Map<String, String> stopHeaders) {
        if (expectedTourSessionId != null && tourSessionId != null
                && !expectedTourSessionId.equals(tourSessionId)) {
            return;
        }
        if (state == State.IDLE && rtcSession == null && rtc == null && expectedTourSessionId == null) return;

        ++generation;
        audioStartAttempt++;
        recovery.stop();
        quietAudioResume = false;
        invalidateTokenRenewal();
        apiClient.cancelRtcCalls();
        if (publishStopping) updateState(State.STOPPING, "正在结束本次导览…");
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
        // tourSessionId 稍后置空，先捕获用于后端 stop（/v1/rtc/session/stop 带 session_id）。
        String stopSessionId = tourSessionId != null ? tourSessionId : expectedTourSessionId;
        rtcSession = null;
        tourSession = null;
        tourSessionId = null;
        pendingVisionRequest = null;
        mediaTransferAudioHold = false;
        rtcRoomJoined = false;
        agentOnline = false;
        if (publishStopping) {
            transcriptTourSessionId = null;
            transcript.clear();
            handledCommandIds.clear();
            hasConnectedInTour = false;
        }
        if (currentSession != null || stopSessionId != null) {
            stopServerSessionAsync(currentSession, stopSessionId, publishStopping, stopHeaders);
        }
        updateState(State.IDLE, "本次导览已结束");
    }

    /** 上传眼镜照片并让同一 RTC Agent 讲解（手动按钮与模型 take_photo 共用同一链路）。 */
    public void injectVisionImage(@NonNull File imageFile,
                                  @Nullable String commandId,
                                  @Nullable OperationCallback callback) {
        mainHandler.post(() -> injectVisionImageOnMain(
                imageFile, commandId, callback));
    }

    private void injectVisionImageOnMain(@NonNull File imageFile,
                                         @Nullable String commandId,
                                         @Nullable OperationCallback callback) {
        GuideApiClient.RtcSessionInfo currentSession = rtcSession;
        if (currentSession == null || (state != State.READY && state != State.PAUSED
                && state != State.LISTENING)) {
            dispatchOperation(callback, false, "RTC 对话尚未就绪");
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
        String uploadTourSessionId = tourSessionId;
        if (uploadTourSessionId == null || uploadTourSessionId.trim().isEmpty()) {
            finishVisionOperation(operationId, callback, false,
                    "导览会话已失效，请重新拍照");
            return;
        }
        // 照片/识图归属用 /v1/rtc/session 返回的 RTC 会话 id；导览会话 id 只作流程门槛
        // （本地联调时它是 App 生成的 mock UUID，后端不认，不能用于落照片回合）。
        String rtcSessionId = currentSession.sessionId;
        activeVisionCallback = callback;
        publishVisionOperation(true, "照片正在交给 AI 讲解…");
        ioExecutor.execute(() -> {
            GuideApiClient.UploadedImage uploaded = apiClient.uploadImage(
                    imageFile, rtcSessionId);
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
            // 统一识图链路：upload → describe-image（后端 CLIP 图搜 → 三态）→ 同一回填文案。
            // 手动按钮与模型 take_photo 只差“如何让模型讲出来”：
            // - FC（commandId=fc:<toolCallId>）：模型下发过工具调用 → func 回填继续讲解；
            // - 手动：没有模型下发的 toolCallId → 同一文案以文本注入对话（ExternalTextToLLM）。
            TourSessionManager.TourSession tour = tourSession;
            String venueId = tour != null ? tour.venueId : null;
            GuideApiClient.ImageDescribeResult desc =
                    apiClient.describeRtcImage(venueId, rtcSessionId, uploaded.url,
                            isFcCommand(commandId) ? activeVisionRoundId : 0);
            String content = buildVisionReplyContent(desc);

            boolean ok;
            if (isFcCommand(commandId)) {
                String botUid = activeFcBotUid;
                RtcVoiceChatManager currentRtc = rtc;
                ok = botUid != null && currentRtc != null;
                if (ok) {
                    currentRtc.sendFunctionResult(botUid, toolCallIdOf(commandId), content);
                }
            } else {
                ok = apiClient.injectRtcMessage(
                        currentSession.roomId, currentSession.taskId, content);
            }
            finishVisionOperation(operationId, callback, ok,
                    ok ? "照片已交给 AI，正在讲解" : "识图结果回填失败");
        });
    }

    /**
     * 按后端置信度三态拼给模型的回填文案（单一声音，不弹 UI）：
     * high_conf 直接讲 / ambiguous 引导确认 / 未匹配建议重拍。
     * FC func 回填与手动文本注入共用同一份。
     */
    private static String buildVisionReplyContent(
            @Nullable GuideApiClient.ImageDescribeResult desc) {
        boolean hasSummary = desc != null && desc.summary != null
                && !desc.summary.trim().isEmpty();
        if (desc != null && desc.isHighConf() && hasSummary) {
            // 高置信：已确定展品，直接口语化讲解。
            return "这是「" + desc.exhibitName + "」。以下是讲解资料，"
                    + "请用讲解员口吻面向游客口语化介绍：" + desc.summary;
        }
        if (desc != null && desc.isAmbiguous() && hasSummary) {
            // 待确认：识别到多个候选，引导用户确认是哪一件，不要硬挑一个讲。
            return "眼前这件有多个相似的候选展品，还不能确定是哪一件，先别急着讲解。"
                    + "请用讲解员口吻自然地把这些候选口语化地说给游客，"
                    + "并问他看的是哪一件，帮你确认后再讲。以下是候选信息：" + desc.summary;
        }
        // 未匹配（含 null/异常）→ 让模型引导用户重拍，保持单一声音。
        return "没有从本馆知识库里识别出这件展品。请用讲解员口吻告诉游客："
                + "暂时没认出眼前这件，建议靠近一点或换个角度再让我看看。";
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
            activeVisionRoundId = 0;
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
        activeVisionRoundId = 0;
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

    private void stopServerSessionAsync(@Nullable GuideApiClient.RtcSessionInfo session,
                                        @Nullable String sessionId, boolean endSession) {
        stopServerSessionAsync(session, sessionId, endSession, AppContextHeaders.dialogue());
    }

    private void stopServerSessionAsync(@Nullable GuideApiClient.RtcSessionInfo session,
                                        @Nullable String sessionId, boolean endSession,
                                        Map<String, String> headers) {
        stopExecutor.execute(() -> retryStopServerSession(session, sessionId, endSession, headers));
    }

    /**
     * 进程崩溃/被系统杀死前的兜底（尽力而为）：主线程 Looper 可能已不可用，
     * 无法走 {@link #stopForTour(String)} 的主线程队列，这里直接在调用线程读取当前
     * RTC 会话并发起后端停止，至多等待 {@link #EXIT_STOP_GRACE_MS}。
     * 只负责通知后端关闭 VoiceChat Agent，不做本地 UI/设备收尾（进程即将消亡）。
     */
    public void stopRtcSessionForExit(@Nullable String expectedTourSessionId) {
        GuideApiClient.RtcSessionInfo toStop;
        final String stopSessionId;
        final Map<String, String> stopHeaders = AppContextHeaders.dialogue();
        try {
            GuideApiClient.RtcSessionInfo current = rtcSession;
            String activeTourId = tourSessionId;
            if (expectedTourSessionId != null && activeTourId != null
                    && !expectedTourSessionId.equals(activeTourId)) {
                return;
            }
            toStop = current;
            stopSessionId = activeTourId != null ? activeTourId : expectedTourSessionId;
            if (stopSessionId == null && toStop == null) return;
            endingTourSessionId = stopSessionId;
            ++generation;
            apiClient.cancelRtcCalls();
            RtcVoiceChatManager activeRtc = rtc;
            if (activeRtc != null) activeRtc.setInputEnabled(false);
        } catch (RuntimeException e) {
            Log.w(TAG, "读取退出前的 RTC 会话失败", e);
            return;
        }

        final CountDownLatch done = new CountDownLatch(1);
        stopExecutor.execute(() -> {
            try {
                retryStopServerSession(toStop, stopSessionId, true, stopHeaders);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(EXIT_STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "退出兜底超时，后端停止请求仍在进行: room="
                        + (toStop == null ? "pending" : toStop.roomId));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void retryStopServerSession(@Nullable GuideApiClient.RtcSessionInfo session,
                                        @Nullable String sessionId, boolean endSession,
                                        Map<String, String> headers) {
        String roomId = session == null ? null : session.roomId;
        String taskId = session == null ? null : session.taskId;
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (apiClient.stopRtcSession(roomId, taskId, sessionId, endSession, headers)) {
                return;
            }
            if (AppAuthInterceptor.consumeAuthError()) {
                // 鉴权失败（X-App-Token 配置错误）重试无意义，直接放弃等后台兜底。
                Log.w(TAG, "停止 RTC 鉴权失败，中止自动重试: room=" + roomId);
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
                + roomId + " task=" + taskId);
    }

    private boolean currentTourAllowsRecovery() {
        TourSessionManager.TourSession active = TourSessionManager.get().current();
        return active != null && active == tourSession && active.sessionId.equals(tourSessionId)
                && !active.sessionId.equals(endingTourSessionId)
                && !TourReturnCoordinator.get().isInProgress();
    }

    private RtcRecoveryController.Intent currentAudioIntent() {
        if (state == State.LISTENING || state == State.AUDIO_LINK_STARTING || quietAudioResume) {
            return RtcRecoveryController.Intent.LISTENING;
        }
        return state == State.PAUSED ? RtcRecoveryController.Intent.PAUSED : RtcRecoveryController.Intent.READY;
    }

    private void beginRtcRecovery(RtcRecoveryController.Cause cause) {
        if (!currentTourAllowsRecovery() || state == State.IDLE || state == State.STOPPING
                || state == State.ERROR || rtcSession == null) return;
        State priorState = getState();
        String priorMessage = getStateMessage();
        boolean started = recovery.interrupt(cause, currentAudioIntent(), SystemClock.elapsedRealtime());
        if (started) {
            recoveryDisplayState = priorState == State.AUDIO_LINK_STARTING ? State.LISTENING : priorState;
            recoveryDisplayMessage = priorMessage;
            quietAudioResume = false;
            transcript.breakGroup();
            audioStartAttempt++;
            if (rtc != null) rtc.setInputEnabled(false);
            glassesAudioSource.pause();
            cancelVisionOperationOnMain("连接暂时中断，拍照识别已取消");
            pendingVisionRequest = null;
            scheduleRecoveryTimers();
        }
        updateState(State.RTC_CONNECTING, recovery.issueMessage());
    }

    private void scheduleRecoveryTimers() {
        long ticket = recovery.ticket();
        long now = SystemClock.elapsedRealtime();
        mainHandler.postDelayed(() -> {
            if (recovery.isCurrent(ticket) && currentTourAllowsRecovery()) publishDisplayState();
        }, recovery.issueDelay(now));
        if (recovery.phase() == RtcRecoveryController.Phase.REBUILDING) return;
        mainHandler.postDelayed(() -> {
            if (!currentTourAllowsRecovery()) return;
            RtcRecoveryController.Action action = recovery.onDeadline(ticket, SystemClock.elapsedRealtime());
            if (action == RtcRecoveryController.Action.REJOIN_EXISTING) rejoinExistingRtc();
            else if (action == RtcRecoveryController.Action.REBUILD_TASK) rebuildRtcTask();
            else if (action == RtcRecoveryController.Action.FAIL) {
                failRecovery("AI 导览服务暂时不可用，请重试", false);
            }
        }, Math.max(0, recovery.deadline() - now));
    }

    /** Local SDK teardown only. The old Agent and its room/task credentials stay alive. */
    private void rejoinExistingRtc() {
        if (!currentTourAllowsRecovery() || rtcSession == null || !recovery.isRecovering()) return;
        RtcVoiceChatManager old = rtc;
        rtc = null;
        ++generation;
        audioStartAttempt++;
        invalidateTokenRenewal();
        glassesAudioSource.pause();
        if (old != null) old.stop();
        rtcRoomJoined = false;
        agentOnline = false; // require presence from this join, never a cached Boolean
        RtcVoiceChatManager manager = new RtcVoiceChatManager(QimuApplication.getAppContext());
        rtc = manager;
        updateState(State.RTC_CONNECTING, recovery.issueMessage());
        manager.start(rtcSession, createRtcListener(generation, manager));
        scheduleRecoveryTimers();
        scheduleTokenRenewal();
    }

    private void rebuildRtcTask() {
        GuideApiClient.RtcSessionInfo previous = rtcSession;
        if (!currentTourAllowsRecovery() || previous == null) return;
        long ticket = recovery.ticket();
        int requestGeneration = ++generation;
        invalidateTokenRenewal();
        audioStartAttempt++;
        RtcVoiceChatManager old = rtc;
        rtc = null;
        if (old != null) old.stop();
        glassesAudioSource.pause();
        rtcRoomJoined = false;
        agentOnline = false;
        requestRtcRebuild(previous, ticket, requestGeneration, 0);
    }

    private void requestRtcRebuild(GuideApiClient.RtcSessionInfo previous, long ticket,
                                   int requestGeneration, int attempt) {
        if (!recovery.isCurrent(ticket) || requestGeneration != generation || !currentTourAllowsRecovery()) return;
        long callEpoch = apiClient.rtcCallEpoch();
        recoveryExecutor.execute(() -> {
            if (requestGeneration != generation) return;
            GuideApiClient.RtcResult result = apiClient.createRtcSession(previous.sessionId, previous, callEpoch);
            mainHandler.post(() -> {
                if (!recovery.isCurrent(ticket) || requestGeneration != generation || !currentTourAllowsRecovery()) {
                    // An idempotent response may name the task we already attached. Never stop it.
                    if (GuideApiClient.shouldCleanUpRtcResponse(result.session, rtcSession)) {
                        stopServerSessionAsync(result.session, previous.sessionId, false);
                    }
                    return;
                }
                if (result.session == null) {
                    if (result.retryable() && attempt < 2) {
                        mainHandler.postDelayed(() -> requestRtcRebuild(previous, ticket,
                                requestGeneration, attempt + 1), 1_000L << attempt);
                    } else {
                        requiresNewTour = result.identityRejected();
                        failRecovery(requiresNewTour ? "连接凭证已失效，请重新开始导览"
                                : "AI 导览服务暂时不可用，请重试", false);
                    }
                    return;
                }
                if (!recovery.rebuilt(ticket, SystemClock.elapsedRealtime())) return;
                rtcSession = result.session;
                tokenRenewal.newCredentials();
                TourSessionManager.get().rememberRtcIds(previous.sessionId,
                        result.session.roomId, result.session.taskId);
                rejoinExistingRtc();
            });
        });
    }

    private void invalidateTokenRenewal() {
        tokenRenewal.invalidate();
    }

    private void scheduleTokenRenewal() {
        GuideApiClient.RtcSessionInfo current = rtcSession;
        if (current == null || current.mocked || current.expireAt <= 0) return;
        int expectedGeneration = generation;
        long expectedAttempt = tokenRenewal.ticket();
        long delay = Math.max(0, current.expireAt * 1000L - System.currentTimeMillis() - 60_000L);
        mainHandler.postDelayed(() -> {
            if (expectedGeneration == generation && tokenRenewal.isCurrent(expectedAttempt)
                    && current.sameTask(rtcSession) && currentTourAllowsRecovery()) renewToken();
        }, delay);
    }

    private void renewToken() {
        GuideApiClient.RtcSessionInfo previous = rtcSession;
        if (previous == null || previous.mocked || !currentTourAllowsRecovery()
                || recovery.phase() == RtcRecoveryController.Phase.STOPPED
                || recovery.phase() == RtcRecoveryController.Phase.FAILED
                || recovery.phase() == RtcRecoveryController.Phase.REBUILDING) return;
        long ticket = tokenRenewal.begin();
        if (ticket >= 0) requestTokenRenewal(previous, generation, ticket, 0);
    }

    private void requestTokenRenewal(GuideApiClient.RtcSessionInfo previous, int expectedGeneration,
                                     long expectedAttempt, int attempt) {
        if (expectedGeneration != generation || !tokenRenewal.isCurrent(expectedAttempt)
                || !currentTourAllowsRecovery() || !previous.sameTask(rtcSession)) return;
        long callEpoch = apiClient.rtcCallEpoch();
        recoveryExecutor.execute(() -> {
            if (expectedGeneration != generation || !tokenRenewal.isCurrent(expectedAttempt)) return;
            GuideApiClient.RtcResult result = apiClient.renewRtcToken(previous, callEpoch);
            mainHandler.post(() -> {
                if (expectedGeneration != generation || !tokenRenewal.isCurrent(expectedAttempt)
                        || !currentTourAllowsRecovery() || !previous.sameTask(rtcSession)) return;
                if (result.session != null && result.session.expireAt > System.currentTimeMillis() / 1000L) {
                    tokenRenewal.newCredentials();
                    rtcSession = result.session;
                    RtcVoiceChatManager current = rtc;
                    if (current != null && current.updateToken(result.session.token) != 0) {
                        beginRtcRecovery(RtcRecoveryController.Cause.UNKNOWN);
                    }
                    scheduleTokenRenewal();
                    return;
                }
                if (result.retryable() && RtcTokenRenewalController.canRetryHttp(
                        attempt, System.currentTimeMillis() / 1000L, previous.expireAt)) {
                    mainHandler.postDelayed(() -> requestTokenRenewal(previous, expectedGeneration,
                            expectedAttempt, attempt + 1), 1_000L << attempt);
                    return;
                }
                if (result.identityRejected()) {
                    requiresNewTour = true;
                    failRecovery("连接凭证已失效，请重新开始导览", false);
                    return;
                }
                // Pre-expiry failure must not tear down a still-valid conversation.
                tokenRenewal.waitForExpiry(expectedAttempt);
                long delay = Math.max(0, previous.expireAt * 1000L - System.currentTimeMillis());
                mainHandler.postDelayed(() -> {
                    if (expectedGeneration != generation || !tokenRenewal.isCurrent(expectedAttempt)
                            || !currentTourAllowsRecovery() || !previous.sameTask(rtcSession)) return;
                    if (!result.retryable()) {
                        requiresNewTour = true;
                        failRecovery("连接续期暂不可用，请重新开始导览", false);
                    } else if (tokenRenewal.retryAtExpiry(expectedAttempt,
                            System.currentTimeMillis() / 1000L, previous.expireAt)) {
                        beginRtcRecovery(networkConnected ? RtcRecoveryController.Cause.UNKNOWN
                                : RtcRecoveryController.Cause.NETWORK);
                        renewToken(); // one bounded retry window after expiry (backend allows 120 seconds)
                    } else {
                        failRecovery("连接续期未完成，请重试或重新开始导览", false);
                    }
                }, delay);
            });
        });
    }

    private void failRecovery(String message, boolean stopAgent) {
        recovery.fail();
        quietAudioResume = false;
        ++generation;
        audioStartAttempt++;
        invalidateTokenRenewal();
        apiClient.cancelRtcCalls();
        RtcVoiceChatManager old = rtc;
        rtc = null;
        if (old != null) old.stop();
        glassesAudioSource.stop();
        rtcRoomJoined = false;
        agentOnline = false;
        cancelVisionOperationOnMain("连接不可用，拍照识别已取消");
        pendingVisionRequest = null;
        if (stopAgent && rtcSession != null) stopServerSessionAsync(rtcSession, tourSessionId, false);
        // Retain identity for explicit retry; never turn a recovery into an unfenced initial start.
        updateState(State.ERROR, message);
    }

    private void terminateRtcOnError(RtcVoiceChatManager expectedRtc, String message) {
        if (rtc != expectedRtc) return;
        requiresNewTour = true;
        failRecovery(message, true);
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

    private void publishDisplayState() {
        for (Listener listener : listeners) listener.onStateChanged(getState(), getStateMessage());
    }

    private void updateState(State nextState, String message) {
        state = nextState;
        stateMessage = message;
        publishDisplayState();
        if (!recovery.isRecovering() && (nextState == State.READY || nextState == State.PAUSED
                || nextState == State.LISTENING)) deliverPendingVisionRequest();
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
        if (!rtcRoomJoined || current == null || (!current.mocked && !agentOnline)
                || state != State.RTC_CONNECTING) return;
        boolean restoring = hasConnectedInTour && recovery.isRecovering();
        RtcRecoveryController.Intent intent = recovery.intent();
        recovery.connected();
        hasConnectedInTour = true;
        if (restoring && intent == RtcRecoveryController.Intent.PAUSED) {
            updateState(State.PAUSED, recoveryDisplayMessage);
        } else if (restoring && intent == RtcRecoveryController.Intent.LISTENING) {
            boolean permission = hasAudioPermission();
            boolean connected = BleService.getInstance().isConnected();
            boolean busy = mediaTransferAudioHold || BleService.getInstance().isMediaDownloadActive();
            if (recovery.shouldResumeAudio(permission, connected, busy)) {
                quietAudioResume = true;
                recoveryDisplayState = State.LISTENING;
                recoveryDisplayMessage = "正在聆听，请直接说话";
                state = State.READY;
                startGuidanceOnMain();
            } else {
                recovery.setIntent(RtcRecoveryController.Intent.PAUSED);
                updateState(State.PAUSED, !permission ? "收音权限不可用，请点击继续对话"
                        : !connected ? "眼镜未连接，连接后可继续对话"
                        : "已暂停收音 · 照片导出完成后可继续对话");
            }
        } else {
            updateState(State.READY, current.mocked ? "当前为 RTC 模拟模式，齐目 AI 不会响应"
                    : "齐目 AI 已准备好，点击开始对话");
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

            pendingVisionRequest = new PendingVisionRequest(commandId, 0);
            deliverPendingVisionRequest();
        } catch (Exception parseError) {
            Log.w(TAG, "忽略无法解析的 RTC 控制消息", parseError);
        }
    }

    /** FC 触发拍照的 commandId 前缀，用于在 injectVisionImageOnMain 区分回填方式（func vs inject）。 */
    private static final String FC_COMMAND_PREFIX = "fc:";

    /** 记录 FC commandId → botUid / toolCallId，供拍照完成后 func 回填使用。 */
    private volatile String activeFcBotUid;

    /**
     * 处理火山 client-side FC 指令（阶段2a）。
     * take_photo → 触发真实拍照（复用 pendingVisionRequest 机制，commandId=fc:<toolCallId>），
     * 拍照+upload+describe-image 后走 func 回填（见 injectVisionImageOnMain 的 FC 分支）。
     */
    private void handleFunctionCall(RtcVoiceChatManager rtc, String senderUid,
                                    String toolCallId, String functionName, int roundId) {
        if (!"take_photo".equals(functionName)) {
            Log.w(TAG, "收到未知 FC: " + functionName);
            return;
        }
        GuideApiClient.RtcSessionInfo currentSession = rtcSession;
        if (currentSession == null || rtc == null) {
            Log.w(TAG, "FC take_photo 跳过：会话未就绪");
            return;
        }
        if (pendingVisionRequest != null || visionOperationInProgress) {
            Log.w(TAG, "FC take_photo 跳过：已有识图任务进行中");
            return;
        }
        activeFcBotUid = currentSession.botUid != null && !currentSession.botUid.isEmpty()
                ? currentSession.botUid : senderUid;
        String commandId = FC_COMMAND_PREFIX + toolCallId;
        Log.i(TAG, "FC take_photo → 触发拍照 commandId=" + commandId);
        pendingVisionRequest = new PendingVisionRequest(commandId, roundId);
        deliverPendingVisionRequest();
    }

    private static boolean isFcCommand(@Nullable String commandId) {
        return commandId != null && commandId.startsWith(FC_COMMAND_PREFIX);
    }

    private static String toolCallIdOf(String fcCommandId) {
        return fcCommandId.substring(FC_COMMAND_PREFIX.length());
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
            if (listener.onVisionCaptureRequested(pending.commandId)) {
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
            private void postIfCurrent(Runnable action) {
                mainHandler.post(() -> {
                    if (rtcGeneration != generation || rtc != expectedRtc || !currentTourAllowsRecovery()) return;
                    action.run();
                });
            }

            @Override
            public void onRoomJoined(boolean success, String reason) {
                postIfCurrent(() -> {
                    if (success) {
                        rtcRoomJoined = true;
                        networkConnected = true;
                        if (!agentOnline) recovery.setCause(RtcRecoveryController.Cause.AGENT);
                        publishReadyIfComplete();
                        if (recovery.isRecovering()) publishDisplayState();
                    } else {
                        rtcRoomJoined = false;
                        agentOnline = false;
                        beginRtcRecovery(networkConnected ? RtcRecoveryController.Cause.UNKNOWN
                                : RtcRecoveryController.Cause.NETWORK);
                    }
                });
            }

            @Override
            public void onNetworkStateChanged(boolean connected) {
                postIfCurrent(() -> {
                    networkConnected = connected;
                    if (!connected) {
                        rtcRoomJoined = false;
                        agentOnline = false;
                        beginRtcRecovery(RtcRecoveryController.Cause.NETWORK);
                    } else if (recovery.isRecovering()) {
                        recovery.setCause(rtcRoomJoined && !agentOnline
                                ? RtcRecoveryController.Cause.AGENT : RtcRecoveryController.Cause.UNKNOWN);
                        publishDisplayState();
                    }
                });
            }

            @Override
            public void onRoomInterrupted(boolean recoverable, String reason) {
                postIfCurrent(() -> {
                    if (!recoverable) {
                        terminateRtcOnError(expectedRtc, "连接不可用，请重新开始导览");
                        return;
                    }
                    rtcRoomJoined = false;
                    agentOnline = false;
                    beginRtcRecovery("RECONNECT".equals(reason) || !networkConnected
                            ? RtcRecoveryController.Cause.NETWORK : RtcRecoveryController.Cause.UNKNOWN);
                });
            }

            @Override
            public void onTokenWillExpire() { postIfCurrent(RealtimeGuideManager.this::renewToken); }

            @Override
            public void onTokenRejected() {
                postIfCurrent(() -> {
                    rtcRoomJoined = false;
                    agentOnline = false;
                    beginRtcRecovery(RtcRecoveryController.Cause.UNKNOWN);
                    renewToken();
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
                    beginRtcRecovery(networkConnected ? RtcRecoveryController.Cause.AGENT
                            : RtcRecoveryController.Cause.NETWORK);
                });
            }

            @Override
            public void onSubtitle(boolean fromSelf, String text,
                                   boolean definite, int sequence, int roundId,
                                   SubtitleTranscript.Source source) {
                if (text == null || text.trim().isEmpty()) return;
                String normalized = TranscriptDisplayPolicy.visibleText(fromSelf, text);
                if (normalized.isEmpty()) return;
                long receivedElapsedMs = SystemClock.elapsedRealtime();
                long timestamp = System.currentTimeMillis();
                postIfCurrent(() -> {
                    SubtitleTranscript.Entry recorded = transcript.record(
                            rtcGeneration, fromSelf, normalized, definite, sequence, roundId,
                            source, receivedElapsedMs, timestamp);
                    if (recorded == null) return;
                    for (Listener listener : listeners) {
                        listener.onSubtitle(recorded);
                    }
                });
            }

            @Override
            public void onCommand(String senderUid, String payload) {
                postIfCurrent(() -> handleRtcCommand(senderUid, payload));
            }

            @Override
            public void onFunctionCall(String senderUid, String toolCallId, String functionName,
                                       int roundId) {
                postIfCurrent(() -> handleFunctionCall(
                        expectedRtc, senderUid, toolCallId, functionName, roundId));
            }

            @Override
            public void onError(int code, String description) {
                postIfCurrent(() -> {
                    if (RtcFailurePolicy.isTokenError(code)) {
                        rtcRoomJoined = false;
                        agentOnline = false;
                        beginRtcRecovery(RtcRecoveryController.Cause.UNKNOWN);
                        renewToken();
                    } else if (RtcFailurePolicy.isPermanent(code) || (code <= -100 && code >= -102)) {
                        terminateRtcOnError(expectedRtc, "AI 导览配置不可用，请联系工作人员");
                    } else {
                        rtcRoomJoined = false;
                        agentOnline = false;
                        beginRtcRecovery(networkConnected ? RtcRecoveryController.Cause.UNKNOWN
                                : RtcRecoveryController.Cause.NETWORK);
                    }
                });
            }
        };
    }
}
