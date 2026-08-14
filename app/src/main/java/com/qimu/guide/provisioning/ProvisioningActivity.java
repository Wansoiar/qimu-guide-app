package com.qimu.guide.provisioning;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener;
import com.qimu.guide.BuildConfig;
import com.qimu.guide.MainActivity;
import com.qimu.guide.R;
import com.qimu.guide.config.OperatorConfigStore;
import com.qimu.guide.service.BleService;
import com.qimu.guide.util.PermissionUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备初始化向导：连接眼镜 → 录入手机 SN → 选择场馆 → 初始化并进入使用页。
 * 运营 token 过期时提示并回到登录页。
 */
public final class ProvisioningActivity extends AppCompatActivity {

    private static final long SCAN_TIMEOUT_MS = 15_000L;
    private static final int STEP_CONNECT = 1;
    private static final int STEP_SERIAL = 2;
    private static final int STEP_VENUE = 3;
    private static final int MAX_SERIAL_LENGTH = 64;

    private ProvisioningStore provisioningStore;
    private OperatorSessionStore operatorSessionStore;
    private ProvisioningApi provisioningApi;
    private OperatorConfigStore operatorConfigStore;
    private BleService bleService;

    private TextView tvConnectionStatus;
    private TextView tvConnectedGlasses;
    private TextView tvScanStatus;
    private TextView tvPhoneSerialStatus;
    private TextView tvVenuesStatus;
    private TextView tvStepCircleConnect;
    private TextView tvStepCircleSerial;
    private TextView tvStepCircleVenue;
    private TextView tvStepConnect;
    private TextView tvStepSerial;
    private TextView tvStepVenue;
    private LinearLayout scanResults;
    private View connectSection;
    private View serialSection;
    private View venueSection;
    private TextInputEditText phoneSerialInput;
    private MaterialButton scanButton;
    private MaterialButton btnPrev;
    private MaterialButton btnNext;
    private RadioGroup venueGroup;

    private final Map<Integer, ProvisioningApi.Venue> venuesByRadioId = new LinkedHashMap<>();
    private final Map<String, View> scanRowsByAddress = new LinkedHashMap<>();
    private int currentStep = STEP_CONNECT;
    private boolean scanning;
    private boolean venuesLoaded;
    private boolean initializing;

