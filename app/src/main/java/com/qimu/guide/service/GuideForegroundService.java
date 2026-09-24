package com.qimu.guide.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.qimu.guide.MainActivity;
import com.qimu.guide.QimuApplication;
import com.qimu.guide.R;
import com.qimu.guide.net.TourSessionManager;

/**
 * 导览期间的 Android 前台执行锚点。
 *
 * <p>真实的 BLE、RTC、收音和导览状态仍分别由 BleService、RtcVoiceChatManager、
 * ScoMicAudioSource 与 RealtimeGuideManager 持有。本服务只提供后台执行资格、
 * Android 强制要求的最小通知和锁屏时的受控 CPU 唤醒，避免产生第二套会话状态机。
 */
public final class GuideForegroundService extends Service implements
        TourSessionManager.Listener,
        RealtimeGuideManager.Listener,
        TourReturnCoordinator.Listener {

    private static final String TAG = "GuideForeground";
    private static final String CHANNEL_ID = "active_ai_guide_v1";
    private static final int NOTIFICATION_ID = 4101;
    private static final String ACTION_PREPARE =
            "com.qimu.guide.action.PREPARE_FOREGROUND_GUIDE";
    private static final String ACTION_ACTIVATE =
            "com.qimu.guide.action.ACTIVATE_FOREGROUND_GUIDE";
    private static final String ACTION_START_LISTENING =
            "com.qimu.guide.action.START_FOREGROUND_LISTENING";
    private static final long PREPARATION_TIMEOUT_MS = 35_000L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 35 * 60 * 1000L;
    private static final long WAKE_LOCK_REFRESH_MS = 30 * 60 * 1000L;

    private static volatile GuideForegroundService runningInstance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private boolean foregroundStarted;
    private boolean microphoneForeground;
    private boolean preparationPending;
    private boolean shuttingDown;
    private RealtimeGuideManager.State guideState = RealtimeGuideManager.State.IDLE;
    private String guideMessage = "正在准备齐目 AI…";
    private final Runnable preparationTimeout = () -> {
        if (preparationPending && !TourSessionManager.get().isActive()) {
            Log.w(TAG, "导览创建超时，撤销前台服务预启动");
            shutdownService();
        }
    };
    private final Runnable wakeLockRefreshTask = this::refreshWakeLockIfNeeded;

    private void refreshWakeLockIfNeeded() {
        if (!shouldHoldWakeLock()) return;
        acquireWakeLock();
        mainHandler.postDelayed(wakeLockRefreshTask, WAKE_LOCK_REFRESH_MS);
    }

    /** 用户点击“进入导览”后、网络请求发出前调用。 */
    public static boolean prepare() {
        return sendForegroundCommand(ACTION_PREPARE);
    }

    /** 活跃会话建立后启动或刷新服务。 */
    public static boolean startForTour() {
        GuideForegroundService instance = runningInstance;
        if (instance != null && !instance.shuttingDown) {
            instance.mainHandler.post(instance::activateCurrentTour);
            return true;
        }
        return sendForegroundCommand(ACTION_ACTIVATE);
    }

    /** 在录音权限已授权的可见页面中，将服务升级为 microphone 类型后再开始收音。 */
    public static boolean startListening() {
        GuideForegroundService instance = runningInstance;
        if (instance != null && !instance.shuttingDown) {
            return instance.promoteAndStartListening();
        }
        return sendForegroundCommand(ACTION_START_LISTENING);
    }

    /** 会话创建失败或页面失效时撤销预启动。 */
    public static void cancelPreparation() {
        GuideForegroundService instance = runningInstance;
        if (instance == null) return;
        instance.mainHandler.post(() -> {
            instance.preparationPending = false;
            if (!TourSessionManager.get().isActive()
                    && !TourReturnCoordinator.get().isInProgress()) {
                instance.shutdownService();
            }
        });
    }

    public static void stop() {
        GuideForegroundService instance = runningInstance;
        if (instance != null) instance.mainHandler.post(instance::shutdownService);
    }

    private static boolean sendForegroundCommand(String action) {
        Context context = QimuApplication.getAppContext();
        if (context == null) return false;
        try {
            ContextCompat.startForegroundService(context,
                    new Intent(context, GuideForegroundService.class).setAction(action));
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "启动导览前台服务失败: " + action, error);
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        runningInstance = this;
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "qimu:active-ai-guide");
            wakeLock.setReferenceCounted(false);
        }
        TourSessionManager.get().addListener(this);
        RealtimeGuideManager guideManager = RealtimeGuideManager.get();
        guideState = guideManager.getState();
        String currentMessage = guideManager.getStateMessage();
        if (currentMessage != null && !currentMessage.trim().isEmpty()) {
            guideMessage = currentMessage.trim();
        }
        guideManager.addListener(this);
        TourReturnCoordinator.get().addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_ACTIVATE : intent.getAction();
        preparationPending = ACTION_PREPARE.equals(action);
        if (!ensureForeground(false)) {
            shutdownService();
            return START_NOT_STICKY;
        }

        if (preparationPending) {
            mainHandler.removeCallbacks(preparationTimeout);
            mainHandler.postDelayed(preparationTimeout, PREPARATION_TIMEOUT_MS);
        }
        if (ACTION_START_LISTENING.equals(action)) {
            promoteAndStartListening();
        } else if (TourSessionManager.get().isActive()) {
            activateCurrentTour();
        } else if (!preparationPending) {
            shutdownService();
        }
        return START_NOT_STICKY;
    }

    private void activateCurrentTour() {
        if (shuttingDown) return;
        preparationPending = false;
        mainHandler.removeCallbacks(preparationTimeout);
        TourSessionManager.TourSession session = TourSessionManager.get().current();
        if (session != null) RealtimeGuideManager.get().startForTour(session);
        updateNotification();
        updateWakeLock();
    }

    private boolean promoteAndStartListening() {
        if (!TourSessionManager.get().isActive()) {
            Log.w(TAG, "没有活动导览，拒绝开启后台收音");
            return false;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "未取得录音权限，拒绝升级 microphone 前台服务");
            return false;
        }
        if (!ensureForeground(true)) return false;
        RealtimeGuideManager.get().startGuidance();
        return true;
    }

    private boolean ensureForeground(boolean includeMicrophone) {
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        && (includeMicrophone || microphoneForeground)) {
                    types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                startForeground(NOTIFICATION_ID, notification, types);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foregroundStarted = true;
            microphoneForeground = microphoneForeground || includeMicrophone;
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "进入导览前台模式失败 microphone=" + includeMicrophone, error);
            return false;
        }
    }

    @Override
    public void onTourSessionChanged(boolean active) {
        mainHandler.post(() -> {
            if (active) {
                activateCurrentTour();
                return;
            }
            RealtimeGuideManager.get().stopForTour(null);
            if (TourReturnCoordinator.get().isInProgress()) {
                guideMessage = "正在结束本次导览…";
                updateNotification();
                updateWakeLock();
            } else if (!preparationPending) {
                shutdownService();
            }
        });
    }

    @Override
    public void onStateChanged(RealtimeGuideManager.State state, String message) {
        mainHandler.post(() -> {
            guideState = state;
            guideMessage = message == null || message.trim().isEmpty()
                    ? "齐目 AI 导览进行中" : message.trim();
            updateNotification();
            updateWakeLock();
        });
    }

    @Override
    public void onSubtitle(SubtitleTranscript.Entry entry) {
        // 不在系统通知中展示游客问题或 AI 字幕。
    }

    @Override
    public void onReturnStageChanged(String message) {
        mainHandler.post(() -> {
            guideMessage = message;
            updateNotification();
            updateWakeLock();
        });
    }

    @Override
    public void onReturnFinished(boolean glassesResetConfirmed,
                                 boolean serverCloseSucceeded,
                                 boolean localCleanupSucceeded) {
        mainHandler.post(() -> {
            if (!TourSessionManager.get().isActive()) shutdownService();
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notificationManager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.guide_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.guide_notification_channel_description));
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel.enableVibration(false);
        channel.setSound(null, null);
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_voice_20)
                .setContentTitle(getString(R.string.guide_notification_title))
                .setContentText(guideMessage)
                .setContentIntent(openPendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }

    private void updateNotification() {
        if (!foregroundStarted || notificationManager == null || shuttingDown) return;
        notificationManager.notify(NOTIFICATION_ID, buildNotification());
    }

    private boolean shouldHoldWakeLock() {
        return TourReturnCoordinator.get().isInProgress()
                || RealtimeGuideManager.get().isListeningDesired()
                || guideState == RealtimeGuideManager.State.RTC_CONNECTING
                || guideState == RealtimeGuideManager.State.AUDIO_LINK_STARTING
                || guideState == RealtimeGuideManager.State.LISTENING;
    }

    private void updateWakeLock() {
        mainHandler.removeCallbacks(wakeLockRefreshTask);
        if (shouldHoldWakeLock()) {
            acquireWakeLock();
            mainHandler.postDelayed(wakeLockRefreshTask, WAKE_LOCK_REFRESH_MS);
        } else {
            releaseWakeLock();
        }
    }

    private void acquireWakeLock() {
        PowerManager.WakeLock current = wakeLock;
        if (current == null) return;
        if (current.isHeld()) current.release();
        current.acquire(WAKE_LOCK_TIMEOUT_MS);
    }

    private void releaseWakeLock() {
        PowerManager.WakeLock current = wakeLock;
        if (current != null && current.isHeld()) current.release();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // 与导航/音乐类持续任务一致：划掉页面不等于结束导览。
        Log.i(TAG, "任务被移除，导览前台服务继续运行");
    }

    private void shutdownService() {
        if (shuttingDown) return;
        shuttingDown = true;
        preparationPending = false;
        mainHandler.removeCallbacks(preparationTimeout);
        mainHandler.removeCallbacks(wakeLockRefreshTask);
        releaseWakeLock();
        if (foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            foregroundStarted = false;
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (!shuttingDown && TourSessionManager.get().isActive()) {
            // 若系统单独撤销服务而进程仍在，停止收音，避免无前台通知的后台录音。
            RealtimeGuideManager.get().pauseGuidance();
        }
        TourSessionManager.get().removeListener(this);
        RealtimeGuideManager.get().removeListener(this);
        TourReturnCoordinator.get().removeListener(this);
        mainHandler.removeCallbacksAndMessages(null);
        releaseWakeLock();
        if (runningInstance == this) runningInstance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
