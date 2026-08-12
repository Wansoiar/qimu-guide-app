package com.qimu.guide.provisioning;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.qimu.guide.BuildConfig;
import com.qimu.guide.MainActivity;
import com.qimu.guide.R;

/**
 * App 启动入口。设备未初始化时展示运营登录页，登录成功后将 token 加密保存并进入初始化向导；
 * 已初始化的设备直接进入使用页。
 */
public final class LoginActivity extends AppCompatActivity {

    private ProvisioningStore provisioningStore;
    private OperatorSessionStore operatorSessionStore;
    private ProvisioningApi provisioningApi;
    private TextInputEditText usernameInput;
    private TextInputEditText passwordInput;
    private MaterialButton loginButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        provisioningStore = ProvisioningStore.get(this);
        if (provisioningStore.isInitialized()) {
            launchMain();
            return;
        }
        operatorSessionStore = OperatorSessionStore.get(this);
        // 已登录且未初始化（如重置后中途退出 App）→ 免登录直接进初始化向导，复用登录态。
        if (!operatorSessionStore.isExpired()) {
            launchProvisioning();
            return;
        }
        setContentView(R.layout.activity_login);
        provisioningApi = ProvisioningApiProvider.get();
        bindViews();
    }

    private void bindViews() {
        usernameInput = findViewById(R.id.edit_login_username);
        passwordInput = findViewById(R.id.edit_login_password);
        loginButton = findViewById(R.id.btn_login);
        TextView mockHint = findViewById(R.id.tv_login_mock_credentials);
        TextView version = findViewById(R.id.tv_login_version);

        if (ProvisioningApiProvider.isMock()) {
            usernameInput.setText(MockProvisioningApi.MOCK_USERNAME);
            mockHint.setVisibility(View.VISIBLE);
        }
        version.setText(getString(R.string.login_version, BuildConfig.VERSION_NAME));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            findViewById(R.id.edit_login_username)
                    .setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        loginButton.setOnClickListener(view -> login());
    }

    private void login() {
        String username = textOf(usernameInput);
        String password = textOf(passwordInput);
        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.provisioning_credentials_required,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        loginButton.setEnabled(false);
        loginButton.setText(R.string.provisioning_verifying);
        provisioningApi.login(username, password,
                new ProvisioningApi.Callback<ProvisioningApi.AuthSession>() {
                    @Override
                    public void onSuccess(ProvisioningApi.AuthSession session) {
                        operatorSessionStore.save(
                                session.operatorToken,
                                session.expiresAtEpochMs,
                                session.displayName);
                        launchProvisioning();
                    }

                    @Override
                    public void onFailure(String message) {
                        loginButton.setEnabled(true);
                        loginButton.setText(R.string.login_action);
                        Toast.makeText(LoginActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private String textOf(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private void launchMain() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void launchProvisioning() {
        Intent intent = new Intent(this, ProvisioningActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}