    private final BleService.BleListener bleListener = new BleService.BleListener() {
        @Override
        public void onConnectionStateChanged(int state) {
            runOnUiThread(() -> renderConnectionState(state));
        }

        @Override public void onBatteryUpdate(int level, boolean charging) { }
        @Override public void onFirmwareVersion(String version) { }
        @Override public void onMediaFileChanged(int photoCount, int videoCount, int audioCount) { }
        @Override public void onWifiStateChange(int state) { }
        @Override public void onWifiConnectionChanged(boolean connected) { }
        @Override public void onLog(String tag, String message) { }
        @Override public void onError(String message) {
            runOnUiThread(() -> Toast.makeText(ProvisioningActivity.this,
                    message, Toast.LENGTH_SHORT).show());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        provisioningStore = ProvisioningStore.get(this);
        if (provisioningStore.isInitialized()) {
            launchMain();
            return;
        }
        operatorSessionStore = OperatorSessionStore.get(this);
        if (operatorSessionStore.isExpired()) {
            Toast.makeText(this, R.string.provisioning_login_again, Toast.LENGTH_LONG).show();
            launchLogin();
            return;
        }

        setContentView(R.layout.activity_provisioning);
        provisioningApi = ProvisioningApiProvider.get();
        operatorConfigStore = OperatorConfigStore.get(this);
        bleService = BleService.getInstance();
        bindViews();
        bindActions();
        renderStep();
        renderConnectionState(bleService.getConnectionState());
    }

    private void bindViews() {
        tvConnectionStatus = findViewById(R.id.tv_provisioning_connection_status);
        tvConnectedGlasses = findViewById(R.id.tv_provisioning_glasses);
        tvScanStatus = findViewById(R.id.tv_provisioning_scan_status);
        tvPhoneSerialStatus = findViewById(R.id.tv_provisioning_phone_serial_status);
        tvVenuesStatus = findViewById(R.id.tv_provisioning_venues_status);
        tvStepCircleConnect = findViewById(R.id.tv_step_circle_connect);
        tvStepCircleSerial = findViewById(R.id.tv_step_circle_serial);
        tvStepCircleVenue = findViewById(R.id.tv_step_circle_venue);
        tvStepConnect = findViewById(R.id.tv_step_connect);
        tvStepSerial = findViewById(R.id.tv_step_serial);
        tvStepVenue = findViewById(R.id.tv_step_venue);
        scanResults = findViewById(R.id.layout_provisioning_scan_results);
        connectSection = findViewById(R.id.section_provisioning_connect);
        serialSection = findViewById(R.id.section_provisioning_serial);
        venueSection = findViewById(R.id.section_provisioning_venue);
        phoneSerialInput = findViewById(R.id.edit_provisioning_phone_serial);
        scanButton = findViewById(R.id.btn_provisioning_scan);
        btnPrev = findViewById(R.id.btn_provisioning_prev);
        btnNext = findViewById(R.id.btn_provisioning_next);
        venueGroup = findViewById(R.id.group_provisioning_venues);
    }

    private void bindActions() {
        scanButton.setOnClickListener(view -> {
            if (scanning) stopScan();
            else if (PermissionUtils.checkAndRequestPermissions(this)) startScan();
        });
        btnPrev.setOnClickListener(view -> {
            if (currentStep > STEP_CONNECT) goToStep(currentStep - 1);
        });
        btnNext.setOnClickListener(view -> onNext());
        venueGroup.setOnCheckedChangeListener((group, checkedId) -> updateStepControls());
        phoneSerialInput.addTextChangedListener(new TextWatcher() {
            private boolean editing;

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable editable) {
                if (editing) return;
                String normalized = sanitizePhoneSerial(editable.toString());
                if (!normalized.equals(editable.toString())) {
                    editing = true;
                    editable.replace(0, editable.length(), normalized);
                    editing = false;
                }
                updateSerialStatus();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (bleService != null) bleService.addListener(bleListener);
    }

    @Override
    protected void onStop() {
        if (bleService != null) {
            bleService.cancelScan();
            bleService.removeListener(bleListener);
        }
        scanning = false;
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PermissionUtils.REQUEST_CODE_BLUETOOTH) return;
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (granted && PermissionUtils.arePermissionsGranted(this)) startScan();
        else Toast.makeText(this, R.string.provisioning_permission_required,
                Toast.LENGTH_LONG).show();
    }

    private void startScan() {
        if (!bleService.isBluetoothEnabled()) {
            Toast.makeText(this, R.string.provisioning_bluetooth_required,
                    Toast.LENGTH_LONG).show();
            return;
        }
        scanRowsByAddress.clear();
        scanResults.removeAllViews();
        scanning = true;
        scanButton.setText(R.string.cancel_scan);
        tvScanStatus.setText(R.string.state_scanning);
        boolean started = bleService.startScan(new BleService.GlassesScanCallback() {
            @Override
            public void onScanning(BleService.GlassesScanResult result) {
                runOnUiThread(() -> addScanResult(result));
            }

            @Override
            public void onScanComplete(int resultCount) {
                runOnUiThread(() -> finishScan(null));
            }

            @Override
            public void onScanFailed(int errorCode) {
                runOnUiThread(() -> finishScan(getString(R.string.scan_start_failed)));
            }
        }, SCAN_TIMEOUT_MS);
        if (!started) finishScan(getString(R.string.scan_start_failed));
    }

    private void addScanResult(BleService.GlassesScanResult result) {
        if (!scanning || result == null || result.getDevice() == null) return;
        BluetoothDevice device = result.getDevice();
        String address;
        String name;
        try {
            address = device.getAddress();
            name = device.getName();
        } catch (SecurityException ignored) {
            return;
        }
        if (TextUtils.isEmpty(address) || scanRowsByAddress.containsKey(address)) return;
        String displayName = TextUtils.isEmpty(name) ? getString(R.string.unknown_device) : name;
        MaterialButton row = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        row.setAllCaps(false);
        row.setText(getString(R.string.provisioning_scan_result,
                displayName, MockProvisioningApi.normalizeMac(address), result.getRssi()));
        row.setOnClickListener(view -> {
            stopScan();
            tvScanStatus.setText(R.string.state_connecting);
            bleService.connect(address);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        scanResults.addView(row, params);
        scanRowsByAddress.put(address, row);
        tvScanStatus.setText(getString(R.string.devices_found, scanRowsByAddress.size()));
    }

    private void stopScan() {
        if (!scanning) return;
        bleService.cancelScan();
        finishScan(null);
    }

    private void finishScan(String error) {
        scanning = false;
        scanButton.setText(R.string.scan_and_connect);
        if (error != null) tvScanStatus.setText(error);
        else if (scanRowsByAddress.isEmpty()) tvScanStatus.setText(R.string.no_device_found);
        else tvScanStatus.setText(getString(R.string.devices_found, scanRowsByAddress.size()));
    }

    private void renderConnectionState(int state) {
        boolean connected = state == CRPBleConnectionStateListener.STATE_CONNECTED
                || bleService.isConnected();
        if (connected) {
            stopScan();
            tvConnectionStatus.setText(R.string.provisioning_glasses_connected);
            tvConnectionStatus.setTextColor(getColorCompat(R.color.qimu_connected));
            tvConnectedGlasses.setText(getString(R.string.provisioning_glasses_value,
                    safe(bleService.getDeviceName(), getString(R.string.unknown_device)),
                    MockProvisioningApi.normalizeMac(bleService.getConnectedAddress())));
            tvScanStatus.setText(R.string.state_connected);
        } else if (state == CRPBleConnectionStateListener.STATE_CONNECTING) {
            tvConnectionStatus.setText(R.string.state_connecting);
            tvConnectionStatus.setTextColor(getColorCompat(R.color.qimu_connecting));
            tvScanStatus.setText(R.string.state_connecting);
        } else {
            tvConnectionStatus.setText(R.string.provisioning_connect_first);
            tvConnectionStatus.setTextColor(getColorCompat(R.color.qimu_error));
            tvConnectedGlasses.setText(R.string.provisioning_no_glasses);
            tvScanStatus.setText(R.string.connect_hint);
        }
        updateStepControls();
    }

    private void renderStep() {
        connectSection.setVisibility(currentStep == STEP_CONNECT ? View.VISIBLE : View.GONE);
        serialSection.setVisibility(currentStep == STEP_SERIAL ? View.VISIBLE : View.GONE);
        venueSection.setVisibility(currentStep == STEP_VENUE ? View.VISIBLE : View.GONE);
        renderStepIndicator();
        btnPrev.setEnabled(currentStep > STEP_CONNECT);
        if (currentStep == STEP_VENUE && !venuesLoaded && !initializing) loadVenues();
        updateStepControls();
    }

    private void renderStepIndicator() {
        applyStep(tvStepCircleConnect, tvStepConnect, STEP_CONNECT);
        applyStep(tvStepCircleSerial, tvStepSerial, STEP_SERIAL);
        applyStep(tvStepCircleVenue, tvStepVenue, STEP_VENUE);
    }

    private void applyStep(TextView circle, TextView label, int step) {
        if (step < currentStep) {
            circle.setBackgroundResource(R.drawable.bg_step_circle_done);
            circle.setTextColor(getColorCompat(R.color.qimu_gold_dark));
            label.setTextColor(getColorCompat(R.color.qimu_gold_dark));
            label.setTypeface(null, Typeface.BOLD);
        } else if (step == currentStep) {
            circle.setBackgroundResource(R.drawable.bg_step_circle_current);
            circle.setTextColor(getColorCompat(R.color.qimu_surface));
            label.setTextColor(getColorCompat(R.color.qimu_brown));
            label.setTypeface(null, Typeface.BOLD);
        } else {
            circle.setBackgroundResource(R.drawable.bg_step_circle_idle);
            circle.setTextColor(getColorCompat(R.color.qimu_text_tertiary));
            label.setTextColor(getColorCompat(R.color.qimu_text_tertiary));
            label.setTypeface(null, Typeface.NORMAL);
        }
    }

    private void updateStepControls() {
        boolean complete;
        if (currentStep == STEP_CONNECT) {
            complete = bleService != null && bleService.isConnected();
        } else if (currentStep == STEP_SERIAL) {
            complete = MockProvisioningApi.isValidPhoneSerial(textOf(phoneSerialInput));
        } else {
            complete = venueGroup.getCheckedRadioButtonId() != -1;
        }
        btnNext.setVisibility(complete ? View.VISIBLE : View.GONE);
        btnNext.setText(currentStep == STEP_VENUE
                ? R.string.provisioning_initialize : R.string.provisioning_step_next);
        if (currentStep == STEP_VENUE) {
            btnNext.setEnabled(complete && !initializing);
        } else {
            btnNext.setEnabled(true);
        }
    }

    private void goToStep(int step) {
        if (operatorSessionStore.isExpired()) {
            Toast.makeText(this, R.string.provisioning_login_again, Toast.LENGTH_LONG).show();
            launchLogin();
            return;
        }
        currentStep = step;
        renderStep();
    }

    private void onNext() {
        if (currentStep == STEP_CONNECT) {
            if (!bleService.isConnected()) {
                Toast.makeText(this, R.string.provisioning_connect_first,
                        Toast.LENGTH_LONG).show();
                return;
            }
            goToStep(STEP_SERIAL);
        } else if (currentStep == STEP_SERIAL) {
            if (!MockProvisioningApi.isValidPhoneSerial(textOf(phoneSerialInput))) {
                Toast.makeText(this, R.string.provisioning_phone_serial_invalid,
                        Toast.LENGTH_LONG).show();
                return;
            }
            goToStep(STEP_VENUE);
        } else {
            initializeDevice();
        }
    }

    private void loadVenues() {
        if (operatorSessionStore.isExpired()) {
            Toast.makeText(this, R.string.provisioning_login_again, Toast.LENGTH_LONG).show();
            launchLogin();
            return;
        }
        tvVenuesStatus.setVisibility(View.VISIBLE);
        tvVenuesStatus.setText(R.string.provisioning_venues_loading);
        venueGroup.setEnabled(false);
        provisioningApi.listVenues(operatorSessionStore.token(),
                new ProvisioningApi.Callback<List<ProvisioningApi.Venue>>() {
                    @Override
                    public void onSuccess(List<ProvisioningApi.Venue> venues) {
                        venuesLoaded = true;
                        venueGroup.setEnabled(true);
                        renderVenues(venues);
                    }

                    @Override
                    public void onFailure(String message) {
                        venueGroup.setEnabled(true);
                        tvVenuesStatus.setVisibility(View.VISIBLE);
                        tvVenuesStatus.setText(message);
                        Toast.makeText(ProvisioningActivity.this, message,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void renderVenues(List<ProvisioningApi.Venue> venues) {
        venueGroup.removeAllViews();
        venuesByRadioId.clear();
        if (venues == null || venues.isEmpty()) {
            tvVenuesStatus.setVisibility(View.VISIBLE);
            tvVenuesStatus.setText(R.string.provisioning_no_venues);
            updateStepControls();
            return;
        }
        for (ProvisioningApi.Venue venue : venues) {
            RadioButton radio = new RadioButton(this);
            int id = View.generateViewId();
            radio.setId(id);
            radio.setText(venueRadioText(venue));
            radio.setPadding(0, dp(8), 0, dp(8));
            venueGroup.addView(radio);
            venuesByRadioId.put(id, venue);
        }
        venueGroup.check(venuesByRadioId.keySet().iterator().next());
        tvVenuesStatus.setVisibility(View.GONE);
        updateStepControls();
    }

    /** 场馆单选文案：名称加粗、地址次色小字，避免长地址挤压排版。 */
    private CharSequence venueRadioText(ProvisioningApi.Venue venue) {
        String name = safe(venue.name, "");
        String address = safe(venue.address, "");
        SpannableStringBuilder text = new SpannableStringBuilder(name);
        if (!address.isEmpty()) {
            int start = text.length();
            text.append('\n').append(address);
            text.setSpan(new ForegroundColorSpan(
                            getColorCompat(R.color.qimu_text_secondary)),
                    start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new RelativeSizeSpan(0.82f),
                    start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    private void initializeDevice() {
        if (operatorSessionStore.isExpired()) {
            Toast.makeText(this, R.string.provisioning_login_again, Toast.LENGTH_LONG).show();
            launchLogin();
            return;
        }
        ProvisioningApi.Venue venue = venuesByRadioId.get(venueGroup.getCheckedRadioButtonId());
        String glassesId = MockProvisioningApi.normalizeMac(bleService.getConnectedAddress());
        if (!bleService.isConnected() || glassesId.isEmpty() || venue == null
                || !MockProvisioningApi.isValidPhoneSerial(textOf(phoneSerialInput))) {
            Toast.makeText(this, R.string.provisioning_connect_first, Toast.LENGTH_LONG).show();
            return;
        }
        ProvisioningApi.DeviceReportRequest request = new ProvisioningApi.DeviceReportRequest(
                MockProvisioningApi.normalizePhoneSerial(textOf(phoneSerialInput)),
                Build.MANUFACTURER + " " + Build.MODEL,
                "Android " + Build.VERSION.RELEASE,
                BuildConfig.VERSION_NAME,
                glassesId,
                safe(bleService.getDeviceName(), getString(R.string.unknown_device)),
                venue);
        initializing = true;
        btnNext.setEnabled(false);
        btnNext.setText(R.string.provisioning_initializing);
        provisioningApi.initialize(operatorSessionStore.token(), request,
                new ProvisioningApi.Callback<ProvisioningApi.ProvisioningSnapshot>() {
                    @Override
                    public void onSuccess(ProvisioningApi.ProvisioningSnapshot snapshot) {
                        if (!provisioningStore.save(snapshot)) {
                            onFailure(getString(R.string.provisioning_save_failed));
                            return;
                        }
                        operatorConfigStore.saveDefaultVenue(
                                snapshot.venue.id, snapshot.venue.name);
                        Toast.makeText(ProvisioningActivity.this,
                                R.string.provisioning_complete, Toast.LENGTH_SHORT).show();
                        launchMain();
                    }

                    @Override
                    public void onFailure(String message) {
                        initializing = false;
                        btnNext.setText(R.string.provisioning_initialize);
                        updateStepControls();
                        Toast.makeText(ProvisioningActivity.this, message,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void updateSerialStatus() {
        String serial = textOf(phoneSerialInput);
        if (serial.isEmpty()) {
            tvPhoneSerialStatus.setText(R.string.provisioning_phone_serial_unverified);
            tvPhoneSerialStatus.setTextColor(getColorCompat(R.color.qimu_text_tertiary));
        } else if (MockProvisioningApi.isValidPhoneSerial(serial)) {
            tvPhoneSerialStatus.setText(R.string.provisioning_phone_serial_valid);
            tvPhoneSerialStatus.setTextColor(getColorCompat(R.color.qimu_connected));
        } else {
            tvPhoneSerialStatus.setText(R.string.provisioning_phone_serial_invalid);
            tvPhoneSerialStatus.setTextColor(getColorCompat(R.color.qimu_error));
        }
        updateStepControls();
    }

    /** 防呆输入：去空格、转大写、过滤非法字符，限制长度。 */
    private String sanitizePhoneSerial(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < MAX_SERIAL_LENGTH; i++) {
            char c = Character.toUpperCase(value.charAt(i));
            if (isAllowedSerialChar(c)) out.append(c);
        }
        return out.toString();
    }

    private boolean isAllowedSerialChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '-';
    }

    private void launchMain() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void launchLogin() {
        Intent intent = new Intent(this, LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private String textOf(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private String safe(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private int getColorCompat(int colorRes) {
        return androidx.core.content.ContextCompat.getColor(this, colorRes);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
