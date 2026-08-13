package com.qimu.guide.provisioning;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.qimu.guide.BuildConfig;
import com.qimu.guide.R;
import com.qimu.guide.config.OperatorConfigStore;
import com.qimu.guide.net.TourSessionManager;
import com.qimu.guide.service.BleService;

public final class OperatorConfigActivity extends AppCompatActivity {

    private ProvisioningStore provisioningStore;
    private OperatorConfigStore operatorConfigStore;
    private OperatorSessionStore operatorSessionStore;
    private ProvisioningApi provisioningApi;
    private BleService bleService;
    private TourSessionManager tourSessionManager;

    private TextView tvOperatorCurrentVenue;
    private TextView tvOperatorVenueId;
    private TextView tvOperatorDeviceId;
    private TextView tvOperatorPhoneSerial;
    private TextView tvOperatorGlassesId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        provisioningStore = ProvisioningStore.get(this);
        if (!provisioningStore.isInitialized()) {
            launchLogin();
            return;
        }
        setContentView(R.layout.activity_operator_config);
        operatorConfigStore = OperatorConfigStore.get(this);
        operatorSessionStore = OperatorSessionStore.get(this);
        provisioningApi = ProvisioningApiProvider.get();
        bleService = BleService.getInstance();
        tourSessionManager = TourSessionManager.get();

        tvOperatorCurrentVenue = findViewById(R.id.tv_operator_current_venue);
        tvOperatorVenueId = findViewById(R.id.tv_operator_venue_id);
        tvOperatorDeviceId = findViewById(R.id.tv_operator_device_id);
        tvOperatorPhoneSerial = findViewById(R.id.tv_operator_phone_serial);
        tvOperatorGlassesId = findViewById(R.id.tv_operator_glasses_id);

        findViewById(R.id.btn_close_operator_config).setOnClickListener(view -> finish());
        findViewById(R.id.btn_reset_device).setOnClickListener(view -> confirmReset());
        findViewById(R.id.layout_mock_order).setVisibility(
                BuildConfig.DEBUG ? View.VISIBLE : View.GONE);

        populate();
    }

    private void populate() {
        ProvisioningApi.ProvisioningSnapshot snapshot = provisioningStore.snapshot();
        if (snapshot == null) {
            launchLogin();
            return;
        }
        tvOperatorCurrentVenue.setText(snapshot.venue.name);
        tvOperatorVenueId.setText(snapshot.venue.id);
        tvOperatorDeviceId.setText(snapshot.deviceId);
        tvOperatorPhoneSerial.setText(snapshot.phoneSerial);
        tvOperatorGlassesId.setText(snapshot.glassesId);
    }

    private void confirmReset() {
        if (tourSessionManager.isActive()) {
            Toast.makeText(this, R.string.operator_reset_active_tour, Toast.LENGTH_LONG).show();
            return;
        }
        String token = validOperatorToken();
        if (token.isEmpty()) {
            showOperatorLoginDialog(this::confirmReset);
            return;
        }
        ProvisioningApi.ProvisioningSnapshot snapshot = provisioningStore.snapshot();
        if (snapshot == null) {
            launchLogin();
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
                provisioningApi.reset(token, snapshot.deviceId,
                        new ProvisioningApi.Callback<Void>() {
                            @Override public void onSuccess(Void unused) {
                                if (!provisioningStore.clearProvisioning()) {
                                    onFailure(getString(R.string.operator_reset_failed));
                                    return;
                                }
                                operatorConfigStore.restoreDefaults();
                                if (bleService != null) bleService.disconnect();
                                dialog.dismiss();
                                launchProvisioning();
                            }

                            @Override public void onFailure(String message) {
                                action.setEnabled(true);
                                action.setText(R.string.operator_reset_action);
                                Toast.makeText(OperatorConfigActivity.this, message,
                                        Toast.LENGTH_LONG).show();
                            }
                        });
            });
        });
        dialog.show();
    }

    private void showOperatorLoginDialog(Runnable onLoggedIn) {
        View content = getLayoutInflater().inflate(R.layout.dialog_operator_login, null, false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        TextInputEditText username = content.findViewById(R.id.edit_operator_username);
        TextInputEditText password = content.findViewById(R.id.edit_operator_password);
        if (ProvisioningApiProvider.isMock()) {
            username.setText(MockProvisioningApi.MOCK_USERNAME);
        }

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
                                operatorSessionStore.save(
                                        session.operatorToken,
                                        session.expiresAtEpochMs,
                                        session.displayName);
                                dialog.dismiss();
                                if (onLoggedIn != null) onLoggedIn.run();
                            }

                            @Override public void onFailure(String message) {
                                action.setEnabled(true);
                                action.setText(R.string.operator_login_action);
                                Toast.makeText(OperatorConfigActivity.this, message,
                                        Toast.LENGTH_LONG).show();
                            }
                        });
            });
        });
        dialog.show();
    }

    private String validOperatorToken() {
        if (operatorSessionStore == null || operatorSessionStore.isExpired()) return "";
        return operatorSessionStore.token();
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

    private void launchLogin() {
        Intent intent = new Intent(this, LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}
