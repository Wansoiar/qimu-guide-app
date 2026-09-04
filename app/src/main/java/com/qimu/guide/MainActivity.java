package com.qimu.guide;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener;
import com.qimu.guide.net.TourSessionManager;
import com.qimu.guide.provisioning.LoginActivity;
import com.qimu.guide.provisioning.OperatorConfigActivity;
import com.qimu.guide.provisioning.OperatorSessionStore;
import com.qimu.guide.provisioning.ProvisioningStore;
import com.qimu.guide.service.BleService;
import com.qimu.guide.service.RealtimeGuideManager;
import com.qimu.guide.service.TourExitWatchdogService;
import com.qimu.guide.ui.device.DeviceFragment;
import com.qimu.guide.ui.dialogue.DialogueFragment;
import com.qimu.guide.ui.export.ExportFragment;

public class MainActivity extends AppCompatActivity implements TourSessionManager.Listener {

    private static final String TAG_DEVICE = "tab_device";
    private static final String TAG_DIALOGUE = "tab_dialogue";
    private static final String TAG_EXPORT = "tab_export";
    private static final int TITLE_TAP_TARGET = 5;
    private static final long TITLE_TAP_WINDOW_MS = 800L;
    private static final long OPERATOR_ENTRY_HOLD_MS = 3_000L;

    private BottomNavigationView bottomNav;
    private BleService bleService;
    private TourSessionManager tourSessionManager;
    private RealtimeGuideManager realtimeGuideManager;
    private ProvisioningStore provisioningStore;
    private OperatorSessionStore operatorSessionStore;
    private TextView tvHeaderStatus;
    private View headerStatusDot;
    private boolean debugPreviewUnlocked;
    private int titleTapCount;
    private long lastTitleTapAt;
    private boolean longPressFired;
    private boolean debugBallDragging;
    private View debugFloatingBall;
    private float debugBallDownX;
    private float debugBallDownY;
    private int debugBallMarginLeft;
    private int debugBallMarginTop;
    private final Handler operatorEntryHandler = new Handler();
    private final Runnable operatorEntryRunnable = this::openOperatorEntry;

    private final BleService.BleListener bleListener = new BleService.BleListener() {
        @Override
        public void onConnectionStateChanged(int state) {
            invalidateTabs();
        }
        @Override public void onBatteryUpdate(int level, boolean charging) { }
        @Override public void onFirmwareVersion(String version) { }
        @Override public void onMediaFileChanged(int p, int v, int a) { }
        @Override public void onWifiStateChange(int state) { }
        @Override public void onWifiConnectionChanged(boolean c) { }
        @Override public void onLog(String tag, String msg) { }
        @Override
        public void onError(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        provisioningStore = ProvisioningStore.get(this);
        if (!provisioningStore.isInitialized()) {
            launchLogin();
            return;
        }
        setContentView(R.layout.activity_main);

        bleService = BleService.getInstance();
        bleService.addListener(bleListener);
        tourSessionManager = TourSessionManager.get();
        tourSessionManager.addListener(this);
        realtimeGuideManager = RealtimeGuideManager.get();
        operatorSessionStore = OperatorSessionStore.get(this);

        bottomNav = findViewById(R.id.bottom_navigation);
        tvHeaderStatus = findViewById(R.id.tv_header_status);
        headerStatusDot = findViewById(R.id.header_status_dot);
        bindVenueTitle();
        bindTitleOperatorEntry();
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            boolean canOpenDialogue = tourSessionManager.isActive()
                    || (BuildConfig.DEBUG && debugPreviewUnlocked);

            if (id == R.id.nav_device) {
                switchFragment(TAG_DEVICE, new DeviceFragment());
                return true;
            } else if (id == R.id.nav_dialogue) {
                if (!canOpenDialogue) {
                    Toast.makeText(this, R.string.must_start_tour_first, Toast.LENGTH_SHORT).show();
                    return false;
                }
                switchFragment(TAG_DIALOGUE, new DialogueFragment());
                return true;
            } else if (id == R.id.nav_export) {
                switchFragment(TAG_EXPORT, new ExportFragment());
                return true;
            }
            return false;
        });

