package com.qimu.guide.service;

import android.bluetooth.BluetoothAdapter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.moyoung.glasses.CRPBleClient;
import com.moyoung.glasses.conn.CRPBleConnection;
import com.moyoung.glasses.conn.CRPBleDevice;
import com.moyoung.glasses.conn.callback.CRPCommandCallback;
import com.moyoung.glasses.conn.callback.CRPDeviceVersionCallback;
import com.moyoung.glasses.conn.callback.CRPFileDownloadCallback;
import com.moyoung.glasses.conn.listener.CRPBatteryListener;
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener;
import com.moyoung.glasses.conn.listener.CRPMediaFileChangeListener;
import com.moyoung.glasses.conn.listener.CRPWifiChangeListener;
import com.moyoung.glasses.conn.protos.BatteryInfo;
import com.moyoung.glasses.conn.protos.VersionInfo;
import com.moyoung.glasses.conn.type.CRPWifiType;
import com.moyoung.glasses.scan.callback.CRPScanCallback;
import com.qimu.guide.QimuApplication;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class BleService {

    public interface BleAction { void execute(BleListener listener); }

    public interface BleListener {
        void onConnectionStateChanged(int state);
        void onBatteryUpdate(int level, boolean charging);
        void onFirmwareVersion(String version);
        void onMediaFileChanged(int photoCount, int videoCount, int audioCount);
        void onWifiStateChange(int state);
        void onWifiConnectionChanged(boolean connected);
        void onLog(String tag, String message);
        void onError(String message);
    }

    private static final String TAG = "BleService";
    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_DELAY_MS = 2000;

    private static volatile BleService instance;

    private final CRPBleClient bleClient;
    private final Handler mainHandler;
    private final List<BleListener> listeners = new CopyOnWriteArrayList<>();

    private CRPBleDevice bleDevice;
    private CRPBleConnection bleConnection;
    private String connectedAddress;
    private int retryCount = 0;
    private boolean isReconnecting = false;

    private int connectionState = CRPBleConnectionStateListener.STATE_DISCONNECTED;
    private int batteryLevel = -1;
    private String deviceName = "未知设备";
    private String firmwareVersion = "";

    public static BleService getInstance() {
        if (instance == null) {
            synchronized (BleService.class) {
                if (instance == null) {
                    instance = new BleService();
                }
            }
        }
        return instance;
    }

    private BleService() {
        bleClient = QimuApplication.getBleClient();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public void addListener(BleListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(BleListener listener) {
        listeners.remove(listener);
    }

    public boolean isBluetoothEnabled() { return bleClient.isBluetoothEnable(); }
    public boolean isConnected() { return bleDevice != null && bleDevice.isConnected(); }
    public int getConnectionState() { return connectionState; }
    public int getBatteryLevel() { return batteryLevel; }
    public String getDeviceName() { return deviceName; }
    public String getConnectedAddress() { return connectedAddress; }
    public String getFirmwareVersion() { return firmwareVersion; }

    @Nullable
    public CRPBleConnection getConnection() { return bleConnection; }

    @Nullable
    public CRPBleDevice getBleDevice() { return bleDevice; }

    public boolean startScan(CRPScanCallback callback, long timeoutMs) {
        if (!isBluetoothEnabled()) {
            notifyError("蓝牙未开启，请在系统设置中开启蓝牙");
            return false;
        }
        postLog("扫描", "开始扫描设备...");
        return bleClient.scanDevice(callback, timeoutMs);
    }

    public void cancelScan() { bleClient.cancelScan(); }

    public void connect(String address) {
        if (address == null) { notifyError("无效的设备地址"); return; }
        bleDevice = bleClient.getBleDevice(address);
        if (bleDevice == null) { notifyError("获取设备对象失败"); return; }

        connectedAddress = address;
        deviceName = bleDevice.getName() != null ? bleDevice.getName() : "未知设备";
        bleConnection = bleDevice.connect();
        postLog("连接", "准备连接: " + deviceName + " [" + address + "]");
        postLog("连接", "bleConnection对象: " + (bleConnection != null ? "获取成功" : "获取失败"));

        setupConnectionStateListener();
        setupBatteryListener();

        boolean success = bleConnection.connect();
        postLog("连接", "connection.connect() 返回: " + success);
        if (!success) notifyError("连接发起失败");
    }

    public void disconnect() {
        retryCount = 0;
        isReconnecting = false;
        batteryLevel = -1;
        firmwareVersion = "";
        if (bleDevice != null && bleDevice.isConnected()) {
            bleDevice.disconnect();
            postLog("连接", "主动断开连接");
        }
        connectedAddress = null;
    }

    /** 查询固件版本 */
    public void queryDeviceVersion() {
        if (bleConnection == null) return;
        bleConnection.queryDeviceVersion(VersionInfo.VersionType.VerFirmware,
                info -> {
                    firmwareVersion = info.getVer();
                    postLog("固件", "版本: " + firmwareVersion);
                    notifyListeners(l -> l.onFirmwareVersion(firmwareVersion));
                });
    }

    public void queryBattery() {
        if (bleConnection != null) {
            bleConnection.queryBattery();
            postLog("电量", "已发送查询指令");
        }
    }

    public void enableWifi(CRPWifiType type) {
        if (bleConnection != null) bleConnection.enableWifi(type);
    }
    public void disableWifi() {
        if (bleConnection != null) bleConnection.disableWifi();
    }
    public void connectWifi() {
        if (bleConnection != null) bleConnection.connectWifi();
    }

    /** 查询新文件数 */
    public void queryNewMediaFile() {
        if (bleConnection != null) { bleConnection.queryNewMediaFile(); postLog("媒体", "已发送查询指令"); }
    }

    /** 下载媒体文件（需要先开启 WiFi） */
    public void downloadMediaFiles() {
        if (bleConnection == null) { postLog("下载", "连接不存在"); return; }
        postLog("下载", "开始下载流程...");
        bleConnection.downloadMediaFile(new CRPFileDownloadCallback() {
            @Override public void onStart() { postLog("下载", "开始下载"); }
            @Override public void onProgress(int total, int done, int pct) { postLog("下载", "进度: " + done + "/" + total + " (" + pct + "%)"); }
            @Override public void onDownloadSpeed(int s) { postLog("下载", "速度: " + s + " B/s"); }
            @Override public void onFileDownloaded(String path) { postLog("下载", "文件完成: " + path); }
            @Override public void onAllFilesDownloaded(String dir, java.util.List<String> paths) { postLog("下载", "本轮完成, " + (paths != null ? paths.size() : 0) + " 个文件"); }
            @Override public void onSuccess() { postLog("下载", "全部下载成功"); }
            @Override public void onFail(int code) { postLog("下载", "下载失败: code=" + code); }
        });
    }

    /** 获取下载的媒体目录 */
    public String getDownloadDir() {
        return QimuApplication.getAppContext().getExternalFilesDir("downloads") != null ?
                QimuApplication.getAppContext().getExternalFilesDir("downloads").getAbsolutePath() : "";
    }

    // ── SDK 调用日志 ──────────────────────────────────────────

    public void postLog(String tag, String message) {
        Log.d(TAG, "[" + tag + "] " + message);
        if (listeners.isEmpty()) return;
        String logMsg = "[" + tag + "] " + message;
        mainHandler.post(() -> {
            for (BleListener l : listeners) {
                l.onLog(tag, logMsg);
            }
        });
    }

    // ── 连接状态监听 ─────────────────────────────────────────

    private void setupConnectionStateListener() {
        if (bleConnection == null) return;

        bleConnection.setConnectionStateListener(newState -> {
            postLog("状态", "连接状态变化: " + stateToString(newState));
            connectionState = newState;

            switch (newState) {
                case CRPBleConnectionStateListener.STATE_CONNECTED:
                    retryCount = 0;
                    isReconnecting = false;
                    onConnected();
                    break;
                case CRPBleConnectionStateListener.STATE_DISCONNECTED:
                    onDisconnected();
                    break;
            }

            notifyListeners(l -> l.onConnectionStateChanged(newState));
        });

        bleConnection.setWifiListener(new CRPWifiChangeListener() {
            @Override
            public void onWifiStateChange(CRPWifiType type, int state) {
                postLog("WiFi", "状态变化: type=" + type + ", state=" + state);
                notifyListeners(l -> l.onWifiStateChange(state));
            }
            @Override
            public void onWifiConnectionStateChanged(boolean connected) {
                postLog("WiFi", "连接状态: " + connected);
                notifyListeners(l -> l.onWifiConnectionChanged(connected));
            }
            @Override
            public void onLiveUrlChanged(String url) {
                postLog("WiFi", "直播地址: " + url);
            }
        });

        bleConnection.setMediaFileChangeListener(info -> {
            postLog("媒体", "新文件: 照片=" + info.getPhotoCount()
                    + ", 视频=" + info.getVideoCount() + ", 音频=" + info.getAudioCount());
            notifyListeners(l -> l.onMediaFileChanged(
                    info.getPhotoCount(), info.getVideoCount(), info.getAudioCount()));
        });
    }

    private void setupBatteryListener() {
        if (bleConnection == null) return;
        bleConnection.setBatteryListener(info -> {
            batteryLevel = info.getLvl();
            postLog("电量", info.getLvl() + "%, 充电=" + info.getCharging());
            notifyListeners(l -> l.onBatteryUpdate(info.getLvl(), info.getCharging()));
        });
    }

    private void onConnected() {
        postLog("连接", "设备已连接成功");
        if (bleConnection != null) {
            bleConnection.syncTime();
            postLog("连接", "时间同步已发送");
            queryBattery();
            queryDeviceVersion();
        }
    }

    private void onDisconnected() {
        postLog("连接", "设备已断开");
        if (retryCount < MAX_RETRY_COUNT && connectedAddress != null) {
            isReconnecting = true;
            retryCount++;
            postLog("重连", "第 " + retryCount + "/" + MAX_RETRY_COUNT + " 次尝试...");
            mainHandler.postDelayed(() -> {
                if (bleConnection != null) {
                    bleConnection.connect();
                }
            }, RETRY_DELAY_MS);
        } else {
            isReconnecting = false;
            if (retryCount >= MAX_RETRY_COUNT) {
                notifyError("重连失败，已达最大重试次数");
            }
            connectedAddress = null;
            bleDevice = null;
            bleConnection = null;
        }
    }

    public boolean isReconnecting() { return isReconnecting; }
    public void resetRetryCount() { retryCount = 0; }

    private String stateToString(int state) {
        switch (state) {
            case CRPBleConnectionStateListener.STATE_CONNECTED: return "CONNECTED(2)";
            case CRPBleConnectionStateListener.STATE_CONNECTING: return "CONNECTING(1)";
            case CRPBleConnectionStateListener.STATE_DISCONNECTED: return "DISCONNECTED(0)";
            case CRPBleConnectionStateListener.STATE_DISCONNECTING: return "DISCONNECTING(3)";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    // ── 辅助 ────────────────────────────────────────────────

    private void notifyError(String message) {
        Log.e(TAG, message);
        notifyListeners(l -> l.onError(message));
    }

    private void notifyListeners(BleAction action) {
        if (listeners.isEmpty()) return;
        mainHandler.post(() -> {
            for (BleListener l : listeners) {
                action.execute(l);
            }
        });
    }
}
