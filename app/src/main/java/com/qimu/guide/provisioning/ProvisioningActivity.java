package com.qimu.guide.provisioning;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Launcher gate shown until this APP installation has been provisioned. */
public final class ProvisioningActivity extends AppCompatActivity {

    private static final long SCAN_TIMEOUT_MS = 15_000L;

    private ProvisioningStore provisioningStore;
    private ProvisioningApi provisioningApi;
    private OperatorConfigStore operatorConfigStore;
    private BleService bleService;

    private TextView tvConnectionStatus;
    private TextView tvConnectedGlasses;
    private TextView tvScanStatus;
    private TextView tvInstallId;
    private LinearLayout scanResults;
    private View authSection;
    private View serialSection;
    private View venueSection;
    private TextInputEditText usernameInput;
    private TextInputEditText passwordInput;
    private TextInputEditText phoneSerialInput;
    private TextView tvPhoneSerialStatus;
    private MaterialButton scanButton;
    private MaterialButton loginButton;
    private MaterialButton verifyPhoneSerialButton;
    private MaterialButton initializeButton;
    private RadioGroup venueGroup;

    private final Map<Integer, ProvisioningApi.Venue> venuesByRadioId = new LinkedHashMap<>();
    private final Map<String, View> scanRowsByAddress = new LinkedHashMap<>();
    private ProvisioningApi.AuthSession authSession;
    private ProvisioningApi.PhoneIdentity phoneIdentity;
    private boolean scanning;

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