        if (savedInstanceState == null || tourSessionManager.hasCleanupWarning()) {
            if (tourSessionManager.hasCleanupWarning()) removeSessionFragments();
            bottomNav.setSelectedItemId(R.id.nav_device);
        }
        invalidateTabs();
        TourSessionManager.TourSession activeSession = tourSessionManager.current();
        if (activeSession != null) {
            realtimeGuideManager.startForTour(activeSession);
            TourExitWatchdogService.start();
        }
    }

    /** 顶栏标题：齐目·当前场馆名；未设置场馆时显示占位。 */
    private void bindVenueTitle() {
        TextView title = findViewById(R.id.tv_header_title);
        String venueName = "";
        if (provisioningStore != null && provisioningStore.snapshot() != null
                && provisioningStore.snapshot().venue != null) {
            venueName = provisioningStore.snapshot().venue.name;
        }
        if (venueName == null || venueName.isEmpty()) {
            title.setText(R.string.brand_title_no_venue);
        } else {
            title.setText(getString(R.string.brand_title_venue, venueName));
        }
    }

    /** 连点标题 5 次（800ms 窗口内）开调试预览；长按标题 3 秒进入运营配置。 */
    private void bindTitleOperatorEntry() {
        View title = findViewById(R.id.tv_header_title);
        title.setOnClickListener(view -> {
            if (longPressFired) {
                longPressFired = false;
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastTitleTapAt > TITLE_TAP_WINDOW_MS) titleTapCount = 0;
            lastTitleTapAt = now;
            titleTapCount++;
            if (titleTapCount >= TITLE_TAP_TARGET) {
                titleTapCount = 0;
                unlockDebugPreview();
            }
        });
        title.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    longPressFired = false;
                    operatorEntryHandler.removeCallbacks(operatorEntryRunnable);
                    operatorEntryHandler.postDelayed(operatorEntryRunnable,
                            OPERATOR_ENTRY_HOLD_MS);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    operatorEntryHandler.removeCallbacks(operatorEntryRunnable);
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** 调试预览仅在 debug 包可用：连点标题 5 次后，对话/导出页免导览会话直接预览。 */
    private void unlockDebugPreview() {
        if (!BuildConfig.DEBUG) return;
        debugPreviewUnlocked = true;
        invalidateTabs();
        Toast.makeText(this, "调试页面预览已开启", Toast.LENGTH_SHORT).show();
        attachDebugFloatingBall();
    }

    /** 调试悬浮球：仅在 debug 预览解锁后显示，可拖拽，点击清除运营登录状态。 */
    private void attachDebugFloatingBall() {
        if (!BuildConfig.DEBUG || debugFloatingBall != null) return;
        ViewGroup content = findViewById(android.R.id.content);
        if (content == null) return;
        int size = dp(48);
        TextView ball = new TextView(this);
        ball.setText("调");
        ball.setTextColor(0xFFFFFFFF);
        ball.setTextSize(16);
        ball.setGravity(Gravity.CENTER);
        ball.setBackgroundResource(R.drawable.bg_debug_float_ball);
        ball.setContentDescription("调试悬浮球");
        debugFloatingBall = ball;
        int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        ball.setOnClickListener(view -> clearOperatorSession());
        ball.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    debugBallDragging = false;
                    debugBallDownX = event.getRawX();
                    debugBallDownY = event.getRawY();
                    FrameLayout.LayoutParams downParams =
                            (FrameLayout.LayoutParams) view.getLayoutParams();
                    debugBallMarginLeft = downParams.leftMargin;
                    debugBallMarginTop = downParams.topMargin;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getRawX() - debugBallDownX) > touchSlop
                            || Math.abs(event.getRawY() - debugBallDownY) > touchSlop) {
                        debugBallDragging = true;
                    }
                    if (debugBallDragging) {
                        FrameLayout.LayoutParams params =
                                (FrameLayout.LayoutParams) view.getLayoutParams();
                        params.leftMargin = debugBallMarginLeft
                                + (int) (event.getRawX() - debugBallDownX);
                        params.topMargin = debugBallMarginTop
                                + (int) (event.getRawY() - debugBallDownY);
                        view.setLayoutParams(params);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!debugBallDragging) view.performClick();
                    debugBallDragging = false;
                    return true;
                default:
                    return true;
            }
        });
        content.post(() -> {
            if (ball.getParent() != null) return;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
            params.leftMargin = Math.max(dp(8), content.getWidth() - size - dp(16));
            params.topMargin = dp(76);
            content.addView(ball, params);
        });
    }

    /** 清除运营登录状态（OperatorSessionStore），下次进运营入口需重新登录。 */
    private void clearOperatorSession() {
        if (operatorSessionStore == null) operatorSessionStore = OperatorSessionStore.get(this);
        operatorSessionStore.clear();
        Toast.makeText(this, "已清除运营登录状态", Toast.LENGTH_SHORT).show();
    }

    /** 运营入口：token 有效直接进运营配置页，否则先进带关闭按钮的登录页。 */
    private void openOperatorEntry() {
        if (operatorSessionStore == null || operatorSessionStore.isExpired()) {
            startActivity(new Intent(this, LoginActivity.class)
                    .putExtra(LoginActivity.EXTRA_FROM_OPERATOR, true));
        } else {
            startActivity(new Intent(this, OperatorConfigActivity.class));
        }
    }

    private void launchLogin() {
        Intent intent = new Intent(this, LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    /** Keep each tab Fragment alive after its first creation. */
    private void switchFragment(String tag, Fragment newFragment) {
        Fragment target = getSupportFragmentManager().findFragmentByTag(tag);
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction()
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);

        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment.isAdded()) transaction.hide(fragment);
        }

        if (target == null) {
            transaction.add(R.id.fragment_container, newFragment, tag);
        } else {
            transaction.show(target);
        }
        transaction.commit();
    }

    private void invalidateTabs() {
        boolean connected = bleService.isConnected()
                || bleService.getConnectionState() == CRPBleConnectionStateListener.STATE_CONNECTED;
        boolean dialogueEnabled = tourSessionManager.isActive()
                || (BuildConfig.DEBUG && debugPreviewUnlocked);
        runOnUiThread(() -> {
            bottomNav.getMenu().findItem(R.id.nav_dialogue).setEnabled(dialogueEnabled);
            bottomNav.getMenu().findItem(R.id.nav_export).setEnabled(true);
            tvHeaderStatus.setText(connected
                    ? R.string.brand_status_online
                    : R.string.brand_status_offline);
            headerStatusDot.setBackgroundResource(connected
                    ? R.drawable.dot_status_connected
                    : R.drawable.dot_status_disconnected);
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onTourSessionChanged(boolean active) {
        runOnUiThread(() -> {
            TourSessionManager.TourSession session = tourSessionManager.current();
            if (active && session != null) {
                realtimeGuideManager.startForTour(session);
                TourExitWatchdogService.start();
            } else if (!active) {
                realtimeGuideManager.stopForTour(null);
                TourExitWatchdogService.stop();
            }
            invalidateTabs();
            removeSessionFragments();
            bottomNav.setSelectedItemId(active ? R.id.nav_dialogue : R.id.nav_device);
        });
    }

    private void removeSessionFragments() {
        Fragment dialogue = getSupportFragmentManager().findFragmentByTag(TAG_DIALOGUE);
        Fragment export = getSupportFragmentManager().findFragmentByTag(TAG_EXPORT);
        if (dialogue == null && export == null) return;
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        if (dialogue != null) transaction.remove(dialogue);
        if (export != null) transaction.remove(export);
        transaction.commit();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // A previously connected device is persisted by BleService. Reconnect
        // directly by MAC so returning users do not have to scan again.
        if (bleService != null) bleService.autoReconnectLastDevice();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        operatorEntryHandler.removeCallbacks(operatorEntryRunnable);
        if (bleService != null) bleService.removeListener(bleListener);
        if (tourSessionManager != null) tourSessionManager.removeListener(this);
        // 用户正常退出 App（返回键/finish）前结束当前导览会话并关闭 RTC 房间。
        // 从最近任务划掉 App 由 TourExitWatchdogService.onTaskRemoved 处理。
        // 旋转等配置变更触发重建时不结束会话；后台被系统杀死由下次启动的会话标记兜底。
        if (isFinishing()) {
            QimuApplication.endActiveTourBeforeExit(false);
        }
    }

}
