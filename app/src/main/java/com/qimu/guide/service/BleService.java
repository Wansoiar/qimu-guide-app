package com.qimu.guide.service;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.moyoung.glasses.CRPBleClient;
import com.moyoung.glasses.conn.CRPBleConnection;
import com.moyoung.glasses.conn.CRPBleDevice;
import com.moyoung.glasses.conn.callback.CRPCommandCallback;
import com.moyoung.glasses.conn.callback.CRPFileDownloadCallback;
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener;
import com.moyoung.glasses.conn.listener.CRPWifiChangeListener;
import com.moyoung.glasses.conn.protos.RunningStatus;
import com.moyoung.glasses.conn.protos.VersionInfo;
import com.moyoung.glasses.conn.type.CRPWifiType;
import com.qimu.guide.QimuApplication;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class BleService {

    public interface BleAction { void execute(BleListener listener); }

    public interface GlassesScanCallback {
        void onScanning(GlassesScanResult result);
        void onScanComplete(int resultCount);
        void onScanFailed(int errorCode);
    }

    public static final class GlassesScanResult {
        private final BluetoothDevice device;
        private final byte[] scanRecord;
        private final int rssi;

        GlassesScanResult(BluetoothDevice device, byte[] scanRecord, int rssi) {
            this.device = device;
            this.scanRecord = scanRecord;
            this.rssi = rssi;
        }

        public BluetoothDevice getDevice() { return device; }
        public byte[] getScanRecord() { return scanRecord; }
        public int getRssi() { return rssi; }
    }

    public interface BleListener {
        void onConnectionStateChanged(int state);
        default void onAudioConnectionStateChanged(int state) { }
        void onBatteryUpdate(int level, boolean charging);
        void onFirmwareVersion(String version);
        void onMediaFileChanged(int photoCount, int videoCount, int audioCount);
        void onWifiStateChange(int state);
        void onWifiConnectionChanged(boolean connected);
        void onLog(String tag, String message);
        void onError(String message);
    }

    /** UI observer for the service-owned Wi-Fi/media transfer state machine. */
    public interface MediaDownloadListener {
        default void onStageChanged(String message) { }
        default void onProgress(int total, int downloaded, int percent) { }
        default void onFileDownloaded(String path) { }
        default void onCompleted(String directory, List<String> paths) { }
        default void onFailed(int code, String message) { }
        default void onCancelled() { }
    }

    public interface ReturnResetCallback {
        void onFinished(boolean success, int errorCode);
    }

    /** Immutable identity and local-media snapshot captured when a return starts. */
    public static final class ReturnTarget {
        private final String address;
        private final CRPBleConnection connectionIdentity;
        private final int connectionGeneration;
        private final int transactionGeneration;
        private final boolean resetEligible;
        private final String downloadDirectory;

        private ReturnTarget(String address, CRPBleConnection connectionIdentity,
                             int connectionGeneration,
                             int transactionGeneration, boolean resetEligible,
                             String downloadDirectory) {
            this.address = address;
            this.connectionIdentity = connectionIdentity;
            this.connectionGeneration = connectionGeneration;
            this.transactionGeneration = transactionGeneration;
            this.resetEligible = resetEligible;
            this.downloadDirectory = downloadDirectory;
        }

        String getAddress() { return address; }
        int getConnectionGeneration() { return connectionGeneration; }
        boolean isResetEligible() { return resetEligible; }
        String getDownloadDirectory() { return downloadDirectory; }
    }

    private static final String TAG = "BleService";
    public static final int AUDIO_STATE_DISCONNECTED = 0;
    public static final int AUDIO_STATE_CONNECTING = 1;
    public static final int AUDIO_STATE_CONNECTED = 2;
    private static final String PREFS_NAME = "glasses_connection";
    private static final String KEY_LAST_ADDRESS = "last_ble_address";
    private static final String KEY_LAST_MEDIA_DIR = "last_media_directory";
    private static final long CONNECT_TIMEOUT_MS = 15000L;
    private static final long AUDIO_CONNECTION_DELAY_MS = 1200L;
    private static final long FAST_RETRY_DELAY_MS = 2000L;
    private static final long BACKGROUND_RETRY_DELAY_MS = 30000L;
    private static final long WIFI_ENABLE_TIMEOUT_MS = 35000L;
    private static final long WIFI_CONNECT_TIMEOUT_MS = 120000L;
    private static final long WIFI_RETRY_DELAY_MS = 6000L;
    private static final long DOWNLOAD_IDLE_TIMEOUT_MS = 180000L;
    private static final long RETURN_RESET_TIMEOUT_MS = 10000L;
    private static final long NEXT_VISITOR_RECONNECT_DELAY_MS = 8000L;
    private static final long WIFI_PREFLIGHT_DELAY_MS = 500L;
    private static final int MAX_WIFI_BUSY_RETRIES = 2;
    // Vendor flow: try P2P GO three times, then use AP for the fourth attempt.
    private static final int MAX_WIFI_CONNECTION_ATTEMPTS = 4;
    private static final String[] FORCED_AP_FIRMWARE_VERSIONS = {
            "MOY-A073-0.1.0", "MOY-A073-0.0.7", "MOY-A073-0.0.6", "MOY-A073-0.0.3",
            "MOY-A253-0.0.8", "MOY-A253-0.0.5", "MOY-A253-0.0.4", "MOY-A253-0.0.3"
    };

    private static final int MEDIA_IDLE = 0;
    private static final int MEDIA_ENABLING_WIFI = 1;
    private static final int MEDIA_WAITING_WIFI_APPROVAL = 2;
    private static final int MEDIA_DOWNLOADING = 3;
    private static final int MEDIA_RETRY_WAIT = 4;
    private static final int MEDIA_PREPARING_WIFI = 5;
    private static final ParcelUuid GLASSES_SERVICE_UUID = ParcelUuid.fromString(
            "0000FEA8-0000-1000-8000-00805F9B34FB");

    private static volatile BleService instance;

    private final CRPBleClient bleClient;
    private final Context appContext;
    private final SharedPreferences preferences;
    private final Handler mainHandler;
    private final List<BleListener> listeners = new CopyOnWriteArrayList<>();
    private final Object scanLock = new Object();
    private final BluetoothAudioCoordinator bluetoothAudioCoordinator;

    private BluetoothLeScanner bluetoothLeScanner;
    private ScanCallback platformScanCallback;
    private GlassesScanCallback glassesScanCallback;
    private Runnable scanTimeoutRunnable;
    private int scanResultCount;

    private CRPBleDevice bleDevice;
    private CRPBleConnection bleConnection;
    private String connectedAddress;
    private int retryCount = 0;
    private boolean isReconnecting = false;
    private boolean userInitiatedDisconnect;
    private int connectionGeneration;
    private Runnable reconnectRunnable;
    private Runnable audioConnectionRunnable;
    private Runnable returnResetTimeoutRunnable;
    private Runnable nextVisitorReconnectRunnable;
    private int returnResetGeneration;
    private boolean returnResetInProgress;
    private int returnTransactionGeneration;
    private boolean returnTransactionInProgress;
    private ReturnTarget activeReturnTarget;
    private final Runnable connectTimeoutTask = this::onConnectTimeout;

    private int connectionState = CRPBleConnectionStateListener.STATE_DISCONNECTED;
    private int audioConnectionState = AUDIO_STATE_DISCONNECTED;
    private int batteryLevel = -1;
    private String deviceName = "未知设备";
    private String firmwareVersion = "";

    private MediaDownloadListener mediaDownloadListener;
    private int mediaState = MEDIA_IDLE;
    private int mediaGeneration;
    private int wifiBusyRetryCount;
    private int wifiConnectionAttempt;
    private int wifiAttemptToken;
    private boolean wifiUseApMode;
    private boolean wifiSystemApprovalExpected;
    private RunningStatus latestMediaFeatureState;
    private Runnable mediaTimeoutRunnable;
    private String lastDownloadDir;
    private final List<String> lastDownloadedPaths = new ArrayList<>();

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
        appContext = QimuApplication.getAppContext();
        bleClient = QimuApplication.getBleClient();
        preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mainHandler = new Handler(Looper.getMainLooper());
        lastDownloadDir = preferences.getString(KEY_LAST_MEDIA_DIR, "");
        bluetoothAudioCoordinator = new BluetoothAudioCoordinator(
                appContext,
                mainHandler,
                new BluetoothAudioCoordinator.Callback() {
                    @Override
                    public void onPairingStateChanged(boolean active) {
                        postLog("音频", active ? "正在准备蓝牙音频连接" : "蓝牙音频配对流程已结束");
                    }

                    @Override
                    public void onAudioConnectionStateChanged(int state) {
                        setAudioConnectionState(state);
                    }

                    @Override
                    public void onPairingFinished(boolean success) {
                        if (connectionState != CRPBleConnectionStateListener.STATE_CONNECTED
                                && connectedAddress != null && !userInitiatedDisconnect) {
                            scheduleReconnect(success ? "音频配对完成" : "音频配对结束");
                        }
                    }

                    @Override
                    public void onBluetoothAdapterEnabled() {
                        autoReconnectLastDevice();
                    }

                    @Override public void onLog(String message) { postLog("音频", message); }
                    @Override public void onError(String message) { notifyError(message); }
                });
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
    public boolean isConnected() {
        if (connectionState == CRPBleConnectionStateListener.STATE_CONNECTED) return true;
        try {
            return bleDevice != null && bleDevice.isConnected();
        } catch (SecurityException ignored) {
            return false;
        }
    }
    public int getConnectionState() { return connectionState; }
    public int getAudioConnectionState() { return audioConnectionState; }
    public int getBatteryLevel() { return batteryLevel; }
    public String getDeviceName() { return deviceName; }
    public String getConnectedAddress() { return connectedAddress; }
    public String getFirmwareVersion() { return firmwareVersion; }

    @Nullable
    public CRPBleConnection getConnection() { return bleConnection; }

    @Nullable
    public CRPBleDevice getBleDevice() { return bleDevice; }

    /**
     * Freezes the glasses identity and media directory for an irreversible return.
     * This method is intentionally synchronous and main-thread only so no connection
     * callback can replace the target between capture and activation of the gate.
     */
    @Nullable
    public ReturnTarget beginReturnTransaction() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.e(TAG, "归还事务必须在主线程启动");
            return null;
        }
        if (returnTransactionInProgress) return null;

        cancelNextVisitorReconnect();
        cancelPendingReconnect();
        cancelPendingAudioConnection();
        if (mediaState != MEDIA_IDLE) cancelMediaDownload();
        bluetoothAudioCoordinator.cancel();
        userInitiatedDisconnect = true;

        returnTransactionInProgress = true;
        int transaction = ++returnTransactionGeneration;
        boolean resetEligible = bleConnection != null
                && connectionState == CRPBleConnectionStateListener.STATE_CONNECTED
                && !TextUtils.isEmpty(connectedAddress);
        String targetAddress = !TextUtils.isEmpty(connectedAddress)
                ? connectedAddress
                : preferences.getString(KEY_LAST_ADDRESS, "");
        ReturnTarget target = new ReturnTarget(targetAddress, bleConnection,
                connectionGeneration,
                transaction, resetEligible, getDownloadDir());
        activeReturnTarget = target;
        postLog("归还", "已锁定归还目标: "
                + (TextUtils.isEmpty(targetAddress) ? "无已知眼镜" : targetAddress)
                + ", connectionGeneration=" + connectionGeneration
                + ", resetEligible=" + resetEligible);
        return target;
    }

    public boolean startScan(GlassesScanCallback callback, long timeoutMs) {
        if (!isBluetoothEnabled()) {
            notifyError("蓝牙未开启，请在系统设置中开启蓝牙");
            return false;
        }
        if (callback == null) return false;

        cancelScan();
        BluetoothManager manager = (BluetoothManager) QimuApplication.getAppContext()
                .getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        BluetoothLeScanner scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (scanner == null) {
            notifyError("无法启动蓝牙扫描");
            return false;
        }

        // FEA8 is carried in the Service Data AD structure rather than the
        // advertised service UUID list. Filtering here prevents unrelated BLE
        // broadcasts from reaching the app and avoids the SDK's unfiltered
        // legacy scan path.
        ScanFilter glassesFilter = new ScanFilter.Builder()
                .setServiceData(GLASSES_SERVICE_UUID, new byte[0])
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build();

        ScanCallback scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                dispatchScanResult(this, result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                if (results == null) return;
                for (ScanResult result : results) dispatchScanResult(this, result);
            }

            @Override
            public void onScanFailed(int errorCode) {
                finishPlatformScan(this, false, errorCode);
            }
        };
        Runnable timeout = () -> finishPlatformScan(scanCallback, true, null);

        synchronized (scanLock) {
            bluetoothLeScanner = scanner;
            platformScanCallback = scanCallback;
            glassesScanCallback = callback;
            scanTimeoutRunnable = timeout;
            scanResultCount = 0;
        }

        postLog("扫描", "开始扫描设备...");
        try {
            scanner.startScan(Collections.singletonList(glassesFilter), settings, scanCallback);
            mainHandler.postDelayed(timeout, timeoutMs);
            return true;
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            finishPlatformScan(scanCallback, false, null);
            Log.e(TAG, "启动眼镜扫描失败", e);
            return false;
        }
    }

    public void cancelScan() { finishPlatformScan(null, false, null); }

    private void dispatchScanResult(ScanCallback source, ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        GlassesScanCallback callback;
        synchronized (scanLock) {
            if (source != platformScanCallback) return;
            callback = glassesScanCallback;
            scanResultCount++;
        }
        if (callback == null) return;
        byte[] record = result.getScanRecord() == null
                ? null
                : result.getScanRecord().getBytes();
        callback.onScanning(new GlassesScanResult(
                result.getDevice(), record, result.getRssi()));
    }

    private void finishPlatformScan(@Nullable ScanCallback expectedCallback,
                                    boolean notifyComplete,
                                    @Nullable Integer errorCode) {
        BluetoothLeScanner scanner;
        ScanCallback scanCallback;
        GlassesScanCallback callback;
        Runnable timeout;
        int resultCount;
        synchronized (scanLock) {
            if (platformScanCallback == null
                    || (expectedCallback != null && expectedCallback != platformScanCallback)) return;
            scanner = bluetoothLeScanner;
            scanCallback = platformScanCallback;
            callback = glassesScanCallback;
            timeout = scanTimeoutRunnable;
            resultCount = scanResultCount;
            bluetoothLeScanner = null;
            platformScanCallback = null;
            glassesScanCallback = null;
            scanTimeoutRunnable = null;
            scanResultCount = 0;
        }
        if (timeout != null) mainHandler.removeCallbacks(timeout);
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (IllegalStateException | SecurityException e) {
                Log.w(TAG, "停止眼镜扫描失败", e);
            }
        }
        if (callback == null) return;
        if (errorCode != null) callback.onScanFailed(errorCode);
        else if (notifyComplete) callback.onScanComplete(resultCount);
    }

    public void connect(String address) {
        if (returnTransactionInProgress) {
            notifyError("正在归还眼镜，暂时不能切换设备");
            return;
        }
        if (TextUtils.isEmpty(address)) {
            notifyError("无效的设备地址");
            return;
        }
        if (!hasBluetoothConnectPermission()) {
            notifyError("缺少蓝牙连接权限");
            return;
        }
        if (bluetoothAudioCoordinator.isPairingActive()) {
            notifyError("眼镜正在进行蓝牙音频配对，请先在系统弹窗中完成确认");
            return;
        }

        cancelScan();
        cancelNextVisitorReconnect();
        userInitiatedDisconnect = false;
        connectedAddress = address;
        retryCount = 0;
        isReconnecting = false;
        cancelPendingReconnect();
        cleanupConnection();
        doConnect();
    }

    /** Reconnects the last successfully connected glasses without scanning. */
    public void autoReconnectLastDevice() {
        if (returnTransactionInProgress) {
            postLog("归还", "归还事务进行中，忽略自动重连");
            return;
        }
        if (nextVisitorReconnectRunnable != null) {
            postLog("归还", "眼镜重启等待期内，暂不提前自动重连");
            return;
        }
        if (!hasBluetoothConnectPermission() || !isBluetoothEnabled()) return;
        if (bluetoothAudioCoordinator.isPairingActive()) {
            postLog("重连", "经典蓝牙正在配对，忽略本次前台 BLE 重连请求");
            return;
        }
        if (connectionState == CRPBleConnectionStateListener.STATE_CONNECTED
                || connectionState == CRPBleConnectionStateListener.STATE_CONNECTING
                || reconnectRunnable != null) return;

        String savedAddress = preferences.getString(KEY_LAST_ADDRESS, "");
        if (TextUtils.isEmpty(savedAddress)) return;
        userInitiatedDisconnect = false;
        connectedAddress = savedAddress;
        retryCount = 0;
        postLog("重连", "自动连接上次使用的眼镜: " + savedAddress);
        cleanupConnection();
        doConnect();
    }

    /** Runs the BT/A2DP preflight again; mainly useful from diagnostics. */
    public void ensureBluetoothAudioConnection() {
        if (returnTransactionInProgress) {
            notifyError("正在归还眼镜，暂时不能重连音频");
            return;
        }
        if (!isConnected() || bleConnection == null || bleDevice == null) {
            notifyError("请先连接眼镜 BLE");
            return;
        }
        cancelPendingAudioConnection();
        setAudioConnectionState(AUDIO_STATE_CONNECTING);
        bluetoothAudioCoordinator.ensureAudioConnection(bleConnection, bleDevice);
    }

    /** Creates a fresh SDK device/connection and starts a connection watchdog. */
    private void doConnect() {
        if (returnTransactionInProgress
                || userInitiatedDisconnect || TextUtils.isEmpty(connectedAddress)) return;
        if (!hasBluetoothConnectPermission() || !isBluetoothEnabled()) return;
        if (bluetoothAudioCoordinator.isPairingActive()) {
            postLog("重连", "经典蓝牙正在配对，暂不重建 GATT");
            return;
        }

        cleanupConnection();
        final int generation = ++connectionGeneration;
        CRPBleDevice newDevice = bleClient.getBleDevice(connectedAddress);
        if (newDevice == null) {
            notifyError("获取设备对象失败");
            scheduleReconnect("设备对象不可用");
            return;
        }

        bleDevice = newDevice;
        try {
            String name = newDevice.getName();
            deviceName = TextUtils.isEmpty(name) ? "齐目眼镜" : name;
        } catch (SecurityException ignored) {
            deviceName = "齐目眼镜";
        }

        CRPBleConnection newConnection;
        try {
            newConnection = newDevice.connect();
        } catch (RuntimeException e) {
            Log.e(TAG, "创建 BLE 连接失败", e);
            scheduleReconnect("创建连接失败");
            return;
        }
        if (newConnection == null) {
            notifyError("获取连接对象失败");
            scheduleReconnect("连接对象为空");
            return;
        }

        bleConnection = newConnection;
        setupConnectionStateListener(newConnection, generation);
        setupBatteryListener(newConnection, generation);
        postLog("连接", "准备连接: " + deviceName + " [" + connectedAddress + "]");

        boolean started;
        try {
            started = newConnection.connect();
        } catch (RuntimeException e) {
            Log.e(TAG, "发起 BLE 连接失败", e);
            started = false;
        }
        postLog("连接", "connection.connect() 返回: " + started);
        if (!started) {
            scheduleReconnect("连接发起失败");
            return;
        }

        connectionState = CRPBleConnectionStateListener.STATE_CONNECTING;
        notifyListeners(l -> l.onConnectionStateChanged(connectionState));
        mainHandler.removeCallbacks(connectTimeoutTask);
        mainHandler.postDelayed(connectTimeoutTask, CONNECT_TIMEOUT_MS);
    }

    private void onConnectTimeout() {
        if (connectionState == CRPBleConnectionStateListener.STATE_CONNECTED
                || userInitiatedDisconnect) return;
        postLog("连接", "连接超时（" + (CONNECT_TIMEOUT_MS / 1000) + " 秒未就绪）");
        scheduleReconnect("连接超时");
    }

    private void scheduleReconnect(String reason) {
        if (returnTransactionInProgress
                || userInitiatedDisconnect || TextUtils.isEmpty(connectedAddress)) return;
        if (bluetoothAudioCoordinator.isPairingActive()) {
            postLog("重连", "经典蓝牙正在配对，暂缓 BLE 重连");
            return;
        }

        cancelPendingReconnect();
        cleanupConnection();
        isReconnecting = true;
        retryCount++;
        long delay = retryCount <= 3
                ? FAST_RETRY_DELAY_MS * retryCount
                : BACKGROUND_RETRY_DELAY_MS;
        postLog("重连", reason + "，" + (delay / 1000) + " 秒后第 "
                + retryCount + " 次重连");
        reconnectRunnable = () -> {
            reconnectRunnable = null;
            doConnect();
        };
        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    private void cancelPendingReconnect() {
        if (reconnectRunnable != null) {
            mainHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    private void cancelPendingAudioConnection() {
        if (audioConnectionRunnable != null) {
            mainHandler.removeCallbacks(audioConnectionRunnable);
            audioConnectionRunnable = null;
        }
    }

    private void cancelNextVisitorReconnect() {
        if (nextVisitorReconnectRunnable != null) {
            mainHandler.removeCallbacks(nextVisitorReconnectRunnable);
            nextVisitorReconnectRunnable = null;
        }
    }

    /** Releases the current GATT. Stale callbacks are ignored by generation. */
    private void cleanupConnection() {
        mainHandler.removeCallbacks(connectTimeoutTask);
        cancelPendingAudioConnection();
        connectionGeneration++;
        CRPBleConnection oldConnection = bleConnection;
        CRPBleDevice oldDevice = bleDevice;
        bleConnection = null;
        bleDevice = null;
        if (oldConnection != null) {
            try { oldConnection.close(); } catch (RuntimeException ignored) { }
        }
        if (oldDevice != null) {
            try {
                if (oldDevice.isConnected()) oldDevice.disconnect();
            } catch (RuntimeException ignored) { }
        }
    }

    /** Stops this session, while retaining the saved device for the next app launch. */
    public void disconnect() {
        if (returnTransactionInProgress) {
            notifyError("正在归还眼镜，暂时不能断开目标设备");
            return;
        }
        disconnectInternal();
    }

    private void disconnectInternal() {
        userInitiatedDisconnect = true;
        retryCount = 0;
        isReconnecting = false;
        batteryLevel = -1;
        firmwareVersion = "";
        cancelPendingReconnect();
        cancelNextVisitorReconnect();
        cancelMediaDownload();
        bluetoothAudioCoordinator.cancel();
        setAudioConnectionState(AUDIO_STATE_DISCONNECTED);
        cleanupConnection();
        connectedAddress = null;
        connectionState = CRPBleConnectionStateListener.STATE_DISCONNECTED;
        postLog("连接", "主动断开连接");
        notifyListeners(l -> l.onConnectionStateChanged(connectionState));
    }

    /**
     * Resets the glasses for visitor return without first closing the SDK connection.
     * The callback is completed exactly once on the main thread, including a 10 second timeout.
     */
    public boolean resetForReturn(ReturnTarget target, ReturnResetCallback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.e(TAG, "眼镜归还重置必须在主线程执行");
            mainHandler.post(() -> callback.onFinished(false, -7));
            return false;
        }
        if (!isCurrentReturnTarget(target)) {
            callback.onFinished(false, -6);
            return false;
        }
        if (returnResetInProgress) {
            callback.onFinished(false, -4);
            return false;
        }
        if (!target.resetEligible) {
            callback.onFinished(false, -2);
            return false;
        }
        final CRPBleConnection connection = bleConnection;
        if (connection == null
                || connection != target.connectionIdentity
                || connectionGeneration != target.connectionGeneration
                || !TextUtils.equals(connectedAddress, target.address)
                || connectionState != CRPBleConnectionStateListener.STATE_CONNECTED) {
            postLog("归还", "归还目标连接已变化，拒绝重置当前设备");
            callback.onFinished(false, -5);
            return false;
        }

        returnResetInProgress = true;
        userInitiatedDisconnect = true;
        cancelPendingReconnect();
        cancelPendingAudioConnection();
        mainHandler.removeCallbacks(connectTimeoutTask);
        if (mediaState != MEDIA_IDLE) cancelMediaDownload();
        bluetoothAudioCoordinator.cancel();
        setAudioConnectionState(AUDIO_STATE_DISCONNECTED);

        final int resetGeneration = ++returnResetGeneration;
        returnResetTimeoutRunnable = () -> finishReturnReset(
                resetGeneration, false, -1, callback);
        mainHandler.postDelayed(returnResetTimeoutRunnable, RETURN_RESET_TIMEOUT_MS);
        postLog("归还", "正在重置眼镜并清理眼镜端媒体");
        try {
            connection.reset(new CRPCommandCallback() {
                @Override
                public void onSuccess() {
                    mainHandler.post(() -> finishReturnReset(
                            resetGeneration, true, 0, callback));
                }

                @Override
                public void onFailure(int code) {
                    mainHandler.post(() -> finishReturnReset(
                            resetGeneration, false, code, callback));
                }
            });
        } catch (RuntimeException e) {
            Log.e(TAG, "眼镜 reset 调用失败", e);
            finishReturnReset(resetGeneration, false, -3, callback);
        }
        return true;
    }

    private void finishReturnReset(int generation, boolean success, int errorCode,
                                   ReturnResetCallback callback) {
        if (!returnResetInProgress || generation != returnResetGeneration) return;
        returnResetInProgress = false;
        if (returnResetTimeoutRunnable != null) {
            mainHandler.removeCallbacks(returnResetTimeoutRunnable);
            returnResetTimeoutRunnable = null;
        }
        postLog("归还", success ? "眼镜重置成功" : "眼镜重置未确认，错误码 " + errorCode);
        callback.onFinished(success, errorCode);
    }

    /** Clears persisted media references only after the captured SDK directory was deleted. */
    public boolean clearReturnedMediaState(ReturnTarget target) {
        if (Looper.myLooper() != Looper.getMainLooper() || !isCurrentReturnTarget(target)) {
            return false;
        }
        if (!preferences.edit().remove(KEY_LAST_MEDIA_DIR).commit()) {
            postLog("归还", "无法清除本地媒体目录记录");
            return false;
        }
        lastDownloadDir = "";
        lastDownloadedPaths.clear();
        return true;
    }

    /** Disconnects this visitor and reconnects the remembered glasses after their reboot. */
    public void prepareForNextVisitor(ReturnTarget target) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> prepareForNextVisitor(target));
            return;
        }
        if (!isCurrentReturnTarget(target)) {
            postLog("归还", "忽略过期归还事务的收尾请求");
            return;
        }
        cancelNextVisitorReconnect();
        try {
            disconnectInternal();
        } finally {
            returnTransactionInProgress = false;
            activeReturnTarget = null;
        }
        nextVisitorReconnectRunnable = () -> {
            nextVisitorReconnectRunnable = null;
            autoReconnectLastDevice();
        };
        mainHandler.postDelayed(nextVisitorReconnectRunnable, NEXT_VISITOR_RECONNECT_DELAY_MS);
    }

    private boolean isCurrentReturnTarget(@Nullable ReturnTarget target) {
        return returnTransactionInProgress && target != null && target == activeReturnTarget
                && target.transactionGeneration == returnTransactionGeneration;
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

    /** 查询新文件数 */
    public void queryNewMediaFile() {
        if (returnTransactionInProgress) {
            postLog("归还", "归还事务进行中，忽略媒体文件查询");
            return;
        }
        if (bleConnection != null) { bleConnection.queryNewMediaFile(); postLog("媒体", "已发送查询指令"); }
    }

    public void setMediaDownloadListener(@Nullable MediaDownloadListener listener) {
        mediaDownloadListener = listener;
        if (listener != null && mediaState != MEDIA_IDLE) {
            listener.onStageChanged(mediaStateDescription());
        }
    }

    public void removeMediaDownloadListener(MediaDownloadListener listener) {
        if (mediaDownloadListener == listener) mediaDownloadListener = null;
    }

    public boolean isMediaDownloadActive() { return mediaState != MEDIA_IDLE; }

    /**
     * Executes the complete SDK transfer protocol. The state lives here rather
     * than in a Fragment so tab switches cannot orphan the Wi-Fi channel.
     */
    public boolean startMediaDownload() {
        if (returnTransactionInProgress) {
            notifyMediaFailure(-1, "正在归还眼镜，暂时不能导出照片");
            return false;
        }
        if (!isConnected() || bleConnection == null) {
            notifyMediaFailure(-1, "请先连接眼镜");
            return false;
        }
        if (mediaState != MEDIA_IDLE) {
            notifyMediaStage("已有照片导出任务正在进行");
            return false;
        }

        mediaGeneration++;
        wifiBusyRetryCount = 0;
        wifiConnectionAttempt = 1;
        wifiUseApMode = shouldUseApFirst(firmwareVersion);
        wifiSystemApprovalExpected = false;
        lastDownloadedPaths.clear();
        prepareMediaWifi(mediaGeneration);
        return true;
    }

    /** Backwards-compatible diagnostic entry point; now uses the same state machine. */
    public void downloadMediaFiles() { startMediaDownload(); }

    public void cancelMediaDownload() {
        if (mediaState == MEDIA_IDLE) return;
        int cancelledGeneration = ++mediaGeneration;
        cancelMediaTimeout();
        mediaState = MEDIA_IDLE;
        CRPBleConnection current = bleConnection;
        if (current != null) {
            try { current.cancelMediaFileDownload(); } catch (RuntimeException ignored) { }
        }
        cleanupWifiChannel();
        postLog("下载", "用户取消照片导出, generation=" + cancelledGeneration);
        MediaDownloadListener listener = mediaDownloadListener;
        if (listener != null) mainHandler.post(listener::onCancelled);
    }

    /**
     * Mirrors the vendor app's preflight. A previous FILE/LIVE task can leave
     * the glasses' Wi-Fi state occupied even though the phone is disconnected;
     * sending another enable command in that state is accepted over BLE but
     * never produces the Wi-Fi-ready callback.
     */
    private void prepareMediaWifi(int generation) {
        CRPBleConnection current = bleConnection;
        if (generation != mediaGeneration || current == null || !isConnected()) {
            failMediaTransfer(-1, "眼镜 BLE 已断开");
            return;
        }

        mediaState = MEDIA_PREPARING_WIFI;
        latestMediaFeatureState = null;
        notifyMediaStage("正在检查眼镜 Wi-Fi 状态…");
        try {
            current.setFeatureActiveStateListener(status -> mainHandler.post(() -> {
                if (generation != mediaGeneration || current != bleConnection) return;
                latestMediaFeatureState = status;
                postLog("WiFi", "任务预检: " + describeWifiTasks(status));
            }));
            current.queryFeatureActiveState();
        } catch (RuntimeException queryFailure) {
            Log.w(TAG, "查询眼镜任务状态失败，将先清理 Wi-Fi", queryFailure);
        }

        mainHandler.postDelayed(() -> finishMediaWifiPreflight(generation, current),
                WIFI_PREFLIGHT_DELAY_MS);
    }

    private void finishMediaWifiPreflight(int generation, CRPBleConnection connection) {
        if (generation != mediaGeneration || connection != bleConnection
                || mediaState != MEDIA_PREPARING_WIFI) return;

        RunningStatus status = latestMediaFeatureState;
        if (!hasActiveWifiTask(status)) {
            beginWifiEnable(generation);
            return;
        }

        mediaState = MEDIA_RETRY_WAIT;
        notifyMediaStage(status == null
                ? "未读取到眼镜任务状态，正在先清理 Wi-Fi…"
                : "检测到眼镜上次 Wi-Fi 任务，正在关闭后重试…");
        cleanupWifiChannel();
        mainHandler.postDelayed(() -> {
            if (generation == mediaGeneration && mediaState == MEDIA_RETRY_WAIT) {
                beginWifiEnable(generation);
            }
        }, WIFI_RETRY_DELAY_MS);
    }

    /** A missing fresh response is treated as busy, matching the vendor app. */
    private boolean hasActiveWifiTask(@Nullable RunningStatus status) {
        return status == null
                || status.getFileSync()
                || status.getLivingMode()
                || status.getSlaveOta()
                || status.getSlaveActive();
    }

    private String describeWifiTasks(@Nullable RunningStatus status) {
        if (status == null) return "无响应";
        return "fileSync=" + status.getFileSync()
                + ", living=" + status.getLivingMode()
                + ", slaveOta=" + status.getSlaveOta()
                + ", slaveActive=" + status.getSlaveActive();
    }

    private void beginWifiEnable(int generation) {
        if (generation != mediaGeneration || bleConnection == null || !isConnected()) {
            failMediaTransfer(-1, "眼镜 BLE 已断开");
            return;
        }
        final int attemptToken = ++wifiAttemptToken;
        mediaState = MEDIA_ENABLING_WIFI;
        notifyMediaStage(wifiConnectionAttempt > 1
                ? "正在重试开启眼镜 Wi-Fi…"
                : "正在开启眼镜 Wi-Fi…");
        postLog("WiFi", "开启 FILE Wi-Fi，连接尝试 " + wifiConnectionAttempt);
        try {
            // The vendor app's DeviceCmdManager does this immediately before
            // calling enableWifi(). Without it, the first heartbeat request
            // from the glasses is answered with the SDK's disable-Wi-Fi
            // packet, so enable always times out.
            if (!setSdkWifiHeartbeatAlive(true)) {
                failMediaTransfer(-1, "当前 SDK 无法启动眼镜 Wi-Fi 心跳");
                return;
            }
            // Use the vendor firmware policy plus the scoped Huawei/AP
            // compatibility decision made from the real-device test below.
            if (!configureSdkWifiTransport(wifiUseApMode)) {
                failMediaTransfer(-1, "当前 SDK 无法配置眼镜 Wi-Fi 连接方式");
                return;
            }
            // The SDK stores one process-wide listener. Reinstall ours before
            // every attempt so no screen or stale task can steal callbacks.
            bleConnection.setWifiListener(createWifiListener(generation, attemptToken));
            bleConnection.enableWifi(CRPWifiType.FILE);
            scheduleMediaTimeout(generation, WIFI_ENABLE_TIMEOUT_MS,
                    () -> {
                        if (attemptToken == wifiAttemptToken
                                && mediaState == MEDIA_ENABLING_WIFI) {
                            failMediaTransfer(CRPWifiChangeListener.STATE_TIMEOUT,
                                    wifiStateError(CRPWifiChangeListener.STATE_TIMEOUT));
                        }
                    });
        } catch (RuntimeException e) {
            Log.e(TAG, "开启眼镜 Wi-Fi 异常", e);
            handleWifiEnableFailure(CRPWifiChangeListener.STATE_TIMEOUT);
        }
    }

    /**
     * The AAR invokes Wi-Fi callbacks from BLE, ConnectivityManager and P2P
     * threads. Give every attempt its own listener/token and serialize all
     * state transitions on the main looper so stale callbacks cannot advance
     * a newer transfer.
     */
    private CRPWifiChangeListener createWifiListener(int generation, int attemptToken) {
        return new CRPWifiChangeListener() {
            @Override
            public void onWifiStateChange(CRPWifiType type, int state) {
                mainHandler.post(() -> handleWifiStateChange(
                        generation, attemptToken, type, state));
            }

            @Override
            public void onWifiConnectionStateChanged(boolean connected) {
                mainHandler.post(() -> handleWifiConnectionStateChange(
                        generation, attemptToken, connected));
            }

            @Override
            public void onLiveUrlChanged(String url) {
                mainHandler.post(() -> postLog("WiFi", "直播地址: " + url));
            }
        };
    }

    private void handleWifiStateChange(int generation, int attemptToken,
                                       CRPWifiType type, int state) {
        postLog("WiFi", "状态变化: type=" + type + ", state=" + state);
        notifyListeners(l -> l.onWifiStateChange(state));
        if (generation != mediaGeneration || attemptToken != wifiAttemptToken
                || type != CRPWifiType.FILE || mediaState != MEDIA_ENABLING_WIFI) {
            return;
        }
        if (state == CRPWifiChangeListener.STATE_SUCCESS) {
            cancelMediaTimeout();
            requestGlassesWifiJoin(generation, attemptToken);
        } else if (state == CRPWifiChangeListener.STATE_TIMEOUT) {
            // The AAR has a hard-coded 30 s timer. This firmware has been
            // observed reporting the real success 331 ms later, so keep the
            // heartbeat and our 35 s deadline alive instead of disabling Wi-Fi.
            postLog("WiFi", "SDK 30 秒超时，继续等待眼镜最终开启结果");
            notifyMediaStage("眼镜热点启动较慢，正在完成开启…");
        } else {
            cancelMediaTimeout();
            handleWifiEnableFailure(state);
        }
    }

    private void handleWifiConnectionStateChange(int generation, int attemptToken,
                                                  boolean connected) {
        postLog("WiFi", "连接状态: " + connected);
        notifyListeners(l -> l.onWifiConnectionChanged(connected));
        if (generation != mediaGeneration || attemptToken != wifiAttemptToken) return;
        if (mediaState == MEDIA_WAITING_WIFI_APPROVAL) {
            cancelMediaTimeout();
            if (connected) startSdkMediaDownload(generation, attemptToken);
            else if (wifiSystemApprovalExpected) {
                failMediaTransfer(CRPFileDownloadCallback.CODE_NOT_NETWORK,
                        "未加入眼镜 Wi-Fi，可能已取消系统确认；请点导出后在弹窗中选择连接");
            } else {
                retryWifiConnectionOrFail("眼镜 Wi-Fi 连接失败");
            }
        } else if (!connected && mediaState == MEDIA_DOWNLOADING) {
            failMediaTransfer(CRPFileDownloadCallback.CODE_NOT_NETWORK,
                    "照片下载期间 Wi-Fi 已断开");
        }
    }

    private void requestGlassesWifiJoin(int generation, int attemptToken) {
        if (generation != mediaGeneration || attemptToken != wifiAttemptToken
                || bleConnection == null) return;
        mediaState = MEDIA_WAITING_WIFI_APPROVAL;
        notifyMediaStage(wifiSystemApprovalExpected
                ? "眼镜 Wi-Fi 已开启，请在系统弹窗中确认加入…"
                : "眼镜 Wi-Fi 已开启，正在自动查找并连接热点…");
        try {
            // In AP mode this calls WifiNetworkSpecifier/requestNetwork inside
            // the SDK. Android owns the confirmation dialog; the app must not
            // attempt to silently accept it on the user's behalf.
            bleConnection.connectWifi();
            scheduleMediaTimeout(generation, WIFI_CONNECT_TIMEOUT_MS,
                    () -> {
                        if (attemptToken == wifiAttemptToken
                                && mediaState == MEDIA_WAITING_WIFI_APPROVAL) {
                            if (wifiSystemApprovalExpected) {
                                failMediaTransfer(CRPFileDownloadCallback.CODE_NOT_NETWORK,
                                        "等待加入眼镜 Wi-Fi 超时；请重新导出并在系统弹窗中选择连接");
                            } else {
                                retryWifiConnectionOrFail("眼镜 Wi-Fi 连接超时");
                            }
                        }
                    });
        } catch (RuntimeException e) {
            Log.e(TAG, "连接眼镜 Wi-Fi 异常", e);
            retryWifiConnectionOrFail("眼镜 Wi-Fi 连接异常");
        }
    }

    private void handleWifiEnableFailure(int state) {
        if (mediaState == MEDIA_IDLE) return;
        if (state == CRPWifiChangeListener.STATE_BUSY
                && wifiBusyRetryCount < MAX_WIFI_BUSY_RETRIES) {
            wifiBusyRetryCount++;
            retryWholeWifiAttempt("眼镜 Wi-Fi 忙，正在清理后重试", false);
            return;
        }
        failMediaTransfer(state, wifiStateError(state));
    }

    private void retryWifiConnectionOrFail(String reason) {
        if (mediaState == MEDIA_IDLE) return;
        if (wifiConnectionAttempt >= MAX_WIFI_CONNECTION_ATTEMPTS) {
            failMediaTransfer(CRPFileDownloadCallback.CODE_NOT_NETWORK, reason);
            return;
        }
        wifiConnectionAttempt++;
        // Vendor app uses P2P GO for attempts 1-3, then AP for attempt 4.
        boolean switchTransport = wifiConnectionAttempt == MAX_WIFI_CONNECTION_ATTEMPTS;
        retryWholeWifiAttempt(reason + "，准备第 " + wifiConnectionAttempt + " 次尝试",
                switchTransport);
    }

    private void retryWholeWifiAttempt(String reason, boolean switchTransport) {
        int generation = mediaGeneration;
        cancelMediaTimeout();
        mediaState = MEDIA_RETRY_WAIT;
        notifyMediaStage(reason);
        cleanupWifiChannel();
        mainHandler.postDelayed(() -> {
            if (generation != mediaGeneration || mediaState != MEDIA_RETRY_WAIT) return;
            if (switchTransport) switchSdkWifiTransportToFallback();
            beginWifiEnable(generation);
        }, WIFI_RETRY_DELAY_MS);
    }

    private void startSdkMediaDownload(int generation, int attemptToken) {
        CRPBleConnection current = bleConnection;
        if (generation != mediaGeneration || attemptToken != wifiAttemptToken
                || current == null) return;
        mediaState = MEDIA_DOWNLOADING;
        notifyMediaStage("Wi-Fi 已连接，正在下载照片…");
        scheduleDownloadIdleTimeout(generation);
        try {
            current.downloadMediaFile(new CRPFileDownloadCallback() {
                @Override
                public void onStart() {
                    mainHandler.post(() -> {
                        if (!isCurrentDownload(generation, attemptToken)) return;
                        postLog("下载", "开始下载");
                        scheduleDownloadIdleTimeout(generation);
                    });
                }

                @Override
                public void onProgress(int total, int done, int percent) {
                    mainHandler.post(() -> {
                        if (!isCurrentDownload(generation, attemptToken)) return;
                        postLog("下载", "进度: " + done + "/" + total
                                + " (" + percent + "%)");
                        scheduleDownloadIdleTimeout(generation);
                        MediaDownloadListener listener = mediaDownloadListener;
                        if (listener != null) listener.onProgress(total, done, percent);
                    });
                }

                @Override
                public void onDownloadSpeed(int speed) {
                    mainHandler.post(() -> {
                        if (isCurrentDownload(generation, attemptToken)) {
                            postLog("下载", "速度: " + speed + " B/s");
                        }
                    });
                }

                @Override
                public void onFileDownloaded(String path) {
                    mainHandler.post(() -> {
                        if (!isCurrentDownload(generation, attemptToken)) return;
                        postLog("下载", "文件完成: " + path);
                        MediaDownloadListener listener = mediaDownloadListener;
                        if (listener != null) listener.onFileDownloaded(path);
                    });
                }

                @Override
                public void onAllFilesDownloaded(String directory, List<String> paths) {
                    List<String> safePaths = paths == null
                            ? Collections.emptyList() : new ArrayList<>(paths);
                    mainHandler.post(() -> {
                        if (!isCurrentDownload(generation, attemptToken)) return;
                        lastDownloadDir = directory == null ? "" : directory;
                        lastDownloadedPaths.clear();
                        lastDownloadedPaths.addAll(safePaths);
                        if (!TextUtils.isEmpty(lastDownloadDir)) {
                            preferences.edit().putString(
                                    KEY_LAST_MEDIA_DIR, lastDownloadDir).apply();
                        }
                        postLog("下载", "本轮文件完成: " + lastDownloadedPaths.size()
                                + " 个，目录=" + lastDownloadDir);
                    });
                }

                @Override
                public void onSuccess() {
                    mainHandler.post(() -> {
                        if (isCurrentDownload(generation, attemptToken)) {
                            completeMediaTransfer();
                        }
                    });
                }

                @Override
                public void onFail(int code) {
                    mainHandler.post(() -> {
                        if (isCurrentDownload(generation, attemptToken)) {
                            failMediaTransfer(code, downloadError(code));
                        }
                    });
                }
            });
        } catch (RuntimeException e) {
            Log.e(TAG, "启动媒体下载异常", e);
            failMediaTransfer(CRPFileDownloadCallback.CODE_HTTP_FAIL, "无法启动照片下载");
        }
    }

    private boolean isCurrentDownload(int generation, int attemptToken) {
        return generation == mediaGeneration
                && attemptToken == wifiAttemptToken
                && mediaState == MEDIA_DOWNLOADING;
    }

    private void completeMediaTransfer() {
        cancelMediaTimeout();
        mediaState = MEDIA_IDLE;
        cleanupWifiChannel();
        String directory = getDownloadDir();
        List<String> paths = new ArrayList<>(lastDownloadedPaths);
        postLog("下载", "全部下载成功，共 " + paths.size() + " 个文件");
        MediaDownloadListener listener = mediaDownloadListener;
        if (listener != null) {
            mainHandler.post(() -> listener.onCompleted(directory, paths));
        }
    }

    private void failMediaTransfer(int code, String message) {
        if (mediaState == MEDIA_IDLE) {
            notifyMediaFailure(code, message);
            return;
        }
        ++mediaGeneration;
        cancelMediaTimeout();
        CRPBleConnection current = bleConnection;
        if (current != null) {
            try { current.cancelMediaFileDownload(); } catch (RuntimeException ignored) { }
        }
        mediaState = MEDIA_IDLE;
        cleanupWifiChannel();
        postLog("下载", "导出失败: code=" + code + ", " + message);
        notifyMediaFailure(code, message);
    }

    private void cleanupWifiChannel() {
        setSdkWifiHeartbeatAlive(false);
        CRPBleConnection current = bleConnection;
        if (current != null) {
            try {
                current.disableWifi();
            } catch (RuntimeException sdkCleanupFailure) {
                // AAR 1.0 can unregister the same Connectivity callback twice.
                // Its disableWifi() then throws before queueing the BLE
                // "disable Wi-Fi" command, leaving the glasses busy forever.
                Log.w(TAG, "SDK 清理手机 Wi-Fi 通道失败，补发眼镜关闭指令",
                        sdkCleanupFailure);
                sendDisableWifiCommandFallback(current);
            }
        }
    }

    /** Version-scoped fallback for the bundled obfuscated AAR's cleanup bug. */
    private void sendDisableWifiCommandFallback(CRPBleConnection connection) {
        try {
            Class<?> formatter = Class.forName("com.moyoung.d.e");
            Method disableCommand = formatter.getDeclaredMethod("b");
            disableCommand.setAccessible(true);
            byte[] command = (byte[]) disableCommand.invoke(null);

            Method enqueue = connection.getClass().getDeclaredMethod("a", byte[].class);
            enqueue.setAccessible(true);
            enqueue.invoke(connection, (Object) command);
            postLog("WiFi", "已补发眼镜 Wi-Fi 关闭指令");
        } catch (Exception reflectionFailure) {
            Log.e(TAG, "无法补发眼镜 Wi-Fi 关闭指令", reflectionFailure);
            postLog("WiFi", "眼镜 Wi-Fi 清理失败，请稍后重试或重启眼镜");
        }
    }

    private void scheduleMediaTimeout(int generation, long delayMs, Runnable action) {
        cancelMediaTimeout();
        mediaTimeoutRunnable = () -> {
            if (generation == mediaGeneration && mediaState != MEDIA_IDLE) action.run();
        };
        mainHandler.postDelayed(mediaTimeoutRunnable, delayMs);
    }

    private void scheduleDownloadIdleTimeout(int generation) {
        scheduleMediaTimeout(generation, DOWNLOAD_IDLE_TIMEOUT_MS,
                () -> failMediaTransfer(CRPFileDownloadCallback.CODE_NOT_NETWORK,
                        "照片下载长时间无进度，已停止本次导出"));
    }

    private void cancelMediaTimeout() {
        if (mediaTimeoutRunnable != null) {
            mainHandler.removeCallbacks(mediaTimeoutRunnable);
            mediaTimeoutRunnable = null;
        }
    }

    private void notifyMediaStage(String message) {
        postLog("下载", message);
        MediaDownloadListener listener = mediaDownloadListener;
        if (listener != null) mainHandler.post(() -> listener.onStageChanged(message));
    }

    private void notifyMediaFailure(int code, String message) {
        MediaDownloadListener listener = mediaDownloadListener;
        if (listener != null) mainHandler.post(() -> listener.onFailed(code, message));
    }

    /** Mirrors the vendor app's WifiConnectionHelper selection for this AAR. */
    private boolean configureSdkWifiTransport(boolean useAp) {
        try {
            Class<?> connectionFactory = Class.forName("com.moyoung.z.d");
            Method forceAp = connectionFactory.getDeclaredMethod("a", boolean.class);
            forceAp.setAccessible(true);
            forceAp.invoke(null, useAp);

            // With forced AP disabled, clear any process-wide fallback left by
            // an earlier transfer and make the glasses the P2P group owner.
            if (!useAp) {
                Method resetSavedTransport = connectionFactory.getDeclaredMethod("g");
                resetSavedTransport.setAccessible(true);
                resetSavedTransport.invoke(null);
            }
            Method setP2pGo = connectionFactory.getDeclaredMethod("b", boolean.class);
            setP2pGo.setAccessible(true);
            setP2pGo.invoke(null, true);

            Method isForcedAp = connectionFactory.getDeclaredMethod("c");
            isForcedAp.setAccessible(true);
            boolean enabled = Boolean.TRUE.equals(isForcedAp.invoke(null));
            if (enabled != useAp) return false;

            wifiSystemApprovalExpected = useAp;
            postLog("WiFi", useAp
                    ? "已切换 AP 热点模式，将由系统确认加入"
                    : "按官方策略使用 P2P GO，自动发现并连接眼镜热点");
            return true;
        } catch (Exception reflectionFailure) {
            Log.w(TAG, "当前 SDK 无法配置 Wi-Fi 连接方式", reflectionFailure);
            return false;
        }
    }

    private boolean shouldForceApForFirmware(@Nullable String version) {
        if (TextUtils.isEmpty(version)) return false;
        for (String forcedVersion : FORCED_AP_FIRMWARE_VERSIONS) {
            if (forcedVersion.equals(version)) return true;
        }
        return false;
    }

    /**
     * The vendor defaults this firmware to P2P GO. On the connected Huawei
     * JAD-AL00, three real-device attempts reached group negotiation but never
     * produced the file-server URL, while AP reliably opened the system-owned
     * join dialog. Keep that verified workaround scoped to the exact pair.
     */
    private boolean shouldUseApFirst(@Nullable String version) {
        if (shouldForceApForFirmware(version)) return true;
        return "HUAWEI".equalsIgnoreCase(Build.MANUFACTURER)
                && "JAD-AL00".equalsIgnoreCase(Build.MODEL)
                && "MOY-A253-0.0.6".equals(version);
    }

    /** Mirrors AllwinnerHeartRateManager.setAlive() from the vendor wrapper. */
    private boolean setSdkWifiHeartbeatAlive(boolean alive) {
        try {
            Class<?> heartbeatManager = Class.forName("com.moyoung.f.a");
            Method getInstance = heartbeatManager.getDeclaredMethod("b");
            getInstance.setAccessible(true);
            Object manager = getInstance.invoke(null);
            Method setAlive = heartbeatManager.getDeclaredMethod("a", boolean.class);
            setAlive.setAccessible(true);
            setAlive.invoke(manager, alive);
            postLog("WiFi", alive ? "Wi-Fi 心跳已启动" : "Wi-Fi 心跳已停止");
            return true;
        } catch (Exception reflectionFailure) {
            Log.w(TAG, "无法设置 SDK Wi-Fi 心跳状态: " + alive, reflectionFailure);
            return false;
        }
    }

    /** The vendor app switches to AP only after three failed P2P connections. */
    private void switchSdkWifiTransportToFallback() {
        wifiUseApMode = true;
        postLog("WiFi", "P2P 连续连接失败，下一次改用 AP 热点模式");
    }

    /** Uses the real SDK callback directory, with the SDK's internal path as first-run fallback. */
    public String getDownloadDir() {
        if (!TextUtils.isEmpty(lastDownloadDir)) return lastDownloadDir;
        return new File(appContext.getFilesDir(), "moyoung/wifi/media_res").getAbsolutePath();
    }

    public List<String> getLastDownloadedPaths() {
        return new ArrayList<>(lastDownloadedPaths);
    }

    private String mediaStateDescription() {
        switch (mediaState) {
            case MEDIA_ENABLING_WIFI: return "正在开启眼镜 Wi-Fi…";
            case MEDIA_WAITING_WIFI_APPROVAL:
                return wifiSystemApprovalExpected
                        ? "请在系统弹窗中确认加入眼镜 Wi-Fi…"
                        : "正在自动查找并连接眼镜 Wi-Fi…";
            case MEDIA_DOWNLOADING: return "正在下载照片…";
            case MEDIA_RETRY_WAIT: return "正在清理 Wi-Fi 并准备重试…";
            case MEDIA_PREPARING_WIFI: return "正在检查眼镜 Wi-Fi 状态…";
            default: return "等待导出";
        }
    }

    private String wifiStateError(int state) {
        switch (state) {
            case CRPWifiChangeListener.STATE_LOW_BATTERY: return "眼镜电量过低，无法开启 Wi-Fi";
            case CRPWifiChangeListener.STATE_TIMEOUT: return "眼镜 Wi-Fi 开启超时";
            case CRPWifiChangeListener.STATE_BUSY: return "眼镜正在执行其他任务，请稍后重试";
            case CRPWifiChangeListener.STATE_LOW_STORAGE: return "眼镜存储空间不足";
            default: return "眼镜 Wi-Fi 开启失败（" + state + "）";
        }
    }

    private String downloadError(int code) {
        switch (code) {
            case CRPFileDownloadCallback.CODE_NOT_NETWORK: return "眼镜 Wi-Fi 未连接";
            case CRPFileDownloadCallback.CODE_HTTP_FAIL: return "照片传输网络异常";
            case CRPFileDownloadCallback.CODE_RESPONSE_FAIL: return "眼镜返回下载失败";
            case CRPFileDownloadCallback.CODE_RESPONSE_DATA_FAIL: return "照片数据解析失败";
            case CRPFileDownloadCallback.CODE_URL_NULL: return "眼镜未返回文件地址";
            case CRPFileDownloadCallback.CODE_STORAGE_TOO_LOW: return "手机存储空间不足";
            default: return "照片下载失败（" + code + "）";
        }
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

    private void setupConnectionStateListener(CRPBleConnection connection, int generation) {
        connection.setConnectionStateListener(newState -> mainHandler.post(() -> {
            if (generation != connectionGeneration || connection != bleConnection) return;
            postLog("状态", "连接状态变化: " + stateToString(newState));
            connectionState = newState;

            switch (newState) {
                case CRPBleConnectionStateListener.STATE_CONNECTED:
                    onConnected();
                    break;
                case CRPBleConnectionStateListener.STATE_DISCONNECTED:
                    onDisconnected();
                    break;
                default:
                    break;
            }

            notifyListeners(l -> l.onConnectionStateChanged(newState));
        }));

        // The SDK stores this listener globally, not per connection.
        connection.setWifiListener(createWifiListener(mediaGeneration, wifiAttemptToken));
        connection.setMediaFileChangeListener(info -> {
            if (generation != connectionGeneration || connection != bleConnection) return;
            postLog("媒体", "新文件: 照片=" + info.getPhotoCount()
                    + ", 视频=" + info.getVideoCount() + ", 音频=" + info.getAudioCount());
            notifyListeners(l -> l.onMediaFileChanged(
                    info.getPhotoCount(), info.getVideoCount(), info.getAudioCount()));
        });
    }

    private void setupBatteryListener(CRPBleConnection connection, int generation) {
        connection.setBatteryListener(info -> {
            if (generation != connectionGeneration || connection != bleConnection) return;
            batteryLevel = info.getLvl();
            postLog("电量", info.getLvl() + "%, 充电=" + info.getCharging());
            notifyListeners(l -> l.onBatteryUpdate(info.getLvl(), info.getCharging()));
        });
    }

    private void onConnected() {
        mainHandler.removeCallbacks(connectTimeoutTask);
        retryCount = 0;
        isReconnecting = false;
        if (returnTransactionInProgress) {
            postLog("归还", "归还事务进行中，不再初始化新到达的 BLE 连接");
            return;
        }
        userInitiatedDisconnect = false;
        if (!TextUtils.isEmpty(connectedAddress)) {
            preferences.edit().putString(KEY_LAST_ADDRESS, connectedAddress).apply();
        }
        postLog("连接", "设备已连接成功");
        if (bleConnection != null) {
            // Restore the SDK's original post-connect initialization first. The
            // glasses need a short quiet window before accepting the BT-enable
            // command; no further BLE commands are queued after audio pairing starts.
            bleConnection.syncTime();
            postLog("连接", "时间同步已发送");
            queryBattery();
            queryDeviceVersion();

            final int generation = connectionGeneration;
            final CRPBleConnection connectedConnection = bleConnection;
            final CRPBleDevice connectedDevice = bleDevice;
            cancelPendingAudioConnection();
            setAudioConnectionState(AUDIO_STATE_CONNECTING);
            audioConnectionRunnable = () -> {
                audioConnectionRunnable = null;
                if (generation != connectionGeneration
                        || connectedConnection != bleConnection
                        || connectedDevice != bleDevice
                        || connectionState != CRPBleConnectionStateListener.STATE_CONNECTED) {
                    return;
                }
                postLog("音频", "BLE 初始化完成，开始建立蓝牙音频连接");
                bluetoothAudioCoordinator.ensureAudioConnection(
                        connectedConnection, connectedDevice);
            };
            mainHandler.postDelayed(audioConnectionRunnable, AUDIO_CONNECTION_DELAY_MS);
        }
    }

    private void onDisconnected() {
        postLog("连接", "设备已断开");
        mainHandler.removeCallbacks(connectTimeoutTask);
        cancelPendingAudioConnection();
        bluetoothAudioCoordinator.onBleDisconnected();
        if (mediaState != MEDIA_IDLE) {
            failMediaTransfer(CRPFileDownloadCallback.CODE_NOT_NETWORK,
                    "眼镜 BLE 已断开，照片导出已停止");
        }
        if (userInitiatedDisconnect || TextUtils.isEmpty(connectedAddress)) {
            cleanupConnection();
            return;
        }
        if (bluetoothAudioCoordinator.isPairingActive()) {
            postLog("重连", "配对期间 BLE 暂时断开，等待系统配对结果");
            cleanupConnection();
            return;
        }
        scheduleReconnect("BLE 连接断开");
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

    private void setAudioConnectionState(int state) {
        if (state < AUDIO_STATE_DISCONNECTED || state > AUDIO_STATE_CONNECTED
                || audioConnectionState == state) return;
        audioConnectionState = state;
        notifyListeners(listener -> listener.onAudioConnectionStateChanged(state));
    }

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

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }
}