        setContentView(R.layout.activity_provisioning);
        provisioningApi = ProvisioningApiProvider.get();
        operatorConfigStore = OperatorConfigStore.get(this);
        bleService = BleService.getInstance();
        bindViews();
        bindActions();
        tvInstallId.setText(provisioningStore.installId());
        renderConnectionState(bleService.getConnectionState());
    }

    private void bindViews() {
        tvConnectionStatus = findViewById(R.id.tv_provisioning_connection_status);
        tvConnectedGlasses = findViewById(R.id.tv_provisioning_glasses);
        tvScanStatus = findViewById(R.id.tv_provisioning_scan_status);
        tvInstallId = findViewById(R.id.tv_provisioning_install_id);
        scanResults = findViewById(R.id.layout_provisioning_scan_results);
        authSection = findViewById(R.id.section_provisioning_auth);
        serialSection = findViewById(R.id.section_provisioning_serial);
        venueSection = findViewById(R.id.section_provisioning_venue);
        usernameInput = findViewById(R.id.edit_provisioning_username);
        passwordInput = findViewById(R.id.edit_provisioning_password);
        phoneSerialInput = findViewById(R.id.edit_provisioning_phone_serial);
        tvPhoneSerialStatus = findViewById(R.id.tv_provisioning_phone_serial_status);
        scanButton = findViewById(R.id.btn_provisioning_scan);
        loginButton = findViewById(R.id.btn_provisioning_login);
        verifyPhoneSerialButton = findViewById(R.id.btn_provisioning_verify_phone_serial);
        initializeButton = findViewById(R.id.btn_provisioning_initialize);
        venueGroup = findViewById(R.id.group_provisioning_venues);
        usernameInput.setText(MockProvisioningApi.MOCK_USERNAME);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            authSection.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
    }

    private void bindActions() {
        scanButton.setOnClickListener(view -> {
            if (scanning) stopScan();
            else if (PermissionUtils.checkAndRequestPermissions(this)) startScan();
        });
        loginButton.setOnClickListener(view -> loginOperator());
        verifyPhoneSerialButton.setOnClickListener(view -> verifyPhoneSerial());
        initializeButton.setOnClickListener(view -> initializeDevice());
        venueGroup.setOnCheckedChangeListener((group, checkedId) -> updateInitializeEnabled());
        phoneSerialInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (phoneIdentity == null) return;
                phoneIdentity = null;
                venueSection.setVisibility(View.GONE);
                tvPhoneSerialStatus.setText(R.string.provisioning_phone_serial_unverified);
                verifyPhoneSerialButton.setText(R.string.provisioning_verify_phone_serial);
                updateInitializeEnabled();
            }
            @Override public void afterTextChanged(Editable s) { }
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
            authSection.setVisibility(View.VISIBLE);
            loginButton.setEnabled(true);
            updateInitializeEnabled();
        } else if (state == CRPBleConnectionStateListener.STATE_CONNECTING) {
            tvConnectionStatus.setText(R.string.state_connecting);
            tvConnectionStatus.setTextColor(getColorCompat(R.color.qimu_connecting));
            loginButton.setEnabled(false);
        } else {
            tvConnectionStatus.setText(R.string.provisioning_connect_first);
            tvConnectionStatus.setTextColor(getColorCompat(R.color.qimu_error));
            tvConnectedGlasses.setText(R.string.provisioning_no_glasses);
            loginButton.setEnabled(false);
            updateInitializeEnabled();
        }
    }

    private void loginOperator() {
        if (!bleService.isConnected()) {
            Toast.makeText(this, R.string.must_connect_first, Toast.LENGTH_SHORT).show();
            return;
        }
        String username = textOf(usernameInput);
        String password = textOf(passwordInput);
        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.provisioning_credentials_required,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        loginButton.setEnabled(false);
        loginButton.setText(R.string.provisioning_verifying);
        provisioningApi.login(username, password, new ProvisioningApi.Callback<ProvisioningApi.AuthSession>() {
            @Override public void onSuccess(ProvisioningApi.AuthSession value) {
                authSession = value;
                phoneIdentity = null;
                venueSection.setVisibility(View.GONE);
                tvPhoneSerialStatus.setText(R.string.provisioning_phone_serial_unverified);
                verifyPhoneSerialButton.setText(R.string.provisioning_verify_phone_serial);
                loginButton.setEnabled(true);
                loginButton.setText(R.string.provisioning_verified);
                serialSection.setVisibility(View.VISIBLE);
            }

            @Override public void onFailure(String message) {
                loginButton.setEnabled(true);
                loginButton.setText(R.string.provisioning_verify_operator);
                Toast.makeText(ProvisioningActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void verifyPhoneSerial() {
        if (authSession == null || authSession.expiresAtEpochMs <= System.currentTimeMillis()) {
            Toast.makeText(this, R.string.provisioning_login_again, Toast.LENGTH_LONG).show();
            return;
        }
        String phoneSerial = MockProvisioningApi.normalizePhoneSerial(textOf(phoneSerialInput));
        if (!MockProvisioningApi.isValidPhoneSerial(phoneSerial)) {
            phoneSerialInput.setError(getString(R.string.provisioning_phone_serial_invalid));
            return;
        }
        phoneSerialInput.setError(null);
        verifyPhoneSerialButton.setEnabled(false);
        verifyPhoneSerialButton.setText(R.string.provisioning_phone_serial_verifying);
        provisioningApi.resolvePhoneSerial(authSession.operatorToken, phoneSerial,
                new ProvisioningApi.Callback<ProvisioningApi.PhoneIdentity>() {
                    @Override public void onSuccess(ProvisioningApi.PhoneIdentity value) {
                        if (value == null
                                || !phoneSerial.equals(MockProvisioningApi.normalizePhoneSerial(
                                        value.phoneSerial))) {
                            onFailure(getString(
                                    R.string.provisioning_phone_serial_response_invalid));
                            return;
                        }
                        if (!phoneSerial.equals(MockProvisioningApi.normalizePhoneSerial(
                                textOf(phoneSerialInput)))) {
                            verifyPhoneSerialButton.setEnabled(true);
                            verifyPhoneSerialButton.setText(
                                    R.string.provisioning_verify_phone_serial);
                            return;
                        }
                        phoneIdentity = null;
                        phoneSerialInput.setText(value.phoneSerial);
                        phoneSerialInput.setSelection(value.phoneSerial.length());
                        phoneIdentity = value;
                        tvPhoneSerialStatus.setText(value.existing
                                && !TextUtils.isEmpty(value.deviceId)
                                ? getString(R.string.provisioning_phone_serial_existing,
                                        value.deviceId)
                                : getString(R.string.provisioning_phone_serial_new));
                        verifyPhoneSerialButton.setEnabled(true);
                        verifyPhoneSerialButton.setText(R.string.provisioning_phone_serial_verified);
                        loadVenues(value.phoneSerial);
                    }

                    @Override public void onFailure(String message) {
                        phoneIdentity = null;
                        verifyPhoneSerialButton.setEnabled(true);
                        verifyPhoneSerialButton.setText(R.string.provisioning_verify_phone_serial);
                        tvPhoneSerialStatus.setText(R.string.provisioning_phone_serial_unverified);
                        Toast.makeText(ProvisioningActivity.this, message,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void loadVenues(String expectedPhoneSerial) {
        provisioningApi.listVenues(authSession.operatorToken,
                new ProvisioningApi.Callback<List<ProvisioningApi.Venue>>() {
                    @Override public void onSuccess(List<ProvisioningApi.Venue> venues) {
                        if (phoneIdentity == null
                                || !expectedPhoneSerial.equals(phoneIdentity.phoneSerial)
                                || !expectedPhoneSerial.equals(
                                        MockProvisioningApi.normalizePhoneSerial(
                                                textOf(phoneSerialInput)))) {
                            return;
                        }
                        renderVenues(venues);
                    }

                    @Override public void onFailure(String message) {
                        authSession = null;
                        loginButton.setEnabled(true);
                        loginButton.setText(R.string.provisioning_verify_operator);
                        Toast.makeText(ProvisioningActivity.this, message,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void renderVenues(List<ProvisioningApi.Venue> venues) {
        venueGroup.removeAllViews();
        venuesByRadioId.clear();
        if (venues == null || venues.isEmpty()) {
            Toast.makeText(this, R.string.provisioning_no_venues, Toast.LENGTH_LONG).show();
            return;
        }
        for (ProvisioningApi.Venue venue : venues) {
            RadioButton radio = new RadioButton(this);
            int id = View.generateViewId();
            radio.setId(id);
            radio.setText(venue.name + "\n" + venue.address);
            radio.setTextColor(getColorCompat(R.color.qimu_text_primary));
            radio.setPadding(0, dp(8), 0, dp(8));
            venueGroup.addView(radio);
            venuesByRadioId.put(id, venue);
        }
        venueSection.setVisibility(View.VISIBLE);
        Integer selectedId = null;
        if (phoneIdentity != null && phoneIdentity.currentVenue != null) {
            for (Map.Entry<Integer, ProvisioningApi.Venue> entry : venuesByRadioId.entrySet()) {
                if (phoneIdentity.currentVenue.id.equals(entry.getValue().id)) {
                    selectedId = entry.getKey();
                    break;
                }
            }
        }
        venueGroup.check(selectedId == null
                ? venuesByRadioId.keySet().iterator().next()
                : selectedId);
    }

    private void initializeDevice() {
        if (authSession == null || authSession.expiresAtEpochMs <= System.currentTimeMillis()) {
            Toast.makeText(this, R.string.provisioning_login_again, Toast.LENGTH_LONG).show();
            return;
        }
        ProvisioningApi.Venue venue = venuesByRadioId.get(venueGroup.getCheckedRadioButtonId());
        String glassesId = MockProvisioningApi.normalizeMac(bleService.getConnectedAddress());
        if (!bleService.isConnected() || glassesId.isEmpty() || venue == null
                || phoneIdentity == null
                || !phoneIdentity.phoneSerial.equals(
                        MockProvisioningApi.normalizePhoneSerial(textOf(phoneSerialInput)))) {
            Toast.makeText(this, R.string.provisioning_incomplete, Toast.LENGTH_LONG).show();
            return;
        }
        String installId = provisioningStore.installId();
        String androidIdHash = hashAndroidId();
        ProvisioningApi.InitializeRequest request = new ProvisioningApi.InitializeRequest(
                installId,
                installId,
                phoneIdentity.phoneSerial,
                androidIdHash,
                Build.MANUFACTURER + " " + Build.MODEL,
                "Android " + Build.VERSION.RELEASE,
                BuildConfig.VERSION_NAME,
                glassesId,
                safe(bleService.getDeviceName(), getString(R.string.unknown_device)),
                venue);
        initializeButton.setEnabled(false);
        initializeButton.setText(R.string.provisioning_initializing);
        provisioningApi.initialize(authSession.operatorToken, request,
                new ProvisioningApi.Callback<ProvisioningApi.ProvisioningSnapshot>() {
                    @Override public void onSuccess(ProvisioningApi.ProvisioningSnapshot snapshot) {
                        if (!provisioningStore.save(snapshot)) {
                            onFailure(getString(R.string.provisioning_save_failed));
                            return;
                        }
                        operatorConfigStore.saveDefaultVenue(snapshot.venue.id, snapshot.venue.name);
                        Toast.makeText(ProvisioningActivity.this,
                                R.string.provisioning_complete, Toast.LENGTH_SHORT).show();
                        launchMain();
                    }

                    @Override public void onFailure(String message) {
                        initializeButton.setText(R.string.provisioning_initialize);
                        updateInitializeEnabled();
                        Toast.makeText(ProvisioningActivity.this, message,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void updateInitializeEnabled() {
        initializeButton.setEnabled(bleService != null
                && bleService.isConnected()
                && phoneIdentity != null
                && venueGroup.getCheckedRadioButtonId() != -1);
    }

    private String hashAndroidId() {
        String androidId = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(androidId.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) out.append(String.format(Locale.ROOT, "%02x", item));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private void launchMain() {
        Intent intent = new Intent(this, MainActivity.class)
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
