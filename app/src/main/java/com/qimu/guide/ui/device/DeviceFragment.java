package com.qimu.guide.ui.device;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.moyoung.glasses.conn.CRPBleConnection;
import com.moyoung.glasses.conn.CRPBleDevice;
import com.moyoung.glasses.conn.callback.CRPCommandCallback;
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener;
import com.moyoung.glasses.scan.bean.CRPScanDevice;
import com.moyoung.glasses.scan.callback.CRPScanCallback;
import com.qimu.guide.R;
import com.qimu.guide.service.BleService;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class DeviceFragment extends Fragment {

    private static final long SCAN_TIMEOUT_MS = 15000;
    private BleService bleService;

    private View layoutDisconnected, layoutConnected;
    private TextView tvScanStatus, tvDeviceName, tvDeviceId, tvBattery, tvSignal, tvFirmware, tvDebugLog;
    private View btnScan;
    private RecyclerView recyclerDevices;

    private final List<CRPScanDevice> deviceList = new ArrayList<>();
    private ScanDeviceAdapter deviceAdapter;
    private boolean isScanning = false;

    private final BleService.BleListener bleListener = new BleService.BleListener() {
        @Override public void onConnectionStateChanged(int state) {
            appendLog("状态变化: " + stateToString(state));
            updateConnectionUI(state);
        }
        @Override public void onBatteryUpdate(int level, boolean ch) {
            tvBattery.setText(level + "%");
            appendLog("电量: " + level + "%, 充电=" + ch);
        }
        @Override public void onFirmwareVersion(String v) {
            tvFirmware.setText(v);
            appendLog("固件版本: " + v);
        }
        @Override public void onMediaFileChanged(int p, int v, int a) { appendLog("媒体文件: 照片=" + p + " 视频=" + v + " 音频=" + a); }
        @Override public void onWifiStateChange(int s) { appendLog("WiFi状态: " + s); }
        @Override public void onWifiConnectionChanged(boolean c) { appendLog("WiFi连接: " + c); }
        @Override public void onLog(String tag, String msg) { appendLog(msg); }
        @Override public void onError(String m) {
            appendLog("错误: " + m);
            if (isAdded()) Toast.makeText(getContext(), m, Toast.LENGTH_SHORT).show();
        }
    };

    private final ActivityResultLauncher<String[]> permissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean g : result.values()) if (!g) { allGranted = false; break; }
                if (allGranted) startScan();
                else Toast.makeText(getContext(), "需要蓝牙和位置权限", Toast.LENGTH_LONG).show();
            });

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        return inf.inflate(R.layout.fragment_device, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        bleService = BleService.getInstance();
        bleService.addListener(bleListener);

        layoutDisconnected = v.findViewById(R.id.layout_disconnected);
        layoutConnected = v.findViewById(R.id.layout_connected);
        tvScanStatus = v.findViewById(R.id.tv_scan_status);
        tvDeviceName = v.findViewById(R.id.tv_device_name);
        tvDeviceId = v.findViewById(R.id.tv_device_id);
        tvBattery = v.findViewById(R.id.tv_battery);
        tvSignal = v.findViewById(R.id.tv_signal);
        tvFirmware = v.findViewById(R.id.tv_firmware);
        recyclerDevices = v.findViewById(R.id.recycler_devices);
        tvDebugLog = v.findViewById(R.id.tv_debug_log);
        btnScan = v.findViewById(R.id.btn_scan);

        deviceAdapter = new ScanDeviceAdapter(deviceList, addr -> {
            appendLog("选中设备: " + addr);
            connectToDevice(addr);
        });
        recyclerDevices.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerDevices.setAdapter(deviceAdapter);

        btnScan.setOnClickListener(vi -> {
            if (isScanning) { bleService.cancelScan(); setScanningState(false); }
            else checkPermissionsAndScan();
        });

        // 调试按钮组
        v.findViewById(R.id.btn_debug_disconnect).setOnClickListener(vi -> { bleService.disconnect(); appendLog("手动断开连接"); });
        v.findViewById(R.id.btn_debug_query_battery).setOnClickListener(vi -> { bleService.queryBattery(); appendLog("已发送电量查询"); });
        v.findViewById(R.id.btn_debug_sync_time).setOnClickListener(vi -> {
            CRPBleConnection conn = bleService.getConnection();
            if (conn != null) { conn.syncTime(); appendLog("已发送时间同步"); }
            else appendLog("错误: 连接不存在");
        });
        v.findViewById(R.id.btn_debug_query_file).setOnClickListener(vi -> {
            bleService.queryNewMediaFile();
            appendLog("已发送新媒体文件查询");
        });
        v.findViewById(R.id.btn_debug_wifi_download).setOnClickListener(vi -> {
            CRPBleConnection conn = bleService.getConnection();
            if (conn == null) { appendLog("错误: 连接不存在"); return; }
            appendLog("开始 WiFi 下载流程...");
            conn.setWifiListener(new com.moyoung.glasses.conn.listener.CRPWifiChangeListener() {
                @Override public void onWifiStateChange(com.moyoung.glasses.conn.type.CRPWifiType type, int state) {
                    appendLog("WiFi状态变化: type=" + type + " state=" + state);
                    if (state == 0) { appendLog("WiFi已开启，开始连接..."); conn.connectWifi(); }
                }
                @Override public void onWifiConnectionStateChanged(boolean connected) {
                    appendLog("WiFi连接状态: " + connected);
                    if (connected) { appendLog("WiFi已连接，开始下载文件..."); bleService.downloadMediaFiles(); }
                }
                @Override public void onLiveUrlChanged(String url) { }
            });
            conn.enableWifi(com.moyoung.glasses.conn.type.CRPWifiType.FILE);
        });
        v.findViewById(R.id.btn_debug_a2dp).setOnClickListener(vi -> {
            CRPBleConnection conn = bleService.getConnection();
            if (conn == null) { appendLog("错误: 连接不存在"); return; }
            // 即使 enableDeviceBT 失败也尝试 createBond
            conn.enableDeviceBT(new CRPCommandCallback() {
                @Override public void onSuccess() { appendLog("✔ 眼镜BT已开启 (可尝试配对)"); }
                @Override public void onFailure(int code) {
                    String reason;
                    if (code == 1) reason = "电量过低";
                    else if (code == 2) reason = "超时";
                    else if (code == 3) reason = "设备忙";
                    else reason = "未知(" + code + ")";
                    appendLog("✘ 开启眼镜BT失败: " + reason + " (仍尝试配对)");
                }
            });
            try {
                android.bluetooth.BluetoothDevice btDev = BleService.getInstance().getBleDevice().getBluetoothDevice();
                java.lang.reflect.Method method = btDev.getClass().getMethod("createBond", int.class);
                method.setAccessible(true);
                boolean result = (Boolean) method.invoke(btDev, android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC);
                appendLog("createBond (classic) 返回: " + result);
            } catch (Exception e) {
                appendLog("✘ createBond 异常: " + e.getMessage());
            }
        });
        v.findViewById(R.id.btn_debug_play_tone).setOnClickListener(vi -> playTestTone());
        v.findViewById(R.id.btn_debug_clear_log).setOnClickListener(vi -> tvDebugLog.setText(""));

        // 恢复现有状态
        updateConnectionUI(bleService.getConnectionState());
        if (bleService.isConnected()) {
            tvDeviceName.setText(bleService.getDeviceName());
            tvDeviceId.setText(bleService.getConnectedAddress());
            int lvl = bleService.getBatteryLevel();
            if (lvl >= 0) tvBattery.setText(lvl + "%");
            String fw = bleService.getFirmwareVersion();
            if (!TextUtils.isEmpty(fw)) tvFirmware.setText(fw);
        }
    }

    private void checkPermissionsAndScan() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN);
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (!needed.isEmpty()) permissionsLauncher.launch(needed.toArray(new String[0]));
        else startScan();
    }

    private void startScan() {
        if (!bleService.isBluetoothEnabled()) { Toast.makeText(getContext(), "请先开启蓝牙", Toast.LENGTH_SHORT).show(); return; }
        deviceList.clear(); deviceAdapter.notifyDataSetChanged();
        setScanningState(true); tvScanStatus.setText(R.string.state_scanning);
        appendLog("开始扫描设备...");
        bleService.startScan(new CRPScanCallback() {
            @Override public void onScanning(CRPScanDevice dev) {
                if (!isAdded()) return;
                for (CRPScanDevice d : deviceList) if (d.getDevice().getAddress().equals(dev.getDevice().getAddress())) return;
                deviceList.add(dev); deviceAdapter.notifyDataSetChanged();
                appendLog("发现: " + dev.getDevice().getName() + " [" + dev.getDevice().getAddress() + "] RSSI=" + dev.getRssi());
            }
            @Override public void onScanComplete(List<CRPScanDevice> r) {
                if (!isAdded()) return;
                setScanningState(false);
                if (deviceList.isEmpty()) tvScanStatus.setText(R.string.no_device_found);
                else tvScanStatus.setText("发现 " + deviceList.size() + " 个设备");
                appendLog("扫描完成, 共 " + r.size() + " 个设备");
            }
        }, SCAN_TIMEOUT_MS);
    }

    private void connectToDevice(String addr) { tvScanStatus.setText(R.string.state_connecting); appendLog("连接: " + addr); bleService.connect(addr); }

    private void setScanningState(boolean scanning) { isScanning = scanning; if (btnScan != null) btnScan.post(() -> { TextView tv = (TextView)btnScan; if (scanning) tv.setText(R.string.cancel_scan); else tv.setText(R.string.scan_and_connect); }); }

    private void updateConnectionUI(int state) {
        if (!isAdded()) return;
        switch (state) {
            case CRPBleConnectionStateListener.STATE_CONNECTED:
                layoutDisconnected.setVisibility(View.GONE); layoutConnected.setVisibility(View.VISIBLE);
                if (recyclerDevices != null) recyclerDevices.setVisibility(View.GONE);
                tvDeviceName.setText(bleService.getDeviceName());
                tvDeviceId.setText(bleService.getConnectedAddress());
                int lvl = bleService.getBatteryLevel();
                tvBattery.setText(lvl >= 0 ? lvl + "%" : "查询中...");
                tvSignal.setText("查询中...");
                String fw = bleService.getFirmwareVersion();
                tvFirmware.setText(!TextUtils.isEmpty(fw) ? fw : "查询中...");
                break;
            case CRPBleConnectionStateListener.STATE_DISCONNECTED:
                layoutConnected.setVisibility(View.GONE); layoutDisconnected.setVisibility(View.VISIBLE);
                if (recyclerDevices != null) recyclerDevices.setVisibility(View.VISIBLE);
                tvScanStatus.setText(R.string.state_disconnected);
                break;
            case CRPBleConnectionStateListener.STATE_CONNECTING:
                layoutDisconnected.setVisibility(View.VISIBLE); layoutConnected.setVisibility(View.GONE);
                tvScanStatus.setText(R.string.state_connecting);
                break;
        }
    }

    private void appendLog(String msg) {
        if (!isAdded() || tvDebugLog == null) return;
        tvDebugLog.post(() -> {
            String cur = tvDebugLog.getText().toString();
            if (cur.length() > 5000) cur = cur.substring(cur.length() - 4000);
            tvDebugLog.setText(cur + "\n" + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date()) + " " + msg);
        });
    }

    private String stateToString(int s) {
        switch (s) { case 0: return "DISCONNECTED"; case 1: return "CONNECTING"; case 2: return "CONNECTED"; case 3: return "DISCONNECTING"; default: return "UNKNOWN(" + s + ")"; }
    }

    private android.speech.tts.TextToSpeech tts;

    @Override public void onDestroyView() {
        super.onDestroyView();
        bleService.removeListener(bleListener);
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
    }

    /** 使用 TTS 播放语音测试 A2DP 音频通道 */
    private void playTestTone() {
        if (tts == null) {
            tts = new android.speech.tts.TextToSpeech(getContext(), status -> {
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    tts.setLanguage(java.util.Locale.CHINESE);
                    doTtsSpeak();
                } else {
                    appendLog("✘ TTS初始化失败");
                }
            });
        } else {
            doTtsSpeak();
        }
    }

    private void doTtsSpeak() {
        String text = "欢迎使用齐目导览，AI智能眼镜将为您提供全程讲解服务。";
        tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "test");
        appendLog("🔊 TTS语音已播放: \"" + text + "\"");
    }

    // ── 扫描列表适配器 ──

    private static class ScanDeviceAdapter extends RecyclerView.Adapter<ScanDeviceAdapter.ViewHolder> {
        private final List<CRPScanDevice> devices;
        private final OnDeviceClickListener listener;
        interface OnDeviceClickListener { void onDeviceClick(String a); }
        ScanDeviceAdapter(List<CRPScanDevice> d, OnDeviceClickListener l) { devices = d; listener = l; }
        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new ViewHolder(LayoutInflater.from(p.getContext()).inflate(android.R.layout.simple_list_item_2, p, false));
        }
        @Override public void onBindViewHolder(@NonNull ViewHolder h, int p) {
            CRPScanDevice dev = devices.get(p);
            BluetoothDevice bt = dev.getDevice();
            h.text1.setText(bt.getName() != null ? bt.getName() : "未知设备");
            h.text2.setText(bt.getAddress() + " RSSI:" + dev.getRssi());
            h.itemView.setOnClickListener(v -> listener.onDeviceClick(bt.getAddress()));
        }
        @Override public int getItemCount() { return devices.size(); }
        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView text1, text2;
            ViewHolder(@NonNull View v) { super(v); text1 = v.findViewById(android.R.id.text1); text2 = v.findViewById(android.R.id.text2); }
        }
    }
}
