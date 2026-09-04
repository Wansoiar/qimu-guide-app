package com.qimu.guide.service;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.moyoung.glasses.conn.CRPBleConnection;
import com.moyoung.glasses.conn.callback.CRPDeviceVolumeCallback;
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener;
import com.qimu.guide.QimuApplication;
import com.qimu.guide.provisioning.ProvisioningApi;
import com.qimu.guide.provisioning.ProvisioningStore;
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
    private static final long RTC_READY_TIMEOUT_MS = 20_000L;
    // 断线自动重连上限：超过后不再自动重连，进 ERROR 让用户手动点“重试”。
    private static final int RTC_AUTO_RECONNECT_MAX = 3;
    private static final long VISION_COMMAND_TTL_MS = 30_000L;
    private static final long MEDIA_AUDIO_RELEASE_GRACE_MS = 1_000L;
    private static final long SUBTITLE_CROSS_CHANNEL_DEDUP_MS = 1_500L;
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
        void onSubtitle(boolean fromSelf, String text, boolean definite, long sequence);
        default boolean onVisionCaptureRequested(String commandId) {
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
        final int roundId;
        final long createdElapsedMs;
        int attempts;

        PendingVisionRequest(String commandId, int roundId) {
            this.commandId = commandId;
            this.roundId = roundId;
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
    // 收音源：眼镜当标准蓝牙耳机走系统 SCO 全双工（外放时仍收音→可打断），
    // 替换旧的眼镜私有 BLE Translation 通道（已删除的 GlassesPcmAudioSource，外放时收不到音打不断）。
    private final ScoMicAudioSource glassesAudioSource =
            new ScoMicAudioSource(QimuApplication.getAppContext());

    private volatile State state = State.IDLE;
    private volatile String stateMessage = "尚未开始游览";
    private volatile RtcVoiceChatManager rtc;

    private volatile int generation;
    private volatile String tourSessionId;
    private String transcriptTourSessionId;
    private TourSessionManager.TourSession tourSession;
    private volatile GuideApiClient.RtcSessionInfo rtcSession;
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
    // 断线重连复用同一次借阅：后端 rtc/session 返回的 session_id。同一 Tour 期间
    // 记住它，重连时回传后端 → 后端复用同一条 session 行 + 停旧 task 起新 task
    // （一次借阅贯穿，见 04-Session 改造方案 P0）。切换/结束 Tour 时清空。
    private String backendRtcSessionId;
    // 自动重连计数：RTC 断开（AI 退/重连超时）后自动重连，超上限才进 ERROR 让用户手动重试。
    private int rtcAutoReconnectAttempt;
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
            // 换了不同的 Tour（新借阅）：清后端 session 复用指针 + 自动重连计数，
            // 避免新借阅误用上一次借阅的后端 session_id。
            backendRtcSessionId = null;
            rtcAutoReconnectAttempt = 0;
        }
        registerBleListener();
        updateState(State.RTC_CONNECTING, "正在准备齐目 AI…");

        // 断线重连复用同一次借阅：若本 Tour 已在后端建过 rtc session，回传其 id
        // 让后端复用同一条 session 行（停旧 task 起新 task）；首次为 null 由后端新建。
        final String reuseSessionId = backendRtcSessionId;
        final String[] devIds = deviceIdsForSession();
        ioExecutor.execute(() -> {
            GuideApiClient.RtcSessionInfo created =
                    apiClient.createRtcSession(session.venueId, reuseSessionId, devIds[0], devIds[1]);
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
            updateState(State.ERROR, "齐目 AI 暂时不可用，请重试");
            return;
        }

        rtcSession = created;
        // 记住后端 session_id，供本次借阅内断线重连复用（后端据此复用同一 session 行）。
        if (created.sessionId != null && !created.sessionId.isEmpty()) {
            backendRtcSessionId = created.sessionId;
        }
        RtcVoiceChatManager manager = new RtcVoiceChatManager(QimuApplication.getAppContext());
        rtc = manager;
        updateState(State.RTC_CONNECTING,
                created.mocked ? "当前为 RTC 模拟模式，齐目 AI 不会响应" : "正在连接齐目 AI…");
        manager.start(created, createRtcListener(requestGeneration, manager));
        scheduleRtcReadyTimeout(requestGeneration, manager,
                "齐目 AI 连接超时，请重试");
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
            updateState(State.RTC_CONNECTING, "正在连接齐目 AI…");
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
            updateState(State.ERROR, "齐目 AI 暂时不可用，请重试");
            return;
        }

        int startGeneration = generation;
        int startAttempt = ++audioStartAttempt;
        currentRtc.setInputEnabled(false);
        updateState(State.AUDIO_LINK_STARTING, "正在连接眼镜麦克风…");
        glassesAudioSource.start(QimuApplication.getAppContext(), new ScoMicAudioSource.Listener() {
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
                    // SCO 已 connected（call mode 就绪）→ 通知 SDK 走蓝牙路由，
                    // 避免 SDK 把内部播放 track 音量掐到 ~0.0075（近静音）。
                    // 必须在此处（SCO 起来后）调，进房时调会被系统路由覆盖。
                    joinedRtc.routeToBluetooth();
                    setGlassesVolumeMax();
                    updateState(State.LISTENING, "正在聆听，请直接说话");
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
            // 真正结束游览才清后端 session 复用指针；publishStopping=false 是重连前的
            // 临时释放，必须保留 backendRtcSessionId 供随后重连复用同一 session。
            backendRtcSessionId = null;
            rtcAutoReconnectAttempt = 0;
        }
        if (currentSession != null) stopServerSessionAsync(currentSession);
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

    /** 读本地设备标识 [眼镜MAC, 手机device_id]，供建会话时上报（设备口径对齐）。缺失返回 ["",""]。 */
    private String[] deviceIdsForSession() {
        try {
            ProvisioningApi.ProvisioningSnapshot snap =
                    ProvisioningStore.get(QimuApplication.getAppContext()).snapshot();
            if (snap != null) {
                String glasses = snap.glassesId == null ? "" : snap.glassesId;  // = glasses_mac
                String phone = snap.deviceId == null ? "" : snap.deviceId;      // = report device_id
                return new String[]{glasses, phone};
            }
        } catch (Exception e) {
            Log.w(TAG, "读取设备标识失败", e);
        }
        return new String[]{"", ""};
    }

    private void stopServerSessionAsync(GuideApiClient.RtcSessionInfo session) {
        stopExecutor.execute(() -> retryStopServerSession(session));
    }

    /**
     * 进程崩溃/被系统杀死前的兜底（尽力而为）：主线程 Looper 可能已不可用，
     * 无法走 {@link #stopForTour(String)} 的主线程队列，这里直接在调用线程读取当前
     * RTC 会话并发起后端停止，至多等待 {@link #EXIT_STOP_GRACE_MS}。
     * 只负责通知后端关闭 VoiceChat Agent，不做本地 UI/设备收尾（进程即将消亡）。
     */
    public void stopRtcSessionForExit(@Nullable String expectedTourSessionId) {
        GuideApiClient.RtcSessionInfo toStop;
        try {
            GuideApiClient.RtcSessionInfo current = rtcSession;
            if (current == null) return;
            String activeTourId = tourSessionId;
            if (expectedTourSessionId != null && activeTourId != null
                    && !expectedTourSessionId.equals(activeTourId)) {
                return;
            }
            toStop = current;
        } catch (RuntimeException e) {
            Log.w(TAG, "读取退出前的 RTC 会话失败", e);
            return;
        }

        final CountDownLatch done = new CountDownLatch(1);
        stopExecutor.execute(() -> {
            try {
                retryStopServerSession(toStop);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(EXIT_STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "退出兜底超时，后端停止请求仍在进行: room="
                        + toStop.roomId + " task=" + toStop.taskId);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void retryStopServerSession(GuideApiClient.RtcSessionInfo session) {
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
    }

    /** 不可恢复错误不属于“暂停”：立即释放坏房间与 Agent，避免空转计费。 */
    /**
     * 处理“可恢复”的 RTC 失败（AI 退房 / 重连超时）：优先自动重连（复用同一次借阅的
     * 后端 session_id），超过 {@link #RTC_AUTO_RECONNECT_MAX} 次才进 ERROR 让用户手动重试。
     * 上下文可断——重连起的是新 RTC task，AI 可不记得之前对话（P0 目标：保可用）。
     */
    private void handleRecoverableRtcFailure(RtcVoiceChatManager expectedRtc, String message) {
        if (rtc != expectedRtc) return;
        TourSessionManager.TourSession current = TourSessionManager.get().current();
        boolean canAuto = current != null
                && current.sessionId.equals(tourSessionId)
                && !TourReturnCoordinator.get().isInProgress()
                && rtcAutoReconnectAttempt < RTC_AUTO_RECONNECT_MAX;
        if (!canAuto) {
            terminateRtcOnError(expectedRtc, message);
            return;
        }
        rtcAutoReconnectAttempt++;
        Log.w(TAG, "RTC 断开，自动重连 " + rtcAutoReconnectAttempt + "/" + RTC_AUTO_RECONNECT_MAX
                + "：" + message);

        // 释放当前 RTC（停旧 SDK 引擎），但保持 RTC_CONNECTING、保留 backendRtcSessionId，
        // 让随后的 startForTourOnMain 复用同一后端 session。后端会停旧 task 起新 task。
        ++generation;
        audioStartAttempt++;
        rtcReadyAttempt++;
        cancelVisionOperationOnMain("RTC 正在重连，识图任务已取消");
        pendingVisionRequest = null;
        expectedRtc.setInputEnabled(false);
        glassesAudioSource.stop();
        rtc = null;
        expectedRtc.stop();
        rtcSession = null;
        rtcRoomJoined = false;
        agentOnline = false;
        updateState(State.RTC_CONNECTING, "连接中断，正在自动重连…");

        int attempt = ++rtcRetryAttempt;
        mainHandler.postDelayed(() -> {
            TourSessionManager.TourSession active = TourSessionManager.get().current();
            if (attempt != rtcRetryAttempt
                    || TourReturnCoordinator.get().isInProgress()
                    || active == null
                    || !active.sessionId.equals(tourSessionId)
                    || rtc != null || rtcSession != null) {
                return;
            }
            // 直接进入建房（此路径已释放旧 rtc，state=RTC_CONNECTING）。
            startForTourReconnect(active);
        }, 500L);
    }

    /** 自动重连专用：绕过 startForTourOnMain 的 IDLE/ERROR 前置校验，复用当前 tour 直接重建房间。 */
    private void startForTourReconnect(TourSessionManager.TourSession session) {
        if (rtc != null || rtcSession != null) return;
        int requestGeneration = ++generation;
        tourSession = session;
        tourSessionId = session.sessionId;
        rtcRoomJoined = false;
        agentOnline = false;
        audioStartAttempt++;
        rtcReadyAttempt++;
        registerBleListener();
        updateState(State.RTC_CONNECTING, "正在自动重连齐目 AI…");
        final String reuseSessionId = backendRtcSessionId;
        final String[] devIds = deviceIdsForSession();
        ioExecutor.execute(() -> {
            GuideApiClient.RtcSessionInfo created =
                    apiClient.createRtcSession(session.venueId, reuseSessionId, devIds[0], devIds[1]);
            mainHandler.post(() -> onRtcSessionCreated(requestGeneration, session, created));
        });
    }

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
                updateState(State.RTC_CONNECTING, "正在连接齐目 AI…");
            }
            return;
        }
        // 重复/迟到的 room 或 bot 回调只更新 flags，不能把 LISTENING、拍照暂停
        // 或 ERROR 覆盖回 READY，更不能让 PCM gate 与 UI 状态失配。
        if (state != State.RTC_CONNECTING) return;
        rtcReadyAttempt++;
        rtcAutoReconnectAttempt = 0;  // 成功连上，重置自动重连计数（下次断开可重新自动重连）
        updateState(State.READY, current.mocked
                ? "当前为 RTC 模拟模式，齐目 AI 不会响应"
                : "齐目 AI 已准备好，点击开始对话");
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
            // 连接/重连超时属可恢复失败：先自动重连，超上限才进 ERROR。
            handleRecoverableRtcFailure(expectedRtc, timeoutMessage);
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
                                "连接暂时中断，正在重试…");
                        cancelVisionOperationOnMain("连接正在恢复，拍照识别已取消");
                        scheduleRtcReadyTimeout(rtcGeneration, expectedRtc,
                                "连接超时，请重试");
                        return;
                    }

                    terminateRtcOnError(expectedRtc, "连接失败，请重试");
                });
            }

            @Override
            public void onTokenWillExpire() {
                postIfCurrent(() -> {
                    terminateRtcOnError(expectedRtc, "连接已过期，请重试");
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
                                "齐目 AI 暂时离线，正在重连…");
                        cancelVisionOperationOnMain("齐目 AI 暂时离线，拍照识别已取消");
                        scheduleRtcReadyTimeout(rtcGeneration, expectedRtc,
                                "齐目 AI 重连超时，请重试");
                    }
                });
            }

            @Override
            public void onSubtitle(boolean fromSelf, String text,
                                   boolean definite, int sequence) {
                if (text == null || text.trim().isEmpty()) return;
                String normalized = TranscriptDisplayPolicy.visibleText(fromSelf, text);
                if (normalized.isEmpty()) return;
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
            public void onFunctionCall(String senderUid, String toolCallId, String functionName,
                                       int roundId) {
                postIfCurrent(() -> handleFunctionCall(
                        expectedRtc, senderUid, toolCallId, functionName, roundId));
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
