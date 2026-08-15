package com.qimu.guide.ui.device;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.moyoung.glasses.conn.CRPBleConnection;
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener;
import com.moyoung.glasses.scan.CRPScanRecordParser;
import com.moyoung.glasses.scan.bean.CRPScanRecordInfo;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.qimu.guide.BuildConfig;
import com.qimu.guide.R;
import com.qimu.guide.config.OperatorConfigStore;
import com.qimu.guide.net.TourSessionApiClient;
import com.qimu.guide.net.TourSessionManager;
import com.qimu.guide.service.BleService;
import com.qimu.guide.service.TourReturnCoordinator;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class DeviceFragment extends Fragment {

    private static final long SCAN_TIMEOUT_MS = 15000;
    private static final long SCAN_LIST_UPDATE_DELAY_MS = 400;
    private static final long START_RECONNECT_TIMEOUT_MS = 15000;
    private static final String MOCK_ORDER_NO = "123456";
    private BleService bleService;
    private TourSessionManager tourSessionManager;
    private OperatorConfigStore operatorConfigStore;
    private com.qimu.guide.service.ScoFullDuplexProbe scoProbe;

    private View layoutDisconnected, layoutConnected;
    private TextView tvScanStatus, tvDeviceName, tvDeviceId, tvBattery, tvFirmware, tvDebugLog;
    private TextView tvBleStatus, tvAudioStatus, tvDeviceReady;
    private ImageView ivBleStatus, ivAudioStatus;
    private View btnScan, layoutDebug;
    private View layoutTourStart, layoutTourActive, layoutCleanupWarning;
    private Button btnStartTour;
    private TextView tvActiveTour, tvDefaultVenue, tvTourStartHint;
    private TextView btnToggleDebug;
    private RecyclerView recyclerDevices;

    private final List<ScanResultItem> deviceList = new ArrayList<>();
    private final Map<String, ScanResultItem> namedScanDevices = new LinkedHashMap<>();
    private final Map<String, Integer> parsedScanRecordHashes = new HashMap<>();
    private final Object scanResultsLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable publishScanResultsRunnable = this::publishScanResults;
    private final AtomicInteger scanGeneration = new AtomicInteger();
    private ScanDeviceAdapter deviceAdapter;
    private volatile boolean isScanning = false;
    private boolean scanPublishScheduled;
    private boolean waitingToStartAfterReconnect;
    private AlertDialog orderDialog;
    private final Runnable startReconnectTimeout = () -> {
        if (!waitingToStartAfterReconnect || !isAdded()) return;
        waitingToStartAfterReconnect = false;
        updateTourUi();
        Toast.makeText(requireContext(), "眼镜连接超时，请先扫描并连接眼镜", Toast.LENGTH_LONG).show();
    };

    private final TourSessionManager.Listener tourSessionListener = active -> {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(this::updateTourUi);
    };

    private final OperatorConfigStore.Listener operatorConfigListener = venue -> {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(this::updateTourUi);
    };

    private final BleService.BleListener bleListener = new BleService.BleListener() {
        @Override public void onConnectionStateChanged(int state) {
            appendLog("状态变化: " + stateToString(state));
            updateConnectionUI(state);
            if (state == CRPBleConnectionStateListener.STATE_CONNECTED
                    && waitingToStartAfterReconnect) {
                waitingToStartAfterReconnect = false;
                mainHandler.removeCallbacks(startReconnectTimeout);
                if (isAdded()) requireActivity().runOnUiThread(DeviceFragment.this::showOrderDialog);
            }
        }
        @Override public void onAudioConnectionStateChanged(int state) {
            appendLog("音频状态变化: " + audioStateToString(state));
            updateAudioConnectionUI(state);
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
        tourSessionManager = TourSessionManager.get();
        operatorConfigStore = OperatorConfigStore.get(requireContext());

        layoutDisconnected = v.findViewById(R.id.layout_disconnected);
        layoutConnected = v.findViewById(R.id.layout_connected);
        tvScanStatus = v.findViewById(R.id.tv_scan_status);
        tvDeviceName = v.findViewById(R.id.tv_device_name);
        tvDeviceId = v.findViewById(R.id.tv_device_id);
        tvBattery = v.findViewById(R.id.tv_battery);
        tvFirmware = v.findViewById(R.id.tv_firmware);
        tvBleStatus = v.findViewById(R.id.tv_ble_status);
        tvAudioStatus = v.findViewById(R.id.tv_audio_status);
        tvDeviceReady = v.findViewById(R.id.tv_device_ready);
        ivBleStatus = v.findViewById(R.id.iv_ble_status);
        ivAudioStatus = v.findViewById(R.id.iv_audio_status);
        recyclerDevices = v.findViewById(R.id.recycler_devices);
        tvDebugLog = v.findViewById(R.id.tv_debug_log);
        btnScan = v.findViewById(R.id.btn_scan);
        layoutTourStart = v.findViewById(R.id.layout_tour_start);
        layoutTourActive = v.findViewById(R.id.layout_tour_active);
        layoutCleanupWarning = v.findViewById(R.id.layout_cleanup_warning);
        btnStartTour = v.findViewById(R.id.btn_start_tour);
        tvActiveTour = v.findViewById(R.id.tv_active_tour);
        tvDefaultVenue = v.findViewById(R.id.tv_default_venue);
        tvTourStartHint = v.findViewById(R.id.tv_tour_start_hint);
        layoutDebug = v.findViewById(R.id.layout_debug);
        btnToggleDebug = v.findViewById(R.id.btn_toggle_debug);

        // Bind every callback target before subscribing. Bluetooth/profile
        // callbacks can arrive immediately when the Fragment is recreated.
        bleService.addListener(bleListener);
        tourSessionManager.addListener(tourSessionListener);
        operatorConfigStore.addListener(operatorConfigListener);

        deviceAdapter = new ScanDeviceAdapter(deviceList, addr -> {
            appendLog("选中设备: " + addr);
            connectToDevice(addr);
        });
        recyclerDevices.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerDevices.setAdapter(deviceAdapter);

        btnScan.setOnClickListener(vi -> {
            if (isScanning) stopScanByUser();
            else checkPermissionsAndScan();
        });

        btnStartTour.setOnClickListener(vi -> beginStartTourFlow());
        v.findViewById(R.id.btn_confirm_cleanup).setOnClickListener(vi ->
                new AlertDialog.Builder(requireContext())
                        .setTitle("确认眼镜已经清理？")
                        .setMessage("仅当管理员已经重置眼镜、确认上一位游客的照片已清除时继续。")
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton("确认已清理", (dialog, which) ->
                                tourSessionManager.clearCleanupWarning())
                        .show());

        btnToggleDebug.setOnClickListener(vi -> {
            boolean show = layoutDebug.getVisibility() != View.VISIBLE;
            layoutDebug.setVisibility(show ? View.VISIBLE : View.GONE);
            btnToggleDebug.setText(show
                    ? R.string.device_diagnostics_hide
                    : R.string.device_diagnostics_show);
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
            appendLog("通过统一状态机开始 WiFi 下载流程...");
            if (!bleService.startMediaDownload()) {
                appendLog("WiFi 下载未启动，请检查连接或现有任务");
            }
        });
        v.findViewById(R.id.btn_debug_a2dp).setOnClickListener(vi -> {
            appendLog("重新检查 BT/A2DP/HFP 音频连接...");
            bleService.ensureBluetoothAudioConnection();
        });
        v.findViewById(R.id.btn_debug_play_tone).setOnClickListener(vi -> playTestTone());
        v.findViewById(R.id.btn_debug_sco_probe).setOnClickListener(vi -> startScoProbe(vi));
        v.findViewById(R.id.btn_debug_clear_log).setOnClickListener(vi -> tvDebugLog.setText(""));

        // 恢复现有状态
        updateConnectionUI(bleService.getConnectionState());
        updateAudioConnectionUI(bleService.getAudioConnectionState());
        if (bleService.isConnected()) {
            tvDeviceName.setText(bleService.getDeviceName());
            tvDeviceId.setText(bleService.getConnectedAddress());
            int lvl = bleService.getBatteryLevel();
            if (lvl >= 0) tvBattery.setText(lvl + "%");
            String fw = bleService.getFirmwareVersion();
            if (!TextUtils.isEmpty(fw)) tvFirmware.setText(fw);
        }
        updateTourUi();
    }

    private void beginStartTourFlow() {
        if (tourSessionManager.isActive()) return;
        if (tourSessionManager.hasCleanupWarning()) {
            Toast.makeText(requireContext(),
                    "请先由管理员确认上一位游客的数据已经清理", Toast.LENGTH_LONG).show();
            return;
        }
        if (bleService.isConnected()
                || bleService.getConnectionState() == CRPBleConnectionStateListener.STATE_CONNECTED
                || BuildConfig.DEBUG) {
            showOrderDialog();
            return;
        }

        waitingToStartAfterReconnect = true;
        updateTourUi();
        bleService.autoReconnectLastDevice();
        mainHandler.removeCallbacks(startReconnectTimeout);
        mainHandler.postDelayed(startReconnectTimeout, START_RECONNECT_TIMEOUT_MS);
        Toast.makeText(requireContext(), "正在连接眼镜，请保持设备靠近", Toast.LENGTH_SHORT).show();
    }

    private void showOrderDialog() {
        if (!isAdded() || tourSessionManager.isActive()) return;
        if (orderDialog != null && orderDialog.isShowing()) return;

        int horizontalPadding = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(horizontalPadding, 0, horizontalPadding, 0);

        TextView hint = new TextView(requireContext());
        hint.setText(R.string.order_dialog_hint);
        hint.setTextColor(ContextCompat.getColor(requireContext(), R.color.qimu_text_secondary));
        hint.setTextSize(14);
        content.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextInputLayout inputLayout = new TextInputLayout(requireContext());
        inputLayout.setHint(R.string.order_number);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = Math.round(12 * getResources().getDisplayMetrics().density);
        content.addView(inputLayout, inputParams);

        TextInputEditText input = new TextInputEditText(inputLayout.getContext());
        input.setSingleLine(true);
        inputLayout.addView(input, new TextInputLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (BuildConfig.DEBUG) {
            input.setText(MOCK_ORDER_NO);
            input.setSelection(MOCK_ORDER_NO.length());
            inputLayout.setHelperText(getString(R.string.mock_order_helper));
        }

        orderDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.order_dialog_title)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.order_confirm_start, null)
                .create();
        orderDialog.setCancelable(false);
        orderDialog.setCanceledOnTouchOutside(false);
        orderDialog.setOnShowListener(ignored -> {
            Button confirm = orderDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            confirm.setEnabled(input.getText() != null
                    && !TextUtils.isEmpty(input.getText().toString().trim()));
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    confirm.setEnabled(!TextUtils.isEmpty(s == null ? "" : s.toString().trim()));
                    inputLayout.setError(null);
                }
                @Override public void afterTextChanged(Editable s) { }
            });
            confirm.setOnClickListener(view -> {
                String orderNo = input.getText() == null ? "" : input.getText().toString().trim();
                if (orderNo.isEmpty()) {
                    inputLayout.setError("请输入订单号");
                    return;
                }
                confirm.setEnabled(false);
                confirm.setText(R.string.order_creating_session);
                orderDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                createTourSession(orderNo, inputLayout, confirm);
            });
            input.requestFocus();
        });
        orderDialog.show();
    }

    private void createTourSession(String orderNo, TextInputLayout inputLayout, Button confirm) {
        final int requestGeneration = tourSessionManager.beginSessionRequest();
        if (requestGeneration < 0) {
            confirm.setText(R.string.order_confirm_start);
            confirm.setEnabled(true);
            Toast.makeText(requireContext(),
                    "当前无法开始新导览，请先处理现有会话或清理告警",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String deviceId = Settings.Secure.getString(
                requireContext().getContentResolver(), Settings.Secure.ANDROID_ID);
        if (TextUtils.isEmpty(deviceId)) deviceId = "android-unknown";
        final String finalDeviceId = deviceId;
        final OperatorConfigStore.Venue venue = operatorConfigStore.defaultVenue();

        if (BuildConfig.DEBUG && MOCK_ORDER_NO.equals(orderNo)) {
            // 本地 Demo 仍使用标准 UUID，避免它流入要求 UUID 格式的线上接口时触发 422。
            postSessionCreated(requestGeneration, UUID.randomUUID().toString(), orderNo,
                    finalDeviceId, venue, false, true,
                    getString(R.string.mock_session_notice));
            return;
        }

        if (!bleService.isConnected()
                && bleService.getConnectionState() != CRPBleConnectionStateListener.STATE_CONNECTED) {
            tourSessionManager.invalidatePendingSessionRequests();
            inputLayout.setError(getString(R.string.must_connect_first));
            confirm.setText(R.string.order_confirm_start);
            confirm.setEnabled(true);
            orderDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
            return;
        }

        TourSessionApiClient.get().createSession(orderNo, venue.id, deviceId,
                new TourSessionApiClient.CreateCallback() {
                    @Override
                    public void onSuccess(String sessionId) {
                        postSessionCreated(requestGeneration, sessionId, orderNo,
                                finalDeviceId, venue, true, false, null);
                    }

                    @Override
                    public void onError(String message, boolean transportUnavailable) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            if (!tourSessionManager.isSessionRequestCurrent(
                                    requestGeneration)) return;
                            if (orderDialog == null || !orderDialog.isShowing()) return;
                            inputLayout.setError(message);
                            confirm.setText(R.string.order_confirm_start);
                            confirm.setEnabled(true);
                            orderDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                            if (BuildConfig.DEBUG && transportUnavailable) {
                                new AlertDialog.Builder(requireContext())
                                        .setTitle("会话服务未连接")
                                        .setMessage("可以继续建立仅用于真机联调的本地会话，但不会校验订单号，也不会创建服务端 session。")
                                        .setNegativeButton("返回重试", null)
                                        .setPositiveButton("仅本地联调", (dialog, which) ->
                                                postSessionCreated(requestGeneration,
                                                        UUID.randomUUID().toString(), orderNo,
                                                        finalDeviceId, venue, false, false,
                                                        "当前是本地联调会话，订单尚未经过服务端校验"))
                                        .show();
                            }
                        });
                    }
                });
    }

    private void postSessionCreated(int requestGeneration, String sessionId,
                                    String orderNo, String deviceId,
                                    OperatorConfigStore.Venue venue,
                                    boolean serverBacked, boolean demoMode,
                                    @Nullable String notice) {
        if (!isAdded()) {
            if (serverBacked) {
                TourSessionApiClient.get().closeSession(sessionId, ignored -> { });
            }
            return;
        }
        requireActivity().runOnUiThread(() -> {
            if (!isAdded() || getView() == null
                    || TourReturnCoordinator.get().isInProgress()
                    || !tourSessionManager.isSessionRequestCurrent(requestGeneration)) {
                if (serverBacked) {
                    TourSessionApiClient.get().closeSession(sessionId, ignored -> { });
                }
                return;
            }
            if (!tourSessionManager.beginSession(requestGeneration, sessionId, orderNo,
                    venue.id, venue.name, deviceId, serverBacked, demoMode)) {
                if (serverBacked) {
                    TourSessionApiClient.get().closeSession(sessionId, ignored -> { });
                }
                return;
            }
            if (orderDialog != null) orderDialog.dismiss();
            if (notice != null) {
                Toast.makeText(requireContext(), notice, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updateTourUi() {
        if (!isAdded() || layoutTourStart == null) return;
        TourSessionManager.TourSession session = tourSessionManager.current();
        boolean active = session != null;
        layoutTourStart.setVisibility(active ? View.GONE : View.VISIBLE);
        layoutTourActive.setVisibility(active ? View.VISIBLE : View.GONE);
        layoutCleanupWarning.setVisibility(!active && tourSessionManager.hasCleanupWarning()
                ? View.VISIBLE : View.GONE);
        if (active) {
            tvActiveTour.setText("订单 " + session.orderNo + " · " + session.venueName);
        } else if (waitingToStartAfterReconnect) {
            btnStartTour.setText("正在连接眼镜…");
            btnStartTour.setEnabled(false);
        } else {
            OperatorConfigStore.Venue venue = operatorConfigStore.defaultVenue();
            tvDefaultVenue.setText(venue.name);
            tvTourStartHint.setText(BuildConfig.DEBUG
                    ? R.string.venue_mock_session_hint
                    : R.string.venue_session_hint);
            btnStartTour.setText(R.string.start_tour);
            btnStartTour.setEnabled(!tourSessionManager.hasCleanupWarning());
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
        if (!isAdded() || isHidden() || getView() == null || isScanning) return;
        if (!bleService.isBluetoothEnabled()) { Toast.makeText(getContext(), "请先开启蓝牙", Toast.LENGTH_SHORT).show(); return; }
        int generation = scanGeneration.incrementAndGet();
        clearScanResults();
        setScanningState(true); tvScanStatus.setText(R.string.state_scanning);
        appendLog("开始扫描设备...");
        boolean started = bleService.startScan(new BleService.GlassesScanCallback() {
            @Override public void onScanning(BleService.GlassesScanResult dev) {
                collectNamedDevice(dev, generation);
            }
            @Override public void onScanComplete(int resultCount) {
                if (!isScanning || generation != scanGeneration.get()) return;
                mainHandler.post(() -> finishScan(generation, resultCount));
            }
            @Override public void onScanFailed(int errorCode) {
                mainHandler.post(() -> handleScanFailure(generation, errorCode));
            }
        }, SCAN_TIMEOUT_MS);
        if (!started) {
            scanGeneration.incrementAndGet();
            setScanningState(false);
            tvScanStatus.setText(R.string.scan_start_failed);
        }
    }

    private void collectNamedDevice(BleService.GlassesScanResult scanDevice, int generation) {
        if (!isScanning || generation != scanGeneration.get()
                || scanDevice == null || scanDevice.getDevice() == null) return;

        BluetoothDevice bluetoothDevice = scanDevice.getDevice();
        String name;
        String address;
        try {
            name = bluetoothDevice.getName();
            address = bluetoothDevice.getAddress();
        } catch (SecurityException ignored) {
            return;
        }
        if (TextUtils.isEmpty(address)) return;

        byte[] scanRecord = scanDevice.getScanRecord();
        int scanRecordHash = Arrays.hashCode(scanRecord);
        synchronized (scanResultsLock) {
            if (generation != scanGeneration.get() || namedScanDevices.containsKey(address)) return;
            Integer previousHash = parsedScanRecordHashes.put(address, scanRecordHash);
            if (previousHash != null && previousHash == scanRecordHash) return;
        }

        CRPScanRecordInfo recordInfo;
        try {
            recordInfo = CRPScanRecordParser.parseScanRecord(scanRecord);
        } catch (RuntimeException ignored) {
            return;
        }
        // The glasses SDK marks supported devices with its A8-FE service data
        // and exposes the decoded firmware type through this parser.
        if (recordInfo == null || TextUtils.isEmpty(recordInfo.getFirmwareType())) return;
        String firmwareType = recordInfo.getFirmwareType().trim();
        if (!isValidFirmwareType(firmwareType)) return;
        String displayName = isDisplayableDeviceName(name)
                ? name.trim()
                : firmwareType + " 眼镜";

        synchronized (scanResultsLock) {
            if (generation != scanGeneration.get()) return;
            if (namedScanDevices.containsKey(address)) return;
            namedScanDevices.put(address,
                    new ScanResultItem(displayName, address, scanDevice.getRssi()));
            if (scanPublishScheduled) return;
            scanPublishScheduled = true;
        }
        mainHandler.postDelayed(publishScanResultsRunnable, SCAN_LIST_UPDATE_DELAY_MS);
    }

    private boolean isDisplayableDeviceName(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String normalized = name.trim();
        return !normalized.isEmpty()
                && !"unknown".equalsIgnoreCase(normalized)
                && !"unknown device".equalsIgnoreCase(normalized)
                && !"未知设备".equals(normalized)
                && !"null".equalsIgnoreCase(normalized);
    }

    private boolean isValidFirmwareType(String firmwareType) {
        if (firmwareType.length() != 3) return false;
        for (int i = 0; i < firmwareType.length(); i++) {
            char c = firmwareType.charAt(i);
            if (!((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    private void publishScanResults() {
        List<ScanResultItem> snapshot;
        synchronized (scanResultsLock) {
            scanPublishScheduled = false;
            snapshot = new ArrayList<>(namedScanDevices.values());
        }
        if (!isAdded() || deviceAdapter == null) return;
        int oldSize = deviceList.size();
        if (snapshot.size() > oldSize) {
            deviceList.addAll(snapshot.subList(oldSize, snapshot.size()));
            deviceAdapter.notifyItemRangeInserted(oldSize, snapshot.size() - oldSize);
        }
        if (isScanning) {
            tvScanStatus.setText(getString(R.string.devices_found_scanning, deviceList.size()));
        }
    }

    private void clearScanResults() {
        mainHandler.removeCallbacks(publishScanResultsRunnable);
        synchronized (scanResultsLock) {
            namedScanDevices.clear();
            parsedScanRecordHashes.clear();
            scanPublishScheduled = false;
        }
        int oldSize = deviceList.size();
        deviceList.clear();
        if (deviceAdapter != null && oldSize > 0) {
            deviceAdapter.notifyItemRangeRemoved(0, oldSize);
        }
    }

    private void finishScan(int generation, int rawCount) {
        if (!isScanning || generation != scanGeneration.get() || !isAdded()) return;
        mainHandler.removeCallbacks(publishScanResultsRunnable);
        publishScanResults();
        setScanningState(false);
        if (deviceList.isEmpty()) {
            tvScanStatus.setText(R.string.no_device_found);
        } else {
            tvScanStatus.setText(getString(R.string.devices_found, deviceList.size()));
        }
        appendLog("扫描完成，可显示 " + deviceList.size() + " 个，匹配广播 " + rawCount + " 条");
    }

    private void handleScanFailure(int generation, int errorCode) {
        if (!isScanning || generation != scanGeneration.get() || !isAdded()) return;
        scanGeneration.incrementAndGet();
        setScanningState(false);
        mainHandler.removeCallbacks(publishScanResultsRunnable);
        publishScanResults();
        tvScanStatus.setText(R.string.scan_start_failed);
        appendLog("扫描失败，错误码 " + errorCode);
    }

    private void stopScanByUser() {
        cancelActiveScan();
        if (deviceList.isEmpty()) {
            tvScanStatus.setText(R.string.scan_stopped);
        } else {
            tvScanStatus.setText(getString(R.string.devices_found, deviceList.size()));
        }
    }

    private void cancelActiveScan() {
        if (!isScanning) return;
        scanGeneration.incrementAndGet();
        bleService.cancelScan();
        setScanningState(false);
        mainHandler.removeCallbacks(publishScanResultsRunnable);
        publishScanResults();
    }

    private void connectToDevice(String addr) {
        cancelActiveScan();
        tvScanStatus.setText(R.string.state_connecting);
        appendLog("连接: " + addr);
        bleService.connect(addr);
    }

    private void setScanningState(boolean scanning) {
        isScanning = scanning;
        if (btnScan == null) return;
        Runnable updateButton = () -> ((TextView) btnScan).setText(scanning
                ? R.string.cancel_scan
                : R.string.scan_and_connect);
        if (Looper.myLooper() == Looper.getMainLooper()) updateButton.run();
        else btnScan.post(updateButton);
    }

    private void updateConnectionUI(int state) {
        if (!isAdded()) return;
        updateBleConnectionStatus(state);
        switch (state) {
            case CRPBleConnectionStateListener.STATE_CONNECTED:
                cancelActiveScan();
                showDeviceCard();
                int lvl = bleService.getBatteryLevel();
                tvBattery.setText(lvl >= 0 ? lvl + "%" : "查询中...");
                String fw = bleService.getFirmwareVersion();
                tvFirmware.setText(!TextUtils.isEmpty(fw) ? fw : "查询中...");
                break;
            case CRPBleConnectionStateListener.STATE_DISCONNECTED:
                if (!TextUtils.isEmpty(bleService.getConnectedAddress())
                        && bleService.isReconnecting()) {
                    showDeviceCard();
                } else {
                    layoutConnected.setVisibility(View.GONE);
                    layoutDisconnected.setVisibility(View.VISIBLE);
                    if (recyclerDevices != null) recyclerDevices.setVisibility(View.VISIBLE);
                    tvScanStatus.setText(R.string.state_disconnected);
                }
                break;
            case CRPBleConnectionStateListener.STATE_CONNECTING:
            case CRPBleConnectionStateListener.STATE_DISCONNECTING:
                showDeviceCard();
                break;
        }
        updateDeviceReadyMessage();
    }

    private void showDeviceCard() {
        layoutDisconnected.setVisibility(View.GONE);
        layoutConnected.setVisibility(View.VISIBLE);
        if (recyclerDevices != null) recyclerDevices.setVisibility(View.GONE);
        tvDeviceName.setText(bleService.getDeviceName());
        tvDeviceId.setText(bleService.getConnectedAddress());
    }

    private void updateBleConnectionStatus(int state) {
        if (tvBleStatus == null || ivBleStatus == null || !isAdded()) return;
        if (state == CRPBleConnectionStateListener.STATE_CONNECTED) {
            renderConnectionStatus(ivBleStatus, tvBleStatus, R.drawable.ic_link_20,
                    R.string.connection_connected, R.color.qimu_connected);
        } else if (state == CRPBleConnectionStateListener.STATE_CONNECTING
                || state == CRPBleConnectionStateListener.STATE_DISCONNECTING
                || bleService.isReconnecting()) {
            renderConnectionStatus(ivBleStatus, tvBleStatus, R.drawable.ic_link_20,
                    bleService.isReconnecting()
                            ? R.string.connection_reconnecting
                            : R.string.connection_connecting,
                    R.color.qimu_connecting);
        } else {
            renderConnectionStatus(ivBleStatus, tvBleStatus, R.drawable.ic_link_20,
                    R.string.connection_disconnected, R.color.qimu_error);
        }
    }

    private void updateAudioConnectionUI(int state) {
        if (tvAudioStatus == null || ivAudioStatus == null || !isAdded()) return;
        if (state == BleService.AUDIO_STATE_CONNECTED) {
            renderConnectionStatus(ivAudioStatus, tvAudioStatus, R.drawable.ic_headset_20,
                    R.string.connection_connected, R.color.qimu_connected);
        } else if (state == BleService.AUDIO_STATE_CONNECTING) {
            renderConnectionStatus(ivAudioStatus, tvAudioStatus, R.drawable.ic_headset_20,
                    R.string.connection_connecting, R.color.qimu_connecting);
        } else {
            renderConnectionStatus(ivAudioStatus, tvAudioStatus, R.drawable.ic_headset_off_20,
                    R.string.connection_disconnected, R.color.qimu_error);
        }
        updateDeviceReadyMessage();
    }

    private void renderConnectionStatus(ImageView icon, TextView label,
                                        int iconRes, int textRes, int colorRes) {
        int color = ContextCompat.getColor(requireContext(), colorRes);
        icon.setImageResource(iconRes);
        icon.setColorFilter(color);
        label.setText(textRes);
        label.setTextColor(color);
    }

    private void updateDeviceReadyMessage() {
        if (tvDeviceReady == null || bleService == null) return;
        if (bleService.getConnectionState() != CRPBleConnectionStateListener.STATE_CONNECTED) {
            tvDeviceReady.setText(R.string.device_connecting_body);
        } else if (bleService.getAudioConnectionState() == BleService.AUDIO_STATE_CONNECTED) {
            tvDeviceReady.setText(R.string.device_ready_body);
        } else if (bleService.getAudioConnectionState() == BleService.AUDIO_STATE_CONNECTING) {
            tvDeviceReady.setText(R.string.device_audio_connecting_body);
        } else {
            tvDeviceReady.setText(R.string.device_audio_disconnected_body);
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

    private String audioStateToString(int state) {
        switch (state) {
            case BleService.AUDIO_STATE_CONNECTING: return "CONNECTING";
            case BleService.AUDIO_STATE_CONNECTED: return "CONNECTED";
            default: return "DISCONNECTED";
        }
    }

    private android.speech.tts.TextToSpeech tts;

    @Override
    public void onResume() {
        super.onResume();
        if (!isHidden()) refreshVisibleState();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden && bleService != null) cancelActiveScan();
        if (!hidden) refreshVisibleState();
    }

    private void refreshVisibleState() {
        if (bleService == null || getView() == null) return;
        updateConnectionUI(bleService.getConnectionState());
        updateAudioConnectionUI(bleService.getAudioConnectionState());
        updateTourUi();
    }

    @Override
    public void onStop() {
        if (bleService != null) cancelActiveScan();
        super.onStop();
    }

    @Override public void onDestroyView() {
        cancelActiveScan();
        scanGeneration.incrementAndGet();
        mainHandler.removeCallbacks(publishScanResultsRunnable);
        mainHandler.removeCallbacks(startReconnectTimeout);
        if (tourSessionManager != null) tourSessionManager.removeListener(tourSessionListener);
        if (operatorConfigStore != null) operatorConfigStore.removeListener(operatorConfigListener);
        bleService.removeListener(bleListener);
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        super.onDestroyView();
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

    /**
     * SCO 全双工自测：进 MODE_IN_COMMUNICATION + startBluetoothSco，边外放正弦音边录音，
     * 打印录音 RMS。外放期间对眼镜说话若 RMS 跳动=全双工OK（拆薄方案可行）。
     * 前提：眼镜已作为蓝牙耳机连上（A2DP+HFP 均 connected，看设备页音频状态）。
     */
    private void startScoProbe(View button) {
        if (bleService == null || !bleService.isConnected()) {
            appendLog("请先连接眼镜再做 SCO 自测");
            return;
        }
        if (bleService.getAudioConnectionState() != BleService.AUDIO_STATE_CONNECTED) {
            appendLog("⚠️ 蓝牙音频(A2DP/HFP)未就绪，SCO 大概率走手机内置麦——先点「音频配对」");
        }
        if (scoProbe == null) {
            scoProbe = new com.qimu.guide.service.ScoFullDuplexProbe(requireContext());
        }
        button.setEnabled(false);
        appendLog("戴上眼镜，点开始后对着眼镜连续说话约 10 秒……");
        scoProbe.start(new com.qimu.guide.service.ScoFullDuplexProbe.Listener() {
            @Override public void onLog(String line) { appendLog(line); }
            @Override public void onFinished() { button.setEnabled(true); }
        });
    }

    // ── 扫描列表适配器 ──

    private static class ScanResultItem {
        final String name;
        final String address;
        final int rssi;

        ScanResultItem(String name, String address, int rssi) {
            this.name = name;
            this.address = address;
            this.rssi = rssi;
        }
    }

    private static class ScanDeviceAdapter extends RecyclerView.Adapter<ScanDeviceAdapter.ViewHolder> {
        private final List<ScanResultItem> devices;
        private final OnDeviceClickListener listener;
        interface OnDeviceClickListener { void onDeviceClick(String a); }
        ScanDeviceAdapter(List<ScanResultItem> d, OnDeviceClickListener l) { devices = d; listener = l; }
        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new ViewHolder(LayoutInflater.from(p.getContext()).inflate(R.layout.item_scan_device, p, false));
        }
        @Override public void onBindViewHolder(@NonNull ViewHolder h, int p) {
            ScanResultItem device = devices.get(p);
            h.tvName.setText(device.name);
            h.tvAddress.setText(device.address);
            h.tvRssi.setText(device.rssi + " dBm");
            h.itemView.setOnClickListener(v -> listener.onDeviceClick(device.address));
        }
        @Override public int getItemCount() { return devices.size(); }
        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvAddress, tvRssi;
            ViewHolder(@NonNull View v) {
                super(v);
                tvName = v.findViewById(R.id.tv_scan_name);
                tvAddress = v.findViewById(R.id.tv_scan_address);
                tvRssi = v.findViewById(R.id.tv_scan_rssi);
            }
        }
    }
}
