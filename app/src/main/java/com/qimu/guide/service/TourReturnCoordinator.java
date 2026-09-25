package com.qimu.guide.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.qimu.guide.QimuApplication;
import com.qimu.guide.net.GuideApiClient;
import com.qimu.guide.net.TourSessionManager;
import com.qimu.guide.ui.gallery.GallerySelectionStore;
import com.qimu.guide.ui.gallery.LocalPhotoRepository;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Owns the irreversible close -> glasses reset -> local cleanup return sequence. */
public final class TourReturnCoordinator {

    private static final String TAG = "TourReturnCoordinator";

    public interface Listener {
        void onReturnStageChanged(String message);
        void onReturnFinished(boolean glassesResetConfirmed, boolean serverCloseSucceeded,
                              boolean localCleanupSucceeded);
        /** 服务端确认结束时失败：message 为接口返回内容或兜底文案，本次归还不会继续。 */
        void onReturnFailed(String message);
    }

    private static final TourReturnCoordinator INSTANCE = new TourReturnCoordinator();

    public static TourReturnCoordinator get() {
        return INSTANCE;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private boolean inProgress;
    /** true：本次归还来自活动游览（导出页）；false：上次订单收尾（设备页）。 */
    private boolean activeTourReturn;
    /** 服务端停止确认进行中；用于兜底超时区分「确认阶段」与「后续清理阶段」。 */
    private final ReturnConfirmationGate confirmationGate = new ReturnConfirmationGate();
    private GuideApiClient pendingStopClient;
    private int generation;
    private String currentStage = "";
    /** 服务端停止确认的兜底超时：超过该时间仍未确认则按失败结束归还，避免进度框卡死。 */
    private static final long SERVER_STOP_CONFIRM_TIMEOUT_MS = 20_000L;

    private TourReturnCoordinator() {
    }

    public void addListener(Listener listener) {
        if (listener == null) return;
        listeners.add(listener);
        if (inProgress && !currentStage.isEmpty()) listener.onReturnStageChanged(currentStage);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isInProgress() {
        return inProgress;
    }

    /** The most recent flow type remains available to terminal callbacks. */
    public boolean isActiveTourReturn() { return activeTourReturn; }

    public boolean isActiveTourReturnInProgress() {
        return inProgress && activeTourReturn;
    }

    public boolean beginReturn() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.e(TAG, "Return must start on the main thread");
            return false;
        }
        TourSessionManager sessionManager = TourSessionManager.get();
        TourSessionManager.TourSession session = sessionManager.current();
        if (inProgress || session == null) return false;
        BleService.ReturnTarget target = BleService.getInstance().beginReturnTransaction();
        if (target == null) return false;

        int operation = beginConfirmation(true, "正在确认结束本次游览…");
        sessionManager.invalidatePendingSessionRequests();
        try {
            GuideApiClient.RtcStopRequest request =
                    RealtimeGuideManager.get().prepareServerStopForTour(session);
            confirmServerStop(operation, request, target);
        } catch (RuntimeException failure) {
            Log.e(TAG, "Unable to prepare tour stop confirmation", failure);
            failPreparation(operation, session.sessionId, target);
        }
        return true;
    }

    /** Missing RTC credentials still require a confirmed session-only stop. */
    public boolean beginStaleOrderReturn(@Nullable String sessionId,
                                         @Nullable String roomId,
                                         @Nullable String taskId) {
        if (Looper.myLooper() != Looper.getMainLooper()
                || TourSessionManager.get().current() != null || inProgress
                || sessionId == null || sessionId.trim().isEmpty()) return false;
        BleService.ReturnTarget target = BleService.getInstance().beginReturnTransaction();
        if (target == null) return false;

        int operation = beginConfirmation(false, "正在确认结束上次游览…");
        TourSessionManager.get().invalidatePendingSessionRequests();
        try {
            GuideApiClient.RtcStopRequest request = RealtimeGuideManager.get()
                    .prepareServerStopForStaleOrder(roomId, taskId, sessionId.trim());
            confirmServerStop(operation, request, target);
        } catch (RuntimeException failure) {
            Log.e(TAG, "Unable to prepare stale order stop confirmation", failure);
            failPreparation(operation, sessionId.trim(), target);
        }
        return true;
    }

