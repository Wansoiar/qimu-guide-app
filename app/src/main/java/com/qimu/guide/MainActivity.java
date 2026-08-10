package com.qimu.guide;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.textfield.TextInputEditText;
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener;
import com.qimu.guide.config.OperatorConfigStore;
import com.qimu.guide.net.TourSessionManager;
import com.qimu.guide.provisioning.MockProvisioningApi;
import com.qimu.guide.provisioning.ProvisioningActivity;
import com.qimu.guide.provisioning.ProvisioningApi;
import com.qimu.guide.provisioning.ProvisioningApiProvider;
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

    private BottomNavigationView bottomNav;
    private BleService bleService;
    private TourSessionManager tourSessionManager;
    private RealtimeGuideManager realtimeGuideManager;
    private OperatorConfigStore operatorConfigStore;
    private ProvisioningStore provisioningStore;
    private ProvisioningApi provisioningApi;
    private ProvisioningApi.AuthSession operatorSession;
    private DrawerLayout drawerLayout;
    private TextView tvOperatorCurrentVenue;
    private TextView tvOperatorVenueId;
    private TextView tvOperatorDeviceId;
    private TextView tvOperatorPhoneSerial;
    private TextView tvOperatorGlassesId;
    private TextView tvOperatorConfigVersion;
    private TextView tvHeaderStatus;
    private View headerStatusDot;
    private boolean debugPreviewUnlocked;

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
            launchProvisioning();
            return;
        }
        setContentView(R.layout.activity_main);

        bleService = BleService.getInstance();
        bleService.addListener(bleListener);
        tourSessionManager = TourSessionManager.get();
        tourSessionManager.addListener(this);
        realtimeGuideManager = RealtimeGuideManager.get();
        operatorConfigStore = OperatorConfigStore.get(this);
        provisioningApi = ProvisioningApiProvider.get();

        bottomNav = findViewById(R.id.bottom_navigation);
        drawerLayout = findViewById(R.id.drawer_layout);
        tvHeaderStatus = findViewById(R.id.tv_header_status);
        headerStatusDot = findViewById(R.id.header_status_dot);
        bindOperatorConfigDrawer();

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
                // Keep the export tab reachable outside an active tour so staff can verify that
                // the dedicated local album is empty after return. Transfer and end-tour actions
                // remain gated inside ExportFragment by the active session.
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

    private void bindOperatorConfigDrawer() {
        tvOperatorCurrentVenue = findViewById(R.id.tv_operator_current_venue);
        tvOperatorVenueId = findViewById(R.id.tv_operator_venue_id);
        tvOperatorDeviceId = findViewById(R.id.tv_operator_device_id);
        tvOperatorPhoneSerial = findViewById(R.id.tv_operator_phone_serial);
        tvOperatorGlassesId = findViewById(R.id.tv_operator_glasses_id);
        tvOperatorConfigVersion = findViewById(R.id.tv_operator_config_version);

        findViewById(R.id.btn_operator_config).setOnClickListener(view ->
                showOperatorLoginDialog());
        findViewById(R.id.btn_close_operator_config).setOnClickListener(view ->
                drawerLayout.closeDrawer(GravityCompat.START));
        findViewById(R.id.btn_reset_provisioning).setOnClickListener(view ->
                confirmResetProvisioning());
        findViewById(R.id.layout_mock_order).setVisibility(
                BuildConfig.DEBUG ? View.VISIBLE : View.GONE);
        populateOperatorConfig();
    }

    private void populateOperatorConfig() {
        ProvisioningApi.ProvisioningSnapshot snapshot = provisioningStore.snapshot();
        if (snapshot == null) {
            launchProvisioning();
            return;
        }
        tvOperatorCurrentVenue.setText(snapshot.venue.name);
        tvOperatorVenueId.setText(snapshot.venue.id);
        tvOperatorDeviceId.setText(snapshot.deviceId);
        tvOperatorPhoneSerial.setText(snapshot.phoneSerial);
        tvOperatorGlassesId.setText(snapshot.glassesId);
        tvOperatorConfigVersion.setText("v" + snapshot.configVersion);
    }

    private void showOperatorLoginDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_operator_login, null, false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        TextInputEditText username = content.findViewById(R.id.edit_operator_username);
        TextInputEditText password = content.findViewById(R.id.edit_operator_password);
        username.setText(MockProvisioningApi.MOCK_USERNAME);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.operator_login_title)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.operator_login_action, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button action = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            action.setOnClickListener(view -> {
                String usernameValue = textOf(username);
                String passwordValue = textOf(password);
                if (usernameValue.isEmpty() || passwordValue.isEmpty()) {
                    Toast.makeText(this, R.string.provisioning_credentials_required,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                action.setEnabled(false);
                action.setText(R.string.provisioning_verifying);
                provisioningApi.login(usernameValue, passwordValue,
                        new ProvisioningApi.Callback<ProvisioningApi.AuthSession>() {
                            @Override public void onSuccess(ProvisioningApi.AuthSession session) {
                                operatorSession = session;
                                dialog.dismiss();
                                populateOperatorConfig();
                                drawerLayout.openDrawer(GravityCompat.START);
                            }

                            @Override public void onFailure(String message) {
                                action.setEnabled(true);
                                action.setText(R.string.operator_login_action);
                                Toast.makeText(MainActivity.this, message,
                                        Toast.LENGTH_LONG).show();
                            }
                        });
            });
        });
        dialog.show();
    }

    private void confirmResetProvisioning() {
        if (tourSessionManager.isActive()) {
            Toast.makeText(this, R.string.operator_reset_active_tour, Toast.LENGTH_LONG).show();
            return;
        }
        if (operatorSession == null
                || operatorSession.expiresAtEpochMs <= System.currentTimeMillis()) {
            drawerLayout.closeDrawer(GravityCompat.START);
            showOperatorLoginDialog();
            return;
        }
        ProvisioningApi.ProvisioningSnapshot snapshot = provisioningStore.snapshot();
        if (snapshot == null) {
            launchProvisioning();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.operator_reset_title)
                .setMessage(R.string.operator_reset_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.operator_reset_action, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button action = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            action.setTextColor(androidx.core.content.ContextCompat.getColor(
                    this, R.color.qimu_error));
            action.setOnClickListener(view -> {
                action.setEnabled(false);
                action.setText(R.string.operator_resetting);
                provisioningApi.reset(operatorSession.operatorToken, snapshot.deviceId,
                        new ProvisioningApi.Callback<Void>() {
                            @Override public void onSuccess(Void unused) {
                                if (!provisioningStore.clearProvisioning()) {
                                    onFailure(getString(R.string.operator_reset_failed));
                                    return;
                                }
                                operatorConfigStore.restoreDefaults();
                                bleService.disconnect();
                                dialog.dismiss();
                                launchProvisioning();
                            }

                            @Override public void onFailure(String message) {
                                action.setEnabled(true);
                                action.setText(R.string.operator_reset_action);
                                Toast.makeText(MainActivity.this, message,
                                        Toast.LENGTH_LONG).show();
                            }
                        });
            });
        });
        dialog.show();
    }

    private String textOf(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private void launchProvisioning() {
        Intent intent = new Intent(this, ProvisioningActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    /**
     * Keep each tab Fragment alive after its first creation. This preserves the
     * conversation and export state when visitors move between tabs.
     */
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
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bleService != null) bleService.removeListener(bleListener);
        if (tourSessionManager != null) tourSessionManager.removeListener(this);
    }
}
