package com.qimu.guide;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener;
import com.qimu.guide.service.BleService;
import com.qimu.guide.ui.device.DeviceFragment;
import com.qimu.guide.ui.dialogue.DialogueFragment;
import com.qimu.guide.ui.export.ExportFragment;

public class MainActivity extends AppCompatActivity {

    private BottomNavigationView bottomNav;
    private BleService bleService;

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
        setContentView(R.layout.activity_main);

        bleService = BleService.getInstance();
        bleService.addListener(bleListener);

        bottomNav = findViewById(R.id.bottom_navigation);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            boolean connected = bleService.isConnected()
                    || bleService.getConnectionState() == CRPBleConnectionStateListener.STATE_CONNECTED;

            if (id == R.id.nav_device) {
                switchFragment(new DeviceFragment());
                return true;
            } else if (id == R.id.nav_dialogue) {
                // 对话页不强制连眼镜：连了走眼镜链路，没连用手机麦克风「按住说话」，
                // 都走同一套后端（端无关）。这样 App 独立可用、也方便无眼镜调试。
                switchFragment(new DialogueFragment());
                return true;
            } else if (id == R.id.nav_export) {
                if (!connected) {
                    Toast.makeText(this, R.string.must_connect_first, Toast.LENGTH_SHORT).show();
                    return false;
                }
                switchFragment(new ExportFragment());
                return true;
            }
            return false;
        });

        if (savedInstanceState == null) {
            // 启动直接进对话页：无眼镜也能用手机麦克风「按住说话」。
            // 需要连眼镜时再切到设备页扫描。
            bottomNav.setSelectedItemId(R.id.nav_dialogue);
        }
    }

    private void switchFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .commit();
    }

    private void invalidateTabs() {
        boolean connected = bleService.isConnected()
                || bleService.getConnectionState() == CRPBleConnectionStateListener.STATE_CONNECTED;
        runOnUiThread(() -> {
            // 对话页始终可用（无眼镜可用手机麦克风）；导出依赖眼镜录音，保持需连接。
            bottomNav.getMenu().findItem(R.id.nav_dialogue).setEnabled(true);
            bottomNav.getMenu().findItem(R.id.nav_export).setEnabled(connected);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bleService.removeListener(bleListener);
    }
}
