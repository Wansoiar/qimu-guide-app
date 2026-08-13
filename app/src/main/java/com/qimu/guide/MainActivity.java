package com.qimu.guide;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
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
import com.qimu.guide.ui.device.DeviceFragment;
import com.qimu.guide.ui.dialogue.DialogueFragment;
import com.qimu.guide.ui.export.ExportFragment;

public class MainActivity extends AppCompatActivity implements TourSessionManager.Listener {

    private static final String TAG_DEVICE = "tab_device";
    private static final String TAG_DIALOGUE = "tab_dialogue";
    private static final String TAG_EXPORT = "tab_export";
    private static final int TITLE_TAP_TARGET = 5;
    private static final long TITLE_TAP_WINDOW_MS = 800L;

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
        bindTitleOperatorEntry();

        if (BuildConfig.DEBUG) {
            findViewById(R.id.top_app_bar).setOnLongClickListener(view -> {
                debugPreviewUnlocked = true;
                invalidateTabs();
                Toast.makeText(this, "调试页面预览已开启", Toast.LENGTH_SHORT).show();
                return true;
            });
        }
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
        if (activeSession != null) realtimeGuideManager.startForTour(activeSession);
    }

    /** 连点标题 5 次（800ms 窗口内）进入运营入口。 */
    private void bindTitleOperatorEntry() {
        findViewById(R.id.tv_header_title).setOnClickListener(view -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastTitleTapAt > TITLE_TAP_WINDOW_MS) titleTapCount = 0;
            lastTitleTapAt = now;
            titleTapCount++;
            if (titleTapCount >= TITLE_TAP_TARGET) {
                titleTapCount = 0;
                openOperatorEntry();
            }
        });
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

    @Override
    public void onTourSessionChanged(boolean active) {
        runOnUiThread(() -> {
            TourSessionManager.TourSession session = tourSessionManager.current();
            if (active && session != null) {
                realtimeGuideManager.startForTour(session);
            } else if (!active) {
                realtimeGuideManager.stopForTour(null);
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
        if (bleService != null) bleService.removeListener(bleListener);
        if (tourSessionManager != null) tourSessionManager.removeListener(this);
    }
}