    private int beginConfirmation(boolean activeTour, String message) {
        inProgress = true;
        activeTourReturn = activeTour;
        int operation = ++generation;
        confirmationGate.begin(operation, SystemClock.elapsedRealtime(), SERVER_STOP_CONFIRM_TIMEOUT_MS);
        publishStage(message);
        return operation;
    }

    private void failPreparation(int operation, String sessionId, BleService.ReturnTarget target) {
        if (!isCurrent(operation) || confirmationGate.resolve(operation, false,
                SystemClock.elapsedRealtime()) == ReturnConfirmationGate.Resolution.IGNORED) return;
        if (pendingStopClient != null) pendingStopClient.cancelAll();
        pendingStopClient = null;
        failReturn(sessionId, target, new GuideApiClient.RtcStopResult(false, null));
    }

    /** The stop client is isolated from RTC/vision requests and can be cancelled on timeout. */
    private void confirmServerStop(int operation, GuideApiClient.RtcStopRequest request,
                                   BleService.ReturnTarget target) {
        GuideApiClient confirmationClient = new GuideApiClient();
        pendingStopClient = confirmationClient;
        Thread worker = new Thread(() -> {
            GuideApiClient.RtcStopResult result = confirmServerStopBlocking(
                    operation, confirmationClient, request);
            mainHandler.post(() -> continueAfterServerStop(operation, request, target, result));
        }, "tour-return-stop-confirm");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException failure) {
            Log.e(TAG, "Unable to start stop confirmation", failure);
            continueAfterServerStop(operation, request, target,
                    new GuideApiClient.RtcStopResult(false, null));
            return;
        }
        mainHandler.postDelayed(() -> {
            if (isCurrent(operation) && confirmationGate.isPending(operation)) {
                continueAfterServerStop(operation, request, target,
                        new GuideApiClient.RtcStopResult(false, null));
            }
        }, confirmationGate.remainingMs(operation, SystemClock.elapsedRealtime()));
    }

    private GuideApiClient.RtcStopResult confirmServerStopBlocking(int operation,
            GuideApiClient client, GuideApiClient.RtcStopRequest request) {
        GuideApiClient.RtcStopResult last = new GuideApiClient.RtcStopResult(false, null);
        try {
            for (int attempt = 0; attempt < 3 && confirmationGate.isPending(operation); attempt++) {
                long remaining = confirmationGate.remainingMs(operation, SystemClock.elapsedRealtime());
                if (remaining <= 0) break;
                // All attempts share one absolute budget. A healthy 5-10 second vendor stop
                // can finish; fast failures may retry without restarting the 20 second clock.
                last = client.stopRtcSessionWithResult(request.roomId, request.taskId,
                        request.sessionId, true, request.headers, remaining);
                if (last.ok || com.qimu.guide.net.AppAuthInterceptor.consumeAuthError()) return last;
                if (attempt < 2 && confirmationGate.isPending(operation)) {
                    long delay = Math.min(250L * (attempt + 1),
                            confirmationGate.remainingMs(operation, SystemClock.elapsedRealtime()));
                    if (delay > 0) Thread.sleep(delay);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            Log.e(TAG, "Stop confirmation failed", failure);
        }
        return last;
    }

    private void continueAfterServerStop(int operation, GuideApiClient.RtcStopRequest request,
                                         BleService.ReturnTarget target,
                                         GuideApiClient.RtcStopResult result) {
        // A timeout, duplicate result or superseded attempt cannot reach destructive cleanup.
        if (!isCurrent(operation)) return;
        ReturnConfirmationGate.Resolution resolution = confirmationGate.resolve(
                operation, result.ok, SystemClock.elapsedRealtime());
        if (resolution == ReturnConfirmationGate.Resolution.IGNORED) return;
        GuideApiClient client = pendingStopClient;
        pendingStopClient = null;
        if (client != null) client.cancelAll();
        if (resolution != ReturnConfirmationGate.Resolution.CONFIRMED) {
            failReturn(request.sessionId, target, result);
            return;
        }
        RealtimeGuideManager.get().completeConfirmedServerStop(request);
        startGlassesReset(operation, request.sessionId, target, true);
    }

    private void failReturn(String sessionId, BleService.ReturnTarget target,
                            GuideApiClient.RtcStopResult result) {
        BleService.getInstance().cancelReturnTransaction(target);
        inProgress = false;
        currentStage = "";
        String message = activeTourReturn
                ? "尚未确认结束本次游览，照片已保留，请重试"
                : "尚未确认结束上次订单，记录已保留，请重试";
        if (result.serverMessage != null && !TextUtils.isEmpty(result.serverMessage)) {
            message += "（" + result.serverMessage + "）";
        }
        RealtimeGuideManager.get().onServerStopConfirmationFailed(sessionId, message);
        for (Listener listener : listeners) {
            try {
                listener.onReturnFailed(message);
            } catch (RuntimeException listenerFailure) {
                Log.e(TAG, "Return failure listener failed", listenerFailure);
            }
        }
    }

    private void startGlassesReset(int operation, String sessionId,
                                   BleService.ReturnTarget returnTarget,
                                   boolean serverCloseSucceeded) {
        if (!isCurrent(operation)) return;
        publishStage("正在清理眼镜照片，请勿关闭应用…");
        BleService.getInstance().resetForReturn(returnTarget, (success, errorCode) ->
                cleanupLocalData(operation, sessionId, returnTarget,
                        success, serverCloseSucceeded));
    }

    private void cleanupLocalData(int operation, String sessionId,
                                  BleService.ReturnTarget returnTarget,
                                  boolean resetConfirmed, boolean serverCloseSucceeded) {
        if (!isCurrent(operation)) return;
        publishStage("正在清理游览记录…");
        Thread cleanupThread = new Thread(() -> {
            CleanupResult result = null;
            try {
                result = deleteLocalData(sessionId, returnTarget);
            } catch (RuntimeException cleanupFailure) {
                Log.e(TAG, "清理本次导览缓存异常", cleanupFailure);
                result = CleanupResult.failure("本地缓存清理异常");
            } finally {
                CleanupResult completed = result == null
                        ? CleanupResult.failure("本地缓存清理未完成") : result;
                mainHandler.post(() -> finish(operation, sessionId, returnTarget,
                        resetConfirmed, serverCloseSucceeded, completed));
            }
        }, "tour-return-cleanup");
        try {
            cleanupThread.start();
        } catch (RuntimeException startFailure) {
            Log.e(TAG, "无法启动本地缓存清理线程", startFailure);
            finish(operation, sessionId, returnTarget, resetConfirmed,
                    serverCloseSucceeded, CleanupResult.failure("无法启动本地缓存清理"));
        }
    }

    private void finish(int operation, String sessionId,
                        BleService.ReturnTarget returnTarget,
                        boolean resetConfirmed, boolean serverCloseSucceeded,
                        CleanupResult cleanupResult) {
        if (!isCurrent(operation)) return;
        BleService bleService = BleService.getInstance();
        boolean localCleanupSucceeded = cleanupResult.succeeded;
        try {
            try {
                if (localCleanupSucceeded
                        && !bleService.clearReturnedMediaState(returnTarget)) {
                    cleanupResult.addFailure("无法清除本地媒体目录记录");
                    localCleanupSucceeded = false;
                }
            } catch (RuntimeException stateCleanupFailure) {
                Log.e(TAG, "清除本地媒体状态失败", stateCleanupFailure);
                cleanupResult.addFailure("无法清除本地媒体目录记录");
                localCleanupSucceeded = false;
            }
            if (!localCleanupSucceeded) {
                bleService.postLog("归还", "本机缓存未完全清理: " + cleanupResult.summary());
            }

            boolean cleanupConfirmed = resetConfirmed && localCleanupSucceeded;
            try {
                TourSessionManager sessionManager = TourSessionManager.get();
                TourSessionManager.TourSession active = sessionManager.current();
                if (active != null && sessionId.equals(active.sessionId)) {
                    sessionManager.completeSession(sessionId, cleanupConfirmed);
                } else if (active == null) {
                    sessionManager.forgetLastSession(sessionId, cleanupConfirmed);
                } else {
                    Log.e(TAG, "当前会话已变化，拒绝由旧归还事务清除新会话");
                }
            } catch (RuntimeException sessionFailure) {
                Log.e(TAG, "提交归还后的会话状态失败", sessionFailure);
            }
        } finally {
            try {
                bleService.prepareForNextVisitor(returnTarget);
            } catch (RuntimeException prepareFailure) {
                Log.e(TAG, "准备下一位游客的连接状态失败", prepareFailure);
            } finally {
                inProgress = false;
                currentStage = "";
            }
        }
        for (Listener listener : listeners) {
            try {
                listener.onReturnFinished(
                        resetConfirmed, serverCloseSucceeded, localCleanupSucceeded);
            } catch (RuntimeException listenerFailure) {
                Log.e(TAG, "归还完成监听器异常", listenerFailure);
            }
        }
    }

    private boolean isCurrent(int operation) {
        return inProgress && operation == generation;
    }

    private void publishStage(String message) {
        currentStage = message;
        for (Listener listener : listeners) {
            try {
                listener.onReturnStageChanged(message);
            } catch (RuntimeException listenerFailure) {
                Log.e(TAG, "归还阶段监听器异常", listenerFailure);
            }
        }
    }

    private CleanupResult deleteLocalData(String sessionId,
                                          BleService.ReturnTarget returnTarget) {
        Context context = QimuApplication.getAppContext();
        CleanupResult result = new CleanupResult();
        if (isSafeSessionId(sessionId)) {
            deletePrivatePath(context,
                    context.getExternalFilesDir("session_" + sessionId),
                    "本次会话目录", result);
        } else {
            result.addFailure("会话标识格式异常，拒绝清理对应目录");
        }
        deletePrivatePath(context, context.getExternalFilesDir("audio"),
                "音频缓存目录", result);

        try {
            LocalPhotoRepository.deleteAllPublishedPhotos(context);
        } catch (IOException | RuntimeException photoCleanupFailure) {
            result.addFailure("本机导览照片未完全清理");
            Log.e(TAG, "无法清理本机导览照片", photoCleanupFailure);
        }
        try {
            if (!new GallerySelectionStore(context).clearForTourEnd()) {
                result.addFailure("照片选择状态未清理");
            }
        } catch (RuntimeException selectionCleanupFailure) {
            result.addFailure("照片选择状态未清理");
            Log.e(TAG, "无法清理照片选择状态", selectionCleanupFailure);
        }

        String sdkDirectory = returnTarget.getDownloadDirectory();
        if (TextUtils.isEmpty(sdkDirectory)) {
            result.addFailure("SDK 媒体目录不可用");
        } else {
            deleteSdkMediaPath(context, new File(sdkDirectory),
                    "SDK 媒体目录", result);
        }

        // Keep the bundled SDK fallback covered when the latest callback points elsewhere.
        deletePrivatePath(context,
                new File(context.getFilesDir(), "moyoung/wifi/media_res"),
                "SDK 默认媒体目录", result);
        return result;
    }

    private boolean isSafeSessionId(String sessionId) {
        return sessionId != null
                && sessionId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    }

    private void deleteSdkMediaPath(Context context, File target, String label,
                                    CleanupResult result) {
        if (target == null) {
            result.addFailure(label + "不可用");
            return;
        }
        try {
            if (!isExpectedSdkMediaPath(context, target)) {
                result.addFailure(label + "格式异常，拒绝删除: " + target.getPath());
                return;
            }
        } catch (IOException | SecurityException pathFailure) {
            result.addFailure(label + "路径校验失败: " + target.getPath());
            Log.e(TAG, "无法校验 SDK 媒体目录 " + target, pathFailure);
            return;
        }
        deletePrivatePath(context, target, label, result);
    }

    private boolean isExpectedSdkMediaPath(Context context, File target) throws IOException {
        File cursor = target.getCanonicalFile();
        while (cursor != null) {
            File wifi = cursor.getParentFile();
            File moyoung = wifi == null ? null : wifi.getParentFile();
            File appRoot = moyoung == null ? null : moyoung.getParentFile();
            if ("media_res".equals(cursor.getName())
                    && wifi != null && "wifi".equals(wifi.getName())
                    && moyoung != null && "moyoung".equals(moyoung.getName())
                    && isAppStorageRoot(context, appRoot)) {
                return true;
            }
            cursor = cursor.getParentFile();
        }
        return false;
    }

    private void deletePrivatePath(Context context, File target, String label,
                                   CleanupResult result) {
        if (target == null) {
            result.addFailure(label + "不可用");
            return;
        }
        try {
            if (!isSafeAppPrivatePath(context, target)) {
                result.addFailure(label + "路径不在 App 私有目录内: " + target.getPath());
                return;
            }
            deleteRecursively(context, target, target.getCanonicalPath(), label, result);
        } catch (IOException | SecurityException cleanupFailure) {
            result.addFailure(label + "路径校验失败: " + target.getPath());
            Log.e(TAG, "无法校验清理路径 " + target, cleanupFailure);
        }
    }

    private boolean deleteRecursively(Context context, File file, String treeRootPath,
                                      String label, CleanupResult result) {
        try {
            String canonicalPath = file.getCanonicalPath();
            if ((!canonicalPath.equals(treeRootPath)
                    && !canonicalPath.startsWith(treeRootPath + File.separator))
                    || !isSafeAppPrivatePath(context, file)) {
                result.addFailure(label + "包含越界路径: " + file.getPath());
                return false;
            }
            if (!file.exists()) return true;

            boolean succeeded = true;
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children == null) {
                    result.addFailure(label + "无法读取: " + file.getPath());
                    succeeded = false;
                } else {
                    for (File child : children) {
                        if (!deleteRecursively(
                                context, child, treeRootPath, label, result)) {
                            succeeded = false;
                        }
                    }
                }
            }
            if (file.exists() && !file.delete()) {
                result.addFailure(label + "无法删除: " + file.getPath());
                succeeded = false;
            }
            return succeeded && !file.exists();
        } catch (IOException | SecurityException cleanupFailure) {
            result.addFailure(label + "清理失败: " + file.getPath());
            Log.e(TAG, "无法清理 " + file, cleanupFailure);
            return false;
        }
    }

    private boolean isSafeAppPrivatePath(Context context, File target) throws IOException {
        String targetPath = target.getCanonicalPath();
        for (File root : appStorageRoots(context)) {
            if (root == null) continue;
            String rootPath = root.getCanonicalPath();
            // Never permit deleting an entire app storage root, only its descendants.
            if (targetPath.startsWith(rootPath + File.separator)) return true;
        }
        return false;
    }

    private boolean isAppStorageRoot(Context context, File candidate) throws IOException {
        if (candidate == null) return false;
        String candidatePath = candidate.getCanonicalPath();
        for (File root : appStorageRoots(context)) {
            if (root != null && candidatePath.equals(root.getCanonicalPath())) return true;
        }
        return false;
    }

    private List<File> appStorageRoots(Context context) {
        List<File> roots = new ArrayList<>();
        roots.add(context.getFilesDir());
        roots.add(context.getCacheDir());
        addRoots(roots, context.getExternalFilesDirs(null));
        addRoots(roots, context.getExternalCacheDirs());
        return roots;
    }

    private void addRoots(List<File> roots, File[] candidates) {
        if (candidates == null) return;
        for (File candidate : candidates) {
            if (candidate != null) roots.add(candidate);
        }
    }

    private static final class CleanupResult {
        boolean succeeded = true;
        final List<String> failures = new ArrayList<>();

        static CleanupResult failure(String message) {
            CleanupResult result = new CleanupResult();
            result.addFailure(message);
            return result;
        }

        void addFailure(String message) {
            succeeded = false;
            if (failures.size() < 8) failures.add(message);
        }

        String summary() {
            return failures.isEmpty() ? "未知清理错误" : TextUtils.join("；", failures);
        }
    }
}
