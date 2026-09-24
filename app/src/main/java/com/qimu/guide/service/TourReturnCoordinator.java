package com.qimu.guide.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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
    private boolean confirmingServerStop;
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

    /** 当前归还是否为「活动游览结束」流程；false 表示「上次订单收尾」。 */
    public boolean isActiveTourReturnInProgress() {
        return inProgress && activeTourReturn;
    }

    public boolean beginReturn() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.e(TAG, "归还流程必须在主线程启动");
            return false;
        }
        TourSessionManager sessionManager = TourSessionManager.get();
        TourSessionManager.TourSession session = sessionManager.current();
        if (inProgress || session == null) return false;

        BleService bleService = BleService.getInstance();
        BleService.ReturnTarget returnTarget = bleService.beginReturnTransaction();
        if (returnTarget == null) return false;

        inProgress = true;
        activeTourReturn = true;
        confirmingServerStop = true;
        int operation = ++generation;
        publishStage("正在确认结束本次游览…");
        confirmServerStop(operation, session, returnTarget);
        // 兜底：确认线程异常退出等情况时，超时后强制按失败收敛，避免 loading 弹窗卡死。
        mainHandler.postDelayed(() -> {
            if (isCurrent(operation) && confirmingServerStop) {
                Log.e(TAG, "确认结束超时，按失败结束归还");
                continueAfterServerStop(operation, session, returnTarget,
                        new GuideApiClient.RtcStopResult(false, null));
            }
        }, SERVER_STOP_CONFIRM_TIMEOUT_MS);
        return true;
    }

    /**
     * 后台确认后端 /v1/rtc/session/stop 真正成功后才继续眼镜重置与本地清理；
     * 失败则释放归还占位并通知 UI，绝不按成功继续跳转。
     */
    private void confirmServerStop(int operation,
                                   TourSessionManager.TourSession session,
                                   BleService.ReturnTarget returnTarget) {
        new Thread(() -> {
            final GuideApiClient.RtcStopResult result = confirmServerStopBlocking();
            mainHandler.post(() ->
                    continueAfterServerStop(operation, session, returnTarget, result));
        }, "tour-return-stop-confirm").start();
    }

    private GuideApiClient.RtcStopResult confirmServerStopBlocking() {
        try {
            return RealtimeGuideManager.get().confirmServerStopForTour();
        } catch (RuntimeException failure) {
            // 确认过程异常统一按「接口无返回」处理，避免进度框卡死。
            Log.e(TAG, "确认服务端停止导览异常", failure);
            return new GuideApiClient.RtcStopResult(false, null);
        }
    }

    private void continueAfterServerStop(int operation,
                                         TourSessionManager.TourSession session,
                                         BleService.ReturnTarget returnTarget,
                                         GuideApiClient.RtcStopResult result) {
        if (!isCurrent(operation)) return;
        confirmingServerStop = false;
        if (result.ok) {
            // 服务端已确认停止，再走本地收尾：停眼镜收音、退 RTC 房。stopForTour 内仍会
            // 补发一次幂等 stop，失败不影响本次已确认的归还。
            TourSessionManager.get().invalidatePendingSessionRequests();
            RealtimeGuideManager.get().stopForTour(session.sessionId);
            publishStage("正在关闭本次游览会话…");
            startGlassesReset(operation, session.sessionId, returnTarget, true);
            return;
        }
        // 后端未确认停止：释放归还占位、结束归还状态并提示用户，不再 reset/完成会话。
        BleService.getInstance().cancelReturnTransaction(returnTarget);
        inProgress = false;
        activeTourReturn = false;
        currentStage = "";
        String message = result.serverMessage != null && !TextUtils.isEmpty(result.serverMessage)
                ? result.serverMessage : "暂时无法结束本次游览，请联系管理员处理";
        for (Listener listener : listeners) {
            try {
                listener.onReturnFailed(message);
            } catch (RuntimeException listenerFailure) {
                Log.e(TAG, "归还失败监听器异常", listenerFailure);
            }
        }
    }

    /**
     * 清理上次异常退出遗留的订单（重启后无活动会话，仅持有持久化的 session_id）。
     * 复用与 {@link #beginReturn()} 相同的眼镜重置 + 本地缓存清理管线；服务端收尾与
     * 活动游览一致：先确认后端停止成功再继续本地收尾，失败不按成功继续。
     */
    public boolean beginStaleOrderReturn(@Nullable String sessionId,
                                         @Nullable String roomId,
                                         @Nullable String taskId) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.e(TAG, "上次订单收尾必须在主线程启动");
            return false;
        }
        TourSessionManager.TourSession current = TourSessionManager.get().current();
        if (current != null || inProgress
                || sessionId == null || sessionId.trim().isEmpty()) {
            return false;
        }

        BleService bleService = BleService.getInstance();
        BleService.ReturnTarget returnTarget = bleService.beginReturnTransaction();
        if (returnTarget == null) return false;
        RealtimeGuideManager.get().stopForTour(null);
        TourSessionManager.get().invalidatePendingSessionRequests();

        final String sid = sessionId.trim();
        final String rid = roomId == null ? "" : roomId.trim();
        final String tid = taskId == null ? "" : taskId.trim();

        inProgress = true;
        activeTourReturn = false;
        confirmingServerStop = true;
        int operation = ++generation;
        // 与活动游览结束一致：先确认后端停止成功，才继续眼镜重置与本地清理；
        // 失败则释放归还占位并通知 UI，绝不按成功继续。
        publishStage("正在确认结束上次游览…");
        confirmStaleServerStop(operation, sid, rid, tid, returnTarget);
        mainHandler.postDelayed(() -> {
            if (isCurrent(operation) && confirmingServerStop) {
                Log.e(TAG, "确认结束上次订单超时，按失败结束归还");
                continueStaleServerStop(operation, sid, rid, tid, returnTarget,
                        new GuideApiClient.RtcStopResult(false, null));
            }
        }, SERVER_STOP_CONFIRM_TIMEOUT_MS);
        return true;
    }

    /**
     * 后台确认后端停止遗留会话成功后才走眼镜重置与本地清理；失败则释放归还占位，
     * 与 {@link #beginReturn()} 的确认逻辑保持一致。
     */
    private void confirmStaleServerStop(int operation, String sessionId,
                                        String roomId, String taskId,
                                        BleService.ReturnTarget returnTarget) {
        new Thread(() -> {
            final GuideApiClient.RtcStopResult result =
                    confirmStaleServerStopBlocking(sessionId, roomId, taskId);
            mainHandler.post(() -> continueStaleServerStop(operation, sessionId,
                    roomId, taskId, returnTarget, result));
        }, "stale-order-stop-confirm").start();
    }

    private GuideApiClient.RtcStopResult confirmStaleServerStopBlocking(
            String sessionId, String roomId, String taskId) {
        try {
            return RealtimeGuideManager.get()
                    .confirmServerStopForStaleOrder(roomId, taskId, sessionId);
        } catch (RuntimeException failure) {
            // 确认过程异常统一按「接口无返回」处理，避免进度框卡死。
            Log.e(TAG, "确认结束上次订单异常", failure);
            return new GuideApiClient.RtcStopResult(false, null);
        }
    }

    private void continueStaleServerStop(int operation, String sessionId,
                                         String roomId, String taskId,
                                         BleService.ReturnTarget returnTarget,
                                         GuideApiClient.RtcStopResult result) {
        if (!isCurrent(operation)) return;
        confirmingServerStop = false;
        if (result.ok) {
            // 后端已确认停止，再走本地收尾（眼镜重置 + 缓存清理）。
            publishStage("正在结束上次游览…");
            startGlassesReset(operation, sessionId, returnTarget, true);
            return;
        }
        BleService.getInstance().cancelReturnTransaction(returnTarget);
        inProgress = false;
        activeTourReturn = false;
        currentStage = "";
        String message = result.serverMessage != null && !TextUtils.isEmpty(result.serverMessage)
                ? result.serverMessage : "暂时无法结束上次订单，请联系管理员处理";
        for (Listener listener : listeners) {
            try {
                listener.onReturnFailed(message);
            } catch (RuntimeException listenerFailure) {
                Log.e(TAG, "上次订单收尾失败监听器异常", listenerFailure);
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
                activeTourReturn = false;
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
