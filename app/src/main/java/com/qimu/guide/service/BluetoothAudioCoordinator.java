package com.qimu.guide.service;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;

import androidx.core.content.ContextCompat;

import com.moyoung.glasses.conn.CRPBleConnection;
import com.moyoung.glasses.conn.CRPBleDevice;
import com.moyoung.glasses.conn.callback.CRPCommandCallback;
import com.moyoung.glasses.conn.protos.NewBtState;

import java.lang.reflect.Method;

/**
 * BLE 就绪后建立眼镜的经典蓝牙音频连接。
 *
 * <p>BLE 连接就绪后，通过 {@code enableDeviceBT} 打开眼镜经典蓝牙，并立即调用
 * 远程仓库已验证可用的 {@code createBond(1)} 触发系统配对弹窗。经典扫描只在直接
 * 配对请求无法启动时作为兜底；已配对设备则恢复 A2DP/HFP。</p>
 */
final class BluetoothAudioCoordinator {

    interface Callback {
        void onPairingStateChanged(boolean active);
        void onAudioConnectionStateChanged(int state);
        void onPairingFinished(boolean success);
        void onBluetoothAdapterEnabled();
        void onLog(String message);
        void onError(String message);
    }

    private static final long CLASSIC_DISCOVERY_TIMEOUT_MS = 16000L;
    private static final long CLASSIC_DISCOVERY_RETRY_DELAY_MS = 800L;
    private static final long BOND_TIMEOUT_MS = 35000L;
    private static final long PROFILE_CONNECT_TIMEOUT_MS = 30000L;
    private static final long PROFILE_RETRY_DELAY_MS = 2500L;
    private static final long PROFILE_FINAL_SETTLE_MS = 4000L;
    private static final int MAX_PROFILE_CONNECT_ATTEMPTS = 3;
    private static final int MAX_CLASSIC_DISCOVERY_ATTEMPTS = 2;
    private static final int TRANSPORT_BREDR = 1;

    private static final int PHASE_DONE = 0;
    private static final int PHASE_ENABLE = 1;
    private static final int PHASE_DISCOVERY = 2;
    private static final int PHASE_BOND = 3;
    private static final int PHASE_PROFILE = 4;

    private final Context context;
    private final Handler handler;
    private final Callback callback;

    private CRPBleConnection connection;
    private BluetoothDevice bluetoothDevice;
    private String targetAddress;
    private int generation;
    private boolean pairingActive;
    private boolean bondStarted;
    private boolean classicDiscoveryActive;
    private boolean classicDiscoveryOwned;
    private boolean discoveryTransitioning;
    private boolean bondRecoveryUsed;
    private int classicDiscoveryAttempt;
    private int phase = PHASE_DONE;
    private Runnable stageTimeout;
    private Runnable profileRetryRunnable;

    private BluetoothA2dp a2dpProxy;
    private BluetoothHeadset headsetProxy;
    private int lastA2dpState = BluetoothProfile.STATE_DISCONNECTED;
    private int lastHeadsetState = BluetoothProfile.STATE_DISCONNECTED;
    private boolean a2dpProxyRequested;
    private boolean headsetProxyRequested;
    private int profileConnectAttempt;

