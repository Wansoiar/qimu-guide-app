package com.qimu.guide;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.moyoung.glasses.CRPBleClient;
import com.moyoung.glasses.util.BleLog;
import com.qimu.guide.net.TourSessionApiClient;
import com.qimu.guide.net.TourSessionManager;
import com.qimu.guide.service.RealtimeGuideManager;
import com.qimu.guide.service.TourExitWatchdogService;
import com.qimu.guide.service.TourReturnCoordinator;

public class QimuApplication extends Application {

    private static final String TAG = "QimuApplication";
    private static Context appContext;
    private CRPBleClient mBleClient;

    public static CRPBleClient getBleClient() {
        QimuApplication app = (QimuApplication) appContext;
        return app.mBleClient;
    }

    public static Context getAppContext() {
        return appContext;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = this;
        BleLog.isPrint = true;
        mBleClient = CRPBleClient.create(this);
        TourSessionManager.get().initialize(this);
        registerExitUncaughtHandler();
    }

    /** 闪退兜底：进程即将死亡前尽力结束当前导览会话并关闭 RTC 房间。 */
    private void registerExitUncaughtHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                endActiveTourBeforeExit(true);
            } catch (Throwable ignored) {
                // 退出路径只做尽力而为的收尾，不向上抛。
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    /**
     * App 关闭/闪退前的统一收尾：结束当前导览会话并关闭 RTC 房间（尽力而为）。
     * 未走归还流程（眼镜/本地数据未清理）时，会话标记为待管理员确认，下次启动需先确认。
     *
     * @param processDying true=进程即将被杀死（闪退兜底），主线程 Looper 可能不可用，
     *                     直接通知后端停 RTC，不做依赖主线程的本地退房；
     *                     false=进程仍会存活（正常退出），走完整退房链路。
     */
    public static void endActiveTourBeforeExit(boolean processDying) {
        try {
            TourSessionManager.TourSession session = TourSessionManager.get().current();
            if (session == null) return;
            RealtimeGuideManager guide = RealtimeGuideManager.get();
            Log.i(TAG, "退出收尾: 结束会话 " + session.sessionId
                    + " processDying=" + processDying);
            // 与归还流程一致：关闭服务端导览会话（本地体验会话无服务端记录，跳过）。
            if (session.serverBacked) {
                try {
                    TourSessionApiClient.get().closeSession(
                            session.sessionId, ignored -> { });
                } catch (Throwable ignored) {
                    // 关闭失败由服务端 IdleTimeout/下次进入时兜底。
                }
            }
            // 先发上述关闭请求，再等待后端 RTC 停止，让两者都落在进程存活窗口内。
            if (processDying) {
                guide.stopRtcSessionForExit(session.sessionId);
            } else {
                guide.stopForTour(session.sessionId);
            }
            // 归还流程正在执行时由归还流程负责收尾并确认清理，避免重复关闭。
            if (!TourReturnCoordinator.get().isInProgress()) {
                TourSessionManager.get().completeSession(session.sessionId, false);
            }
            if (!processDying) {
                // UI 即将退出，退出哨兵不再需要（闪退时进程随之消亡，无需显式停止）。
                TourExitWatchdogService.stop();
            }
        } catch (Throwable ignored) {
            // 退出路径只做尽力而为的收尾，不向上抛。
        }
    }
}
