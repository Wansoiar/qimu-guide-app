package com.qimu.guide.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.qimu.guide.QimuApplication;

/**
 * 导览会话期间的退出哨兵。普通 Activity 收不到“任务被从最近任务列表移除”事件，
 * 只有正在运行的 Service 会收到 {@link #onTaskRemoved(Intent)}；App 被从最近任务
 * 划掉（最常见的“关闭 App”手势）时借此在进程退出前结束会话并关闭 RTC 房间。
 * 会话开始/结束时由使用页启动/停止本服务。
 */
public final class TourExitWatchdogService extends Service {

    private static final String TAG = "TourExitWatchdog";

    public static void start() {
        Context context = QimuApplication.getAppContext();
        if (context == null) return;
        try {
            context.startService(new Intent(context, TourExitWatchdogService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "启动退出哨兵失败", e);
        }
    }

    public static void stop() {
        Context context = QimuApplication.getAppContext();
        if (context == null) return;
        try {
            context.stopService(new Intent(context, TourExitWatchdogService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "停止退出哨兵失败", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "任务被移除，结束当前导览会话并关闭 RTC 房间");
        QimuApplication.endActiveTourBeforeExit(false);
        stopSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