    BluetoothAudioCoordinator(Context context, Handler handler, Callback callback) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.callback = callback;
        registerBluetoothReceiver();
    }

    void ensureAudioConnection(CRPBleConnection connection, CRPBleDevice bleDevice) {
        if (connection == null || bleDevice == null) return;
        if (!hasConnectPermission()) {
            callback.onAudioConnectionStateChanged(BleService.AUDIO_STATE_DISCONNECTED);
            callback.onError("缺少蓝牙连接权限，无法建立眼镜音频连接");
            return;
        }

        BluetoothDevice device;
        try {
            device = bleDevice.getBluetoothDevice();
        } catch (SecurityException e) {
            callback.onAudioConnectionStateChanged(BleService.AUDIO_STATE_DISCONNECTED);
            callback.onError("无法读取眼镜蓝牙设备信息");
            return;
        }
        if (device == null) {
            callback.onAudioConnectionStateChanged(BleService.AUDIO_STATE_DISCONNECTED);
            callback.onError("无法获取眼镜经典蓝牙设备");
            return;
        }

        int requestGeneration = ++generation;
        cancelStageTimeout();
        cancelProfileRetry();
        stopClassicDiscovery();
        closeProfileProxies();
        this.connection = connection;
        this.bluetoothDevice = device;
        try {
            this.targetAddress = device.getAddress();
        } catch (SecurityException e) {
            callback.onError("无法读取眼镜蓝牙地址");
            finishAudioFlow(false, "无法识别眼镜蓝牙设备");
            return;
        }
        this.bondStarted = false;
        this.classicDiscoveryAttempt = 0;
        this.discoveryTransitioning = false;
        this.bondRecoveryUsed = false;
        this.phase = PHASE_ENABLE;
        this.profileConnectAttempt = 0;
        this.lastA2dpState = BluetoothProfile.STATE_DISCONNECTED;
        this.lastHeadsetState = BluetoothProfile.STATE_DISCONNECTED;
        callback.onAudioConnectionStateChanged(BleService.AUDIO_STATE_CONNECTING);

        // pairingActive 仅表示系统正在执行真实 bond；开启 BT 和已配对设备的
        // Profile 恢复不应阻塞 BLE 自动重连。
        if (getBondStateSafely(device) != BluetoothDevice.BOND_BONDING) {
            setPairingActive(false);
        }
        // 保持远程仓库已在真机验证可用的时序：BLE 就绪后立即 open BT，
        // 紧接着 createBond(1)，两步之间不等待 SDK 命令回调。
        enableDeviceBt(requestGeneration);
    }

    void onBleDisconnected() {
        // 真正配对时 BLE 可能暂时断开。保留 BluetoothDevice 和 Profile 状态，
        // 等 bond/profile 广播结束后再由 BleService 恢复 BLE。
        connection = null;
    }

    void cancel() {
        generation++;
        cancelStageTimeout();
        cancelProfileRetry();
        stopClassicDiscovery();
        closeProfileProxies();
        connection = null;
        bluetoothDevice = null;
        targetAddress = null;
        bondStarted = false;
        classicDiscoveryAttempt = 0;
        discoveryTransitioning = false;
        bondRecoveryUsed = false;
        phase = PHASE_DONE;
        setPairingActive(false);
        lastA2dpState = BluetoothProfile.STATE_DISCONNECTED;
        lastHeadsetState = BluetoothProfile.STATE_DISCONNECTED;
        callback.onAudioConnectionStateChanged(BleService.AUDIO_STATE_DISCONNECTED);
    }

    boolean isPairingActive() {
        return pairingActive;
    }

    /** 打开眼镜 BT，并按原版可用流程在调用返回后立即发起 BR/EDR 配对。 */
    private void enableDeviceBt(int requestGeneration) {
        CRPBleConnection current = connection;
        if (current == null || requestGeneration != generation) {
            finishAudioFlow(false, "BLE 已断开，无法开启眼镜 BT");
            return;
        }

        phase = PHASE_ENABLE;
        callback.onLog("正在开启眼镜 BT，并立即发起系统音频配对…");
        try {
            current.enableDeviceBT(new CRPCommandCallback() {
                @Override
                public void onSuccess() {
                    handler.post(() -> {
                        if (requestGeneration != generation) return;
                        callback.onLog("眼镜 BT 开启指令执行成功");
                    });
                }

                @Override
                public void onFailure(int code) {
                    handler.post(() -> {
                        if (requestGeneration != generation) return;
                        // 原版可用实现不会等待此回调；系统 bond 广播才是最终依据，
                        // 不能因失败回调取消已经发出的 createBond(1)。
                        callback.onLog("开启眼镜 BT 返回失败码 " + code
                                + "，系统配对继续进行");
                    });
                }
            });
        } catch (RuntimeException e) {
            callback.onLog("开启眼镜 BT 指令异常，仍按原版流程尝试系统配对");
        }

        // 远程仓库在这台真机上成功弹窗的顺序是：
        // enableDeviceBT(...); createBond(1); 两步之间不等待任何回调。
        beginDirectBond(requestGeneration);
    }

    /** 优先复用远程仓库已验证的 createBond(1)；扫描仅作同步失败兜底。 */
    private void beginDirectBond(int requestGeneration) {
        if (requestGeneration != generation) return;
        cancelStageTimeout();
        BluetoothDevice device = bluetoothDevice;
        if (device == null || !hasConnectPermission()) {
            finishAudioFlow(false, "无法访问眼镜蓝牙设备");
            return;
        }

        int bondState = getBondStateSafely(device);
        if (bondState == BluetoothDevice.BOND_BONDED) {
            phase = PHASE_BOND;
            bondStarted = false;
            setPairingActive(false);
            callback.onLog("眼镜已配对，开始恢复 A2DP/HFP 音频 Profile");
            startProfileRecovery(requestGeneration);
            return;
        }
        if (bondState == BluetoothDevice.BOND_BONDING) {
            phase = PHASE_BOND;
            bondStarted = true;
            setPairingActive(true);
            callback.onLog("系统正在配对眼镜，请在手机弹窗中确认");
            scheduleBondTimeout(requestGeneration);
            return;
        }

        phase = PHASE_BOND;
        callback.onLog("已请求开启眼镜 BT，立即调用 createBond(1) 触发系统配对弹窗");
        boolean started = createClassicBond(device);
        int stateAfterRequest = getBondStateSafely(device);
        bondStarted = started || stateAfterRequest == BluetoothDevice.BOND_BONDING;
        if (bondStarted) {
            setPairingActive(true);
            callback.onLog("系统配对请求已发出，请在手机弹窗中确认");
            scheduleBondTimeout(requestGeneration);
        } else if (stateAfterRequest == BluetoothDevice.BOND_BONDED) {
            setPairingActive(false);
            startProfileRecovery(requestGeneration);
        } else {
            callback.onLog("createBond(1) 未能启动，改用经典蓝牙扫描兜底");
            startClassicDiscovery(requestGeneration);
        }
    }

    private void startClassicDiscovery(int requestGeneration) {
        if (requestGeneration != generation) return;
        if (!hasScanPermission()) {
            finishAudioFlow(false, "缺少附近设备扫描权限，无法发现眼镜 BT");
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            finishAudioFlow(false, "手机蓝牙未开启，无法扫描眼镜 BT");
            return;
        }

        phase = PHASE_DISCOVERY;
        discoveryTransitioning = false;
        boolean alreadyDiscovering;
        try {
            alreadyDiscovering = adapter.isDiscovering();
        } catch (RuntimeException e) {
            alreadyDiscovering = false;
        }
        if (alreadyDiscovering) {
            classicDiscoveryActive = true;
            classicDiscoveryOwned = false;
            callback.onLog("系统已有经典蓝牙扫描，先监听 ACTION_FOUND 同 MAC 眼镜；"
                    + "扫描结束后再启动本应用扫描");
            scheduleStageTimeout(requestGeneration, CLASSIC_DISCOVERY_TIMEOUT_MS,
                    () -> handleClassicDiscoveryMiss(requestGeneration,
                            "系统已有经典蓝牙扫描未发现眼镜"));
            return;
        }

        int attempt = ++classicDiscoveryAttempt;
        boolean started;
        try {
            started = adapter.startDiscovery();
        } catch (RuntimeException e) {
            started = false;
        }
        classicDiscoveryActive = started;
        classicDiscoveryOwned = started;
        if (!started) {
            handleClassicDiscoveryMiss(requestGeneration,
                    "系统未能启动经典蓝牙扫描");
            return;
        }

        callback.onLog("正在扫描眼镜 BT（第 " + attempt + "/"
                + MAX_CLASSIC_DISCOVERY_ATTEMPTS + " 次），等待发现同 MAC 设备");
        scheduleStageTimeout(requestGeneration, CLASSIC_DISCOVERY_TIMEOUT_MS,
                () -> handleClassicDiscoveryMiss(requestGeneration,
                        "经典蓝牙扫描未发现眼镜"));
    }

    private void handleClassicDiscoveryMiss(int requestGeneration, String reason) {
        if (requestGeneration != generation || phase != PHASE_DISCOVERY
                || discoveryTransitioning) return;
        discoveryTransitioning = true;
        cancelStageTimeout();
        stopClassicDiscovery();
        if (classicDiscoveryAttempt < MAX_CLASSIC_DISCOVERY_ATTEMPTS) {
            callback.onLog(reason + "，准备重试");
            scheduleStageTimeout(requestGeneration, CLASSIC_DISCOVERY_RETRY_DELAY_MS, () -> {
                if (requestGeneration != generation || phase != PHASE_DISCOVERY) return;
                discoveryTransitioning = false;
                startClassicDiscovery(requestGeneration);
            });
        } else {
            finishAudioFlow(false, reason + "，请保持眼镜靠近并确认眼镜 BT 已开启");
        }
    }

    private void beginBondAfterDiscovery(int requestGeneration, BluetoothDevice device) {
        if (requestGeneration != generation || device == null) return;
        phase = PHASE_BOND;
        discoveryTransitioning = false;
        cancelStageTimeout();
        // Android recommends stopping discovery before bonding. Once the exact
        // target MAC is found, this is safe even if discovery began elsewhere.
        stopClassicDiscovery(true);
        bluetoothDevice = device;

        int bondState = getBondStateSafely(device);
        if (bondState == BluetoothDevice.BOND_BONDED) {
            bondStarted = false;
            setPairingActive(false);
            callback.onLog("扫描到已配对眼镜，开始恢复 A2DP/HFP 音频 Profile");
            startProfileRecovery(requestGeneration);
            return;
        }
        if (bondState == BluetoothDevice.BOND_BONDING) {
            bondStarted = true;
            setPairingActive(true);
            callback.onLog("系统正在配对眼镜，请在手机弹窗中确认");
            scheduleBondTimeout(requestGeneration);
            return;
        }

        callback.onLog("已发现眼镜 BT，正在发起配对，请在手机弹窗中确认");
        boolean started = createClassicBond(device);
        int stateAfterRequest = getBondStateSafely(device);
        bondStarted = started || stateAfterRequest == BluetoothDevice.BOND_BONDING;
        if (bondStarted) {
            setPairingActive(true);
            scheduleBondTimeout(requestGeneration);
        } else if (stateAfterRequest == BluetoothDevice.BOND_BONDED) {
            setPairingActive(false);
            startProfileRecovery(requestGeneration);
        } else {
            handleBondFailure(requestGeneration, "未能发起眼镜蓝牙配对");
        }
    }

    /** 使用与远程仓库相同的 BR/EDR 定向 createBond(1)。 */
    private boolean createClassicBond(BluetoothDevice device) {
        try {
            Method method = BluetoothDevice.class.getMethod("createBond", int.class);
            method.setAccessible(true);
            Object result = method.invoke(device, TRANSPORT_BREDR);
            boolean started = result instanceof Boolean && (Boolean) result;
            callback.onLog("BR/EDR 配对请求返回: " + started);
            return started;
        } catch (Exception reflectionFailure) {
            callback.onLog("BR/EDR createBond(1) 接口调用失败");
            return false;
        }
    }

    private void scheduleBondTimeout(int requestGeneration) {
        scheduleStageTimeout(requestGeneration, BOND_TIMEOUT_MS, () -> {
            int state = getBondStateSafely(bluetoothDevice);
            if (state == BluetoothDevice.BOND_BONDED) {
                bondStarted = false;
                setPairingActive(false);
                callback.onLog("眼镜蓝牙配对完成，开始连接音频 Profile");
                startProfileRecovery(requestGeneration);
            } else {
                handleBondFailure(requestGeneration, "眼镜蓝牙配对超时");
            }
        });
    }

    /** 配对失败只允许重新 openBT + createBond(1) 一次，避免固件异常时无限循环。 */
    private void handleBondFailure(int requestGeneration, String reason) {
        if (requestGeneration != generation || phase != PHASE_BOND) return;
        cancelStageTimeout();
        bondStarted = false;
        setPairingActive(false);

        if (!bondRecoveryUsed && connection != null) {
            bondRecoveryUsed = true;
            classicDiscoveryAttempt = 0;
            discoveryTransitioning = false;
            stopClassicDiscovery();
            callback.onLog(reason + "，仅重试一次：重新开启眼镜 BT 并立即发起系统配对");
            enableDeviceBt(requestGeneration);
            return;
        }

        String suffix = connection == null
                ? "；BLE 已断开，无法再次开启眼镜 BT"
                : "；已用完一次自动恢复机会";
        finishAudioFlow(false, reason + suffix);
    }

    /** 获取 A2DP/HFP 代理并开始有限次数的 Profile 连接。 */
    private void startProfileRecovery(int requestGeneration) {
        if (requestGeneration != generation || !hasConnectPermission()) {
            finishAudioFlow(false, "缺少蓝牙连接权限，无法恢复音频 Profile");
            return;
        }

        cancelStageTimeout();
        cancelProfileRetry();
        stopClassicDiscovery();
        closeProfileProxies();
        phase = PHASE_PROFILE;
        bondStarted = false;
        bondRecoveryUsed = false;
        setPairingActive(false);
        profileConnectAttempt = 0;
        callback.onAudioConnectionStateChanged(BleService.AUDIO_STATE_CONNECTING);
        callback.onLog("正在获取 A2DP/HFP 音频 Profile…");

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            finishAudioFlow(false, "手机蓝牙未开启，无法连接音频 Profile");
            return;
        }

        BluetoothProfile.ServiceListener a2dpListener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                handler.post(() -> {
                    if (requestGeneration != generation || phase != PHASE_PROFILE) {
                        closeUnexpectedProxy(profile, proxy);
                        return;
                    }
                    if (profile == BluetoothProfile.A2DP && proxy instanceof BluetoothA2dp) {
                        a2dpProxy = (BluetoothA2dp) proxy;
                        lastA2dpState = getA2dpState();
                        publishAggregatedAudioState();
                        callback.onLog("A2DP Profile 代理已就绪");
                        maybeStartProfileConnect(requestGeneration);
                    }
                });
            }

            @Override
            public void onServiceDisconnected(int profile) {
                handler.post(() -> {
                    if (requestGeneration != generation) return;
                    a2dpProxy = null;
                    callback.onLog("A2DP Profile 服务已断开");
                });
            }
        };
        BluetoothProfile.ServiceListener headsetListener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                handler.post(() -> {
                    if (requestGeneration != generation || phase != PHASE_PROFILE) {
                        closeUnexpectedProxy(profile, proxy);
                        return;
                    }
                    if (profile == BluetoothProfile.HEADSET && proxy instanceof BluetoothHeadset) {
                        headsetProxy = (BluetoothHeadset) proxy;
                        lastHeadsetState = getHeadsetState();
                        publishAggregatedAudioState();
                        callback.onLog("HFP Profile 代理已就绪");
                        maybeStartProfileConnect(requestGeneration);
                    }
                });
            }

            @Override
            public void onServiceDisconnected(int profile) {
                handler.post(() -> {
                    if (requestGeneration != generation) return;
                    headsetProxy = null;
                    callback.onLog("HFP Profile 服务已断开");
                });
            }
        };

        try {
            a2dpProxyRequested = adapter.getProfileProxy(context, a2dpListener,
                    BluetoothProfile.A2DP);
            headsetProxyRequested = adapter.getProfileProxy(context, headsetListener,
                    BluetoothProfile.HEADSET);
            callback.onLog("请求 Profile 代理: A2DP=" + a2dpProxyRequested
                    + ", HFP=" + headsetProxyRequested);
        } catch (SecurityException e) {
            finishAudioFlow(false, "缺少蓝牙权限，无法获取音频 Profile");
            return;
        } catch (RuntimeException e) {
            finishAudioFlow(false, "手机无法提供 A2DP/HFP Profile");
            return;
        }

        scheduleStageTimeout(requestGeneration, PROFILE_CONNECT_TIMEOUT_MS,
                () -> finishAudioFlow(false, profileFailureMessage()));
        maybeStartProfileConnect(requestGeneration);
    }

    private void maybeStartProfileConnect(int requestGeneration) {
        if (requestGeneration != generation || phase != PHASE_PROFILE) return;
        if (areAudioProfilesConnected()) {
            finishAudioFlow(true, "A2DP/HFP 音频均已连接");
            return;
        }
        if (a2dpProxy == null || headsetProxy == null || profileRetryRunnable != null) return;
        attemptProfileConnections(requestGeneration);
    }

    private void attemptProfileConnections(int requestGeneration) {
        if (requestGeneration != generation || phase != PHASE_PROFILE) return;
        cancelProfileRetry();

        int a2dpState = getA2dpState();
        int hfpState = getHeadsetState();
        callback.onLog("音频 Profile 当前状态: A2DP=" + profileStateName(a2dpState)
                + ", HFP=" + profileStateName(hfpState));
        if (a2dpState == BluetoothProfile.STATE_CONNECTED
                && hfpState == BluetoothProfile.STATE_CONNECTED) {
            finishAudioFlow(true, "A2DP/HFP 音频均已连接");
            return;
        }

        profileConnectAttempt++;
        callback.onLog("第 " + profileConnectAttempt + "/"
                + MAX_PROFILE_CONNECT_ATTEMPTS + " 次恢复音频 Profile");
        BluetoothDevice device = bluetoothDevice;
        if (device == null) {
            finishAudioFlow(false, "眼镜蓝牙设备已丢失");
            return;
        }

        if (a2dpState == BluetoothProfile.STATE_DISCONNECTED) {
            invokeProfileConnect(a2dpProxy, device, "A2DP");
        } else if (a2dpState == BluetoothProfile.STATE_CONNECTING) {
            callback.onLog("A2DP 正在由系统连接，继续等待");
        }
        if (hfpState == BluetoothProfile.STATE_DISCONNECTED) {
            invokeProfileConnect(headsetProxy, device, "HFP");
        } else if (hfpState == BluetoothProfile.STATE_CONNECTING) {
            callback.onLog("HFP 正在由系统连接，继续等待");
        }

        long delay = profileConnectAttempt < MAX_PROFILE_CONNECT_ATTEMPTS
                ? PROFILE_RETRY_DELAY_MS : PROFILE_FINAL_SETTLE_MS;
        profileRetryRunnable = () -> {
            profileRetryRunnable = null;
            if (requestGeneration != generation || phase != PHASE_PROFILE) return;
            if (areAudioProfilesConnected()) {
                finishAudioFlow(true, "A2DP/HFP 音频均已连接");
            } else if (profileConnectAttempt < MAX_PROFILE_CONNECT_ATTEMPTS) {
                attemptProfileConnections(requestGeneration);
            } else {
                finishAudioFlow(false, profileFailureMessage());
            }
        };
        handler.postDelayed(profileRetryRunnable, delay);
    }

    /** Android 没有公开 Profile.connect；反射失败时仍等待系统自动连接广播。 */
    private void invokeProfileConnect(BluetoothProfile proxy,
                                      BluetoothDevice device,
                                      String profileName) {
        if (proxy == null || device == null || !hasConnectPermission()) return;
        try {
            Method method = findConnectMethod(proxy.getClass());
            if (method == null) {
                callback.onLog(profileName + " connect 接口不可用，等待系统自动连接");
                return;
            }
            method.setAccessible(true);
            Object result = method.invoke(proxy, device);
            callback.onLog(profileName + " connect 请求返回: " + result);
        } catch (Exception e) {
            callback.onLog(profileName + " connect 受系统限制，等待系统自动连接");
        }
    }

    private Method findConnectMethod(Class<?> type) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod("connect", BluetoothDevice.class);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private boolean areAudioProfilesConnected() {
        return getA2dpState() == BluetoothProfile.STATE_CONNECTED
                && getHeadsetState() == BluetoothProfile.STATE_CONNECTED;
    }

    private int getA2dpState() {
        BluetoothA2dp proxy = a2dpProxy;
        BluetoothDevice device = bluetoothDevice;
        if (proxy == null || device == null || !hasConnectPermission()) {
            return lastA2dpState;
        }
        try {
            lastA2dpState = proxy.getConnectionState(device);
            return lastA2dpState;
        } catch (RuntimeException e) {
            return lastA2dpState;
        }
    }

    private int getHeadsetState() {
        BluetoothHeadset proxy = headsetProxy;
        BluetoothDevice device = bluetoothDevice;
        if (proxy == null || device == null || !hasConnectPermission()) {
            return lastHeadsetState;
        }
        try {
            lastHeadsetState = proxy.getConnectionState(device);
            return lastHeadsetState;
        } catch (RuntimeException e) {
            return lastHeadsetState;
        }
    }

    private void publishAggregatedAudioState() {
        final int state;
        if (lastA2dpState == BluetoothProfile.STATE_CONNECTED
                && lastHeadsetState == BluetoothProfile.STATE_CONNECTED) {
            state = BleService.AUDIO_STATE_CONNECTED;
        } else if (pairingActive || phase != PHASE_DONE
                || lastA2dpState == BluetoothProfile.STATE_CONNECTING
                || lastHeadsetState == BluetoothProfile.STATE_CONNECTING) {
            state = BleService.AUDIO_STATE_CONNECTING;
        } else {
            state = BleService.AUDIO_STATE_DISCONNECTED;
        }
        callback.onAudioConnectionStateChanged(state);
    }

    private String profileFailureMessage() {
        return "眼镜音频连接未完成（A2DP=" + profileStateName(getA2dpState())
                + "，HFP=" + profileStateName(getHeadsetState()) + "）";
    }

    private void finishAudioFlow(boolean success, String message) {
        if (phase == PHASE_DONE && !pairingActive) return;
        cancelStageTimeout();
        cancelProfileRetry();
        stopClassicDiscovery();
        bondStarted = false;
        discoveryTransitioning = false;
        phase = PHASE_DONE;
        callback.onLog(message);
        setPairingActive(false);
        if (success) {
            lastA2dpState = BluetoothProfile.STATE_CONNECTED;
            lastHeadsetState = BluetoothProfile.STATE_CONNECTED;
            callback.onAudioConnectionStateChanged(BleService.AUDIO_STATE_CONNECTED);
            querySdkAudioState();
        } else {
            lastA2dpState = BluetoothProfile.STATE_DISCONNECTED;
            lastHeadsetState = BluetoothProfile.STATE_DISCONNECTED;
            callback.onAudioConnectionStateChanged(BleService.AUDIO_STATE_DISCONNECTED);
        }
        closeProfileProxies();
        callback.onPairingFinished(success);
    }

    private void querySdkAudioState() {
        CRPBleConnection current = connection;
        if (current == null) return;
        try {
            current.queryBTConnectionState(state -> handler.post(() -> logAudioState(state)));
        } catch (RuntimeException e) {
            callback.onLog("暂时无法查询眼镜音频 Profile 状态");
        }
    }

    private void logAudioState(NewBtState state) {
        if (state == null) return;
        callback.onLog("眼镜侧 BT=" + state.getBtState()
                + ", A2DP=" + state.getA2DpState()
                + ", HFP=" + state.getHfpState()
                + (state.hasEdrState() ? ", EDR=" + state.getEdrState() : ""));
    }

    private void registerBluetoothReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(bluetoothReceiver, filter);
        }
    }

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int adapterState = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (adapterState == BluetoothAdapter.STATE_ON) {
                    callback.onBluetoothAdapterEnabled();
                } else if (adapterState == BluetoothAdapter.STATE_OFF
                        || adapterState == BluetoothAdapter.STATE_TURNING_OFF) {
                    lastA2dpState = BluetoothProfile.STATE_DISCONNECTED;
                    lastHeadsetState = BluetoothProfile.STATE_DISCONNECTED;
                    callback.onAudioConnectionStateChanged(
                            BleService.AUDIO_STATE_DISCONNECTED);
                }
                return;
            }

            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                if (phase == PHASE_DISCOVERY && classicDiscoveryActive) {
                    classicDiscoveryActive = false;
                    classicDiscoveryOwned = false;
                    handleClassicDiscoveryMiss(generation, "本轮扫描未发现眼镜 BT");
                }
                return;
            }

            BluetoothDevice eventDevice;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                eventDevice = intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            } else {
                //noinspection deprecation
                eventDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            }
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                if (phase == PHASE_DISCOVERY && isTargetDevice(eventDevice)) {
                    callback.onLog("经典蓝牙扫描已命中 BLE 同 MAC 眼镜: "
                            + targetAddress);
                    beginBondAfterDiscovery(generation, eventDevice);
                }
                return;
            }
            if (!isTargetDevice(eventDevice)) return;

            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                int previous = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
                callback.onLog("系统配对状态: " + bondStateName(previous)
                        + " → " + bondStateName(state));
                if (state == BluetoothDevice.BOND_BONDING) {
                    bondStarted = true;
                    setPairingActive(true);
                } else if (state == BluetoothDevice.BOND_BONDED) {
                    bondStarted = false;
                    setPairingActive(false);
                    callback.onLog("眼镜蓝牙配对完成，开始连接 A2DP/HFP");
                    startProfileRecovery(generation);
                } else if (state == BluetoothDevice.BOND_NONE
                        && (previous == BluetoothDevice.BOND_BONDING || bondStarted)) {
                    handleBondFailure(generation, "眼镜蓝牙配对未完成");
                }
                return;
            }

            int profileState = intent.getIntExtra(
                    BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
            boolean a2dpEvent = BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action);
            String profile = a2dpEvent ? "A2DP" : "HFP";
            if (a2dpEvent) lastA2dpState = profileState;
            else lastHeadsetState = profileState;
            publishAggregatedAudioState();
            callback.onLog(profile + " 音频状态: " + profileStateName(profileState));
            if (phase == PHASE_PROFILE) {
                int requestGeneration = generation;
                handler.post(() -> {
                    if (requestGeneration != generation || phase != PHASE_PROFILE) return;
                    if (areAudioProfilesConnected()) {
                        finishAudioFlow(true, "A2DP/HFP 音频均已连接");
                    } else if (profileState == BluetoothProfile.STATE_DISCONNECTED
                            && profileRetryRunnable == null
                            && a2dpProxy != null && headsetProxy != null) {
                        maybeStartProfileConnect(requestGeneration);
                    }
                });
            }
        }
    };

    private void scheduleStageTimeout(int requestGeneration, long delayMs, Runnable action) {
        cancelStageTimeout();
        stageTimeout = () -> {
            stageTimeout = null;
            if (requestGeneration == generation) action.run();
        };
        handler.postDelayed(stageTimeout, delayMs);
    }

    private void cancelStageTimeout() {
        if (stageTimeout != null) {
            handler.removeCallbacks(stageTimeout);
            stageTimeout = null;
        }
    }

    private void cancelProfileRetry() {
        if (profileRetryRunnable != null) {
            handler.removeCallbacks(profileRetryRunnable);
            profileRetryRunnable = null;
        }
    }

    private void stopClassicDiscovery() {
        stopClassicDiscovery(false);
    }

    private void stopClassicDiscovery(boolean cancelEvenIfNotOwned) {
        if (!classicDiscoveryActive) return;
        boolean shouldCancel = classicDiscoveryOwned || cancelEvenIfNotOwned;
        classicDiscoveryActive = false;
        classicDiscoveryOwned = false;
        if (!shouldCancel) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !hasScanPermission()) return;
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (RuntimeException ignored) { }
    }

    private void closeProfileProxies() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            if (a2dpProxy != null) {
                try {
                    adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dpProxy);
                } catch (RuntimeException ignored) { }
            }
            if (headsetProxy != null) {
                try {
                    adapter.closeProfileProxy(BluetoothProfile.HEADSET, headsetProxy);
                } catch (RuntimeException ignored) { }
            }
        }
        a2dpProxy = null;
        headsetProxy = null;
        a2dpProxyRequested = false;
        headsetProxyRequested = false;
    }

    private void closeUnexpectedProxy(int profile, BluetoothProfile proxy) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || proxy == null) return;
        try {
            adapter.closeProfileProxy(profile, proxy);
        } catch (RuntimeException ignored) { }
    }

    private void setPairingActive(boolean active) {
        if (pairingActive == active) return;
        pairingActive = active;
        callback.onPairingStateChanged(active);
        if (active) {
            callback.onAudioConnectionStateChanged(BleService.AUDIO_STATE_CONNECTING);
        }
    }

    private int getBondStateSafely(BluetoothDevice device) {
        if (device == null || !hasConnectPermission()) return BluetoothDevice.ERROR;
        try {
            return device.getBondState();
        } catch (SecurityException e) {
            return BluetoothDevice.ERROR;
        }
    }

    private boolean isTargetDevice(BluetoothDevice device) {
        if (device == null || targetAddress == null) return false;
        try {
            return targetAddress.equalsIgnoreCase(device.getAddress());
        } catch (SecurityException e) {
            return false;
        }
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasScanPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String bondStateName(int state) {
        if (state == BluetoothDevice.BOND_NONE) return "未配对";
        if (state == BluetoothDevice.BOND_BONDING) return "配对中";
        if (state == BluetoothDevice.BOND_BONDED) return "已配对";
        return "未知(" + state + ")";
    }

    private String profileStateName(int state) {
        if (state == BluetoothProfile.STATE_DISCONNECTED) return "未连接";
        if (state == BluetoothProfile.STATE_CONNECTING) return "连接中";
        if (state == BluetoothProfile.STATE_CONNECTED) return "已连接";
        if (state == BluetoothProfile.STATE_DISCONNECTING) return "断开中";
        return "未知(" + state + ")";
    }
}
