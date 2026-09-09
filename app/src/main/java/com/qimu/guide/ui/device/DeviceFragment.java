package com.qimu.guide.ui.device;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.TextPaint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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

import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener;
import com.moyoung.glasses.scan.CRPScanRecordParser;
import com.moyoung.glasses.scan.bean.CRPScanRecordInfo;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.qimu.guide.BuildConfig;
import com.qimu.guide.R;
import com.qimu.guide.config.OperatorConfigStore;
import com.qimu.guide.net.TourSessionApiClient;
import com.qimu.guide.net.TourSessionManager;
import com.qimu.guide.provisioning.ProvisioningApi;
import com.qimu.guide.provisioning.ProvisioningStore;
import com.qimu.guide.service.BleService;
import com.qimu.guide.service.TourReturnCoordinator;
import com.qimu.guide.ui.widget.SerifTextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class DeviceFragment extends Fragment {

    private static final long SCAN_TIMEOUT_MS = 15000;
    private static final long SCAN_LIST_UPDATE_DELAY_MS = 400;
    private static final long START_RECONNECT_TIMEOUT_MS = 15000;
    private BleService bleService;
    private TourSessionManager tourSessionManager;
    private OperatorConfigStore operatorConfigStore;

    private View layoutDisconnected, layoutConnected;
    private TextView tvScanStatus, tvDeviceName, tvDeviceId, tvBattery, tvFirmware;
    private TextView tvBleStatus, tvAudioStatus, tvDeviceReady;
    private View btnScan;
    private View layoutTourStart, layoutTourActive, layoutCleanupWarning;
    private Button btnStartTour;
    private TextView tvDefaultVenue, tvTourStartHint;
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
    private AlertDialog lastOrderProgressDialog;
    private TextView lastOrderProgressText;
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

    private final TourReturnCoordinator.Listener lastOrderReturnListener =
            new TourReturnCoordinator.Listener() {
                @Override
                public void onReturnStageChanged(String message) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> showLastOrderProgress(message));
                }

                @Override
                public void onReturnFinished(boolean glassesResetConfirmed,
                                             boolean serverCloseSucceeded,
                                             boolean localCleanupSucceeded) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (lastOrderProgressDialog != null) {
                            lastOrderProgressDialog.dismiss();
                            lastOrderProgressDialog = null;
                        }
                        if (!localCleanupSucceeded) {
                            Toast.makeText(requireContext(),
                                    "上次导览缓存未完全清理，已阻止开始新导览；请立即告知管理员",
                                    Toast.LENGTH_LONG).show();
                        } else if (!glassesResetConfirmed) {
                            Toast.makeText(requireContext(),
                                    "眼镜清理未确认，请归还前告知管理员",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(requireContext(),
                                    "上次订单已结束，眼镜已为下一位游客准备就绪",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
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
                if (isAdded()) requireActivity().runOnUiThread(DeviceFragment.this::showStartDialog);
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
        recyclerDevices = v.findViewById(R.id.recycler_devices);
        btnScan = v.findViewById(R.id.btn_scan);
        layoutTourStart = v.findViewById(R.id.layout_tour_start);
        layoutTourActive = v.findViewById(R.id.layout_tour_active);
        layoutCleanupWarning = v.findViewById(R.id.layout_cleanup_warning);
        btnStartTour = v.findViewById(R.id.btn_start_tour);
        tvDefaultVenue = v.findViewById(R.id.tv_default_venue);
        tvTourStartHint = v.findViewById(R.id.tv_tour_start_hint);

        // Bind every callback target before subscribing. Bluetooth/profile
        // callbacks can arrive immediately when the Fragment is recreated.
        bleService.addListener(bleListener);
        tourSessionManager.addListener(tourSessionListener);
        operatorConfigStore.addListener(operatorConfigListener);
        TourReturnCoordinator.get().addListener(lastOrderReturnListener);

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
                // 仅清除提示；管理员已手工处理时用此按钮放行继续开导览。
                tourSessionManager.clearCleanupWarning());

        v.findViewById(R.id.btn_end_last_order).setOnClickListener(vi ->
                beginLastOrderReturn());

        v.findViewById(R.id.btn_debug_disconnect).setOnClickListener(vi -> { bleService.disconnect(); appendLog("手动断开连接"); });

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
            showStartDialog();
            return;
        }

        waitingToStartAfterReconnect = true;
        updateTourUi();
        bleService.autoReconnectLastDevice();
        mainHandler.removeCallbacks(startReconnectTimeout);
        mainHandler.postDelayed(startReconnectTimeout, START_RECONNECT_TIMEOUT_MS);
        Toast.makeText(requireContext(), "正在连接眼镜，请保持设备靠近", Toast.LENGTH_SHORT).show();
    }

    private void beginLastOrderReturn() {
        if (!isAdded()) return;
        String last = tourSessionManager.lastSessionId();
        if (last == null || last.trim().isEmpty()) {
            Toast.makeText(requireContext(), "未找到上次导览记录", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!TourReturnCoordinator.get().beginStaleOrderReturn(last)) {
            Toast.makeText(requireContext(), "结束上次订单失败，请重试或先连接眼镜", Toast.LENGTH_LONG).show();
        }
    }

    private void showLastOrderProgress(String message) {
        if (!isAdded() || getView() == null) return;
        if (lastOrderProgressDialog == null) {
            LinearLayout content = new LinearLayout(requireContext());
            content.setOrientation(LinearLayout.VERTICAL);
            int padding = Math.round(24 * getResources().getDisplayMetrics().density);
            content.setPadding(padding, padding / 2, padding, padding);
            ProgressBar progress = new ProgressBar(requireContext());
            content.addView(progress, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            lastOrderProgressText = new TextView(requireContext());
            lastOrderProgressText.setTextColor(ContextCompat.getColor(
                    requireContext(), R.color.qimu_text_secondary));
            lastOrderProgressText.setTextSize(14);
            lastOrderProgressText.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            textParams.topMargin = padding / 2;
            content.addView(lastOrderProgressText, textParams);
            lastOrderProgressDialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.end_last_order_progress_title)
                    .setView(content)
                    .setCancelable(false)
                    .create();
        }
        lastOrderProgressText.setText(message);
        if (!lastOrderProgressDialog.isShowing()) lastOrderProgressDialog.show();
    }

    private void showStartDialog() {
        if (!isAdded() || tourSessionManager.isActive()) return;
        if (orderDialog != null && orderDialog.isShowing()) return;

        int horizontalPadding = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(horizontalPadding, 0, horizontalPadding, 0);

        // 标题用宋体并作为内容首行，替代 Material 框架标题区（框架标题顶部留白过大）。
        SerifTextView titleView = new SerifTextView(requireContext());
        titleView.setText(R.string.start_tour);
        titleView.setTextColor(ContextCompat.getColor(requireContext(), R.color.qimu_text_primary));
        titleView.setTextSize(20);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = Math.round(16 * getResources().getDisplayMetrics().density);
        content.addView(titleView, titleParams);

        TextView hint = new TextView(requireContext());
        hint.setText(R.string.start_dialog_hint);
        hint.setTextColor(ContextCompat.getColor(requireContext(), R.color.qimu_text_secondary));
        hint.setTextSize(14);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = Math.round(12 * getResources().getDisplayMetrics().density);
        content.addView(hint, hintParams);

        TextInputLayout inputLayout = new TextInputLayout(requireContext());
        inputLayout.setHint(R.string.tourist_phone);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = Math.round(12 * getResources().getDisplayMetrics().density);
        content.addView(inputLayout, inputParams);

        TextInputEditText input = new TextInputEditText(inputLayout.getContext());
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        inputLayout.addView(input, new TextInputLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout consentRow = new LinearLayout(requireContext());
        consentRow.setOrientation(LinearLayout.HORIZONTAL);
        consentRow.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams consentRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        consentRowParams.topMargin = Math.round(10 * getResources().getDisplayMetrics().density);
        content.addView(consentRow, consentRowParams);

        MaterialCheckBox consentCheckbox = new MaterialCheckBox(requireContext());
        consentCheckbox.setChecked(false);
        consentCheckbox.setContentDescription(getString(R.string.privacy_consent_checkbox));
        consentCheckbox.setMinHeight(Math.round(
                48 * getResources().getDisplayMetrics().density));
        // MaterialCheckBox 自带前导 minWidth/内边距，清零让勾选框与手机号输入框左缘对齐。
        consentCheckbox.setMinWidth(0);
        consentCheckbox.setMinimumWidth(0);
        consentCheckbox.setPadding(0, 0, 0, 0);
        consentRow.addView(consentCheckbox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView consentText = new TextView(requireContext());
        String consentLabel = getString(R.string.privacy_consent_checkbox);
        String policyLabel = getString(R.string.privacy_policy_link);
        int policyStart = consentLabel.indexOf(policyLabel);
        SpannableString consentSpannable = new SpannableString(consentLabel);
        if (policyStart >= 0) {
            consentSpannable.setSpan(new ClickableSpan() {
                @Override public void onClick(@NonNull View widget) {
                    showPrivacyPolicyDialog();
                }

                @Override public void updateDrawState(@NonNull TextPaint drawState) {
                    super.updateDrawState(drawState);
                    drawState.setColor(ContextCompat.getColor(
                            requireContext(), R.color.qimu_gold_dark));
                    drawState.setUnderlineText(false);
                }
            }, policyStart, policyStart + policyLabel.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        consentText.setText(consentSpannable);
        consentText.setTextColor(ContextCompat.getColor(
                requireContext(), R.color.qimu_text_primary));
        consentText.setTextSize(13);
        consentText.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        consentText.setMovementMethod(LinkMovementMethod.getInstance());
        consentText.setHighlightColor(android.graphics.Color.TRANSPARENT);
        consentRow.addView(consentText, new LinearLayout.LayoutParams(
                0, Math.round(48 * getResources().getDisplayMetrics().density), 1));

        orderDialog = new AlertDialog.Builder(requireContext())
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.start_confirm, null)
                .create();
        orderDialog.setCancelable(false);
        orderDialog.setCanceledOnTouchOutside(false);
        orderDialog.setOnShowListener(ignored -> {
            Button confirm = orderDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Runnable updateConfirmState = () -> confirm.setEnabled(
                    input.getText() != null
                            && !TextUtils.isEmpty(input.getText().toString().trim())
                            && consentCheckbox.isChecked());
            updateConfirmState.run();
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    updateConfirmState.run();
                    inputLayout.setError(null);
                }
                @Override public void afterTextChanged(Editable s) { }
            });
            consentCheckbox.setOnCheckedChangeListener((button, checked) ->
                    updateConfirmState.run());
            confirm.setOnClickListener(view -> {
                String phone = input.getText() == null ? "" : input.getText().toString().trim();
                if (phone.isEmpty()) {
                    inputLayout.setError(getString(R.string.tourist_phone_required));
                    return;
                }
                if (!consentCheckbox.isChecked()) {
                    Toast.makeText(requireContext(), R.string.privacy_consent_required,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                confirm.setEnabled(false);
                confirm.setText(R.string.start_creating_session);
                orderDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                startTour(phone, consentCheckbox.isChecked(), inputLayout, confirm);
            });
            input.requestFocus();
        });
        orderDialog.show();
    }

    private void startTour(String phone, boolean privacyConsent,
                           TextInputLayout inputLayout, Button confirm) {
        if (!privacyConsent) {
            confirm.setText(R.string.start_confirm);
            confirm.setEnabled(true);
            Toast.makeText(requireContext(), R.string.privacy_consent_required,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final int requestGeneration = tourSessionManager.beginSessionRequest();
        if (requestGeneration < 0) {
            confirm.setText(R.string.start_confirm);
            confirm.setEnabled(true);
            Toast.makeText(requireContext(),
                    "当前无法开始新导览，请先处理现有会话或清理告警",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final OperatorConfigStore.Venue venue = operatorConfigStore.defaultVenue();
        // 设备标识从初始化内存取（眼镜 MAC + 手机 device_id），不再取 ANDROID_ID。
        ProvisioningApi.ProvisioningSnapshot provisioned =
                ProvisioningStore.get(requireContext()).snapshot();
        if (provisioned == null) {
            confirm.setText(R.string.start_confirm);
            confirm.setEnabled(true);
            Toast.makeText(requireContext(), "设备尚未完成初始化，请先完成设备初始化",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String glassesId = provisioned.glassesId == null ? "" : provisioned.glassesId.trim();
        String phoneDeviceId = provisioned.deviceId == null ? "" : provisioned.deviceId.trim();

        if (!bleService.isConnected()
                && bleService.getConnectionState() != CRPBleConnectionStateListener.STATE_CONNECTED
                && !BuildConfig.DEBUG) {
            tourSessionManager.invalidatePendingSessionRequests();
            inputLayout.setError(getString(R.string.must_connect_first));
            confirm.setText(R.string.start_confirm);
            confirm.setEnabled(true);
            orderDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
            return;
        }

        TourSessionApiClient.get().startRental(venue.id, glassesId, phoneDeviceId, phone,
                new TourSessionApiClient.CreateCallback() {
                    @Override
                    public void onSuccess(String sessionId) {
                        postSessionCreated(requestGeneration, sessionId, phone,
                                venue, null);
                    }

                    @Override
                    public void onError(String message, boolean transportUnavailable) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            if (!tourSessionManager.isSessionRequestCurrent(
                                    requestGeneration)) return;
                            if (orderDialog == null || !orderDialog.isShowing()) return;
                            inputLayout.setError(message);
                            confirm.setText(R.string.start_confirm);
                            confirm.setEnabled(true);
                            orderDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                        });
                    }
                });
    }

    private void postSessionCreated(int requestGeneration, String sessionId,
                                    String phone, OperatorConfigStore.Venue venue,
                                    @Nullable String notice) {
        // 不满足本地建会话条件时不请求后端收尾：未起 RTC_task、无火山计费，后端没有
        // 对应的"作废新建会话"接口，遗留的孤儿 session 由后端 reconcile_orphan_sessions 兜底。
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            if (!isAdded() || getView() == null
                    || TourReturnCoordinator.get().isInProgress()
                    || !tourSessionManager.isSessionRequestCurrent(requestGeneration)) {
                return;
            }
            if (!tourSessionManager.beginSession(requestGeneration, sessionId, phone,
                    venue.id, venue.name)) {
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
        boolean active = tourSessionManager.isActive();
        OperatorConfigStore.Venue venue = operatorConfigStore.defaultVenue();
        layoutTourStart.setVisibility(active ? View.GONE : View.VISIBLE);
        layoutTourActive.setVisibility(active ? View.VISIBLE : View.GONE);
        layoutCleanupWarning.setVisibility(!active && tourSessionManager.hasCleanupWarning()
                ? View.VISIBLE : View.GONE);
        if (active) {
            // 导览进行中：卡片固定展示“导览进行中”，不再展示场馆/手机号副文案。
            return;
        }
        if (waitingToStartAfterReconnect) {
            btnStartTour.setText("正在连接眼镜…");
            btnStartTour.setEnabled(false);
            return;
        }
        tvDefaultVenue.setText(venue.name);
        tvTourStartHint.setText(R.string.venue_session_hint);
        btnStartTour.setText(R.string.start_tour);
        boolean deviceReady = bleService != null
                && bleService.getConnectionState() == CRPBleConnectionStateListener.STATE_CONNECTED
                && bleService.getAudioConnectionState() == BleService.AUDIO_STATE_CONNECTED;
        btnStartTour.setEnabled(!tourSessionManager.hasCleanupWarning() && deviceReady);
    }

    private void showPrivacyPolicyDialog() {
        if (!isAdded()) return;
        final String policyText;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getResources().openRawResource(R.raw.user_privacy_policy)))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (content.length() > 0) content.append('\n');
                content.append(line);
            }
            policyText = content.toString();
        } catch (IOException | RuntimeException error) {
            Toast.makeText(requireContext(), R.string.privacy_policy_load_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }

        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        TextView policyView = new TextView(requireContext());
        policyView.setText(policyText);
        policyView.setTextColor(ContextCompat.getColor(requireContext(), R.color.qimu_text_primary));
        policyView.setTextSize(14);
        policyView.setLineSpacing(0, 1.25f);
        policyView.setPadding(padding, padding, padding, padding);
        policyView.setTextIsSelectable(true);

        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.addView(policyView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.privacy_policy_title)
                .setView(scrollView)
                .setPositiveButton(R.string.privacy_policy_close, null)
                .show();
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
        updateTourUi();
    }

    private void showDeviceCard() {
        layoutDisconnected.setVisibility(View.GONE);
        layoutConnected.setVisibility(View.VISIBLE);
        if (recyclerDevices != null) recyclerDevices.setVisibility(View.GONE);
        tvDeviceName.setText(bleService.getDeviceName());
        tvDeviceId.setText(bleService.getConnectedAddress());
    }

    private void updateBleConnectionStatus(int state) {
        if (tvBleStatus == null || !isAdded()) return;
        if (state == CRPBleConnectionStateListener.STATE_CONNECTED) {
            renderConnectionStatus(tvBleStatus, R.string.connection_connected, R.color.qimu_text_primary);
        } else if (state == CRPBleConnectionStateListener.STATE_CONNECTING
                || state == CRPBleConnectionStateListener.STATE_DISCONNECTING
                || bleService.isReconnecting()) {
            renderConnectionStatus(tvBleStatus, bleService.isReconnecting()
                            ? R.string.connection_reconnecting
                            : R.string.connection_connecting,
                    R.color.qimu_connecting);
        } else {
            renderConnectionStatus(tvBleStatus, R.string.connection_disconnected, R.color.qimu_error);
        }
    }

    private void updateAudioConnectionUI(int state) {
        if (tvAudioStatus == null || !isAdded()) return;
        if (state == BleService.AUDIO_STATE_CONNECTED) {
            renderConnectionStatus(tvAudioStatus, R.string.connection_connected, R.color.qimu_text_primary);
        } else if (state == BleService.AUDIO_STATE_CONNECTING) {
            renderConnectionStatus(tvAudioStatus, R.string.connection_connecting, R.color.qimu_connecting);
        } else {
            renderConnectionStatus(tvAudioStatus, R.string.connection_disconnected, R.color.qimu_error);
        }
        updateDeviceReadyMessage();
        updateTourUi();
    }

    private void renderConnectionStatus(TextView label, int textRes, int colorRes) {
        int color = ContextCompat.getColor(requireContext(), colorRes);
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
        if (BuildConfig.DEBUG) android.util.Log.d("DeviceFragment", msg);
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
        TourReturnCoordinator.get().removeListener(lastOrderReturnListener);
        if (lastOrderProgressDialog != null) {
            lastOrderProgressDialog.dismiss();
            lastOrderProgressDialog = null;
        }
        super.onDestroyView();
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
