package com.qimu.guide.provisioning;

import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Local development implementation used until the provisioning endpoints exist. */
public final class MockProvisioningApi implements ProvisioningApi {

    public static final String MOCK_USERNAME = "operator";
    public static final String MOCK_PASSWORD = "123456";
    private static final long MOCK_DELAY_MS = 350L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void login(String username, String password, Callback<AuthSession> callback) {
        deliver(() -> {
            if (!MOCK_USERNAME.equals(username == null ? "" : username.trim())
                    || !MOCK_PASSWORD.equals(password)) {
                callback.onFailure("运营账号或密码错误");
                return;
            }
            callback.onSuccess(new AuthSession(
                    "mock_operator_" + UUID.randomUUID(),
                    "Mock 运营人员",
                    System.currentTimeMillis() + 15 * 60 * 1000L));
        });
    }

    @Override
    public void resolvePhoneSerial(String operatorToken, String phoneSerial,
                                   Callback<PhoneIdentity> callback) {
        deliver(() -> {
            if (!isValidToken(operatorToken)) {
                callback.onFailure("运营登录已失效，请重新登录");
                return;
            }
            if (!isValidPhoneSerial(phoneSerial)) {
                callback.onFailure("手机 SN 格式不正确");
                return;
            }
            String normalizedSerial = normalizePhoneSerial(phoneSerial);
            callback.onSuccess(new PhoneIdentity(
                    normalizedSerial,
                    stableDeviceIdFromSerial(normalizedSerial),
                    false,
                    null));
        });
    }

    @Override
    public void listVenues(String operatorToken, Callback<List<Venue>> callback) {
        deliver(() -> {
            if (!isValidToken(operatorToken)) {
                callback.onFailure("运营登录已失效，请重新登录");
                return;
            }
            callback.onSuccess(Arrays.asList(
                    new Venue("61f1f93d-fe42-49d0-b392-bcbf9cd1c13d",
                            "NAMOC", "中国美术馆", "北京市东城区五四大街 1 号"),
                    new Venue("8c8c4b78-8888-4f68-9c88-888888888888",
                            "QIMU-DEMO", "齐目演示馆", "Mock 场馆，仅用于 APP 联调")
            ));
        });
    }

    @Override
    public void initialize(String operatorToken, InitializeRequest request,
                           Callback<ProvisioningSnapshot> callback) {
        deliver(() -> {
            if (!isValidToken(operatorToken)) {
                callback.onFailure("运营登录已失效，请重新登录");
                return;
            }
            if (request == null || request.venue == null
                    || request.installId == null || request.installId.trim().isEmpty()
                    || !isValidPhoneSerial(request.phoneSerial)
                    || request.glassesId == null || request.glassesId.trim().isEmpty()) {
                callback.onFailure("初始化信息不完整");
                return;
            }
            String normalizedMac = normalizeMac(request.glassesId);
            String phoneSerial = normalizePhoneSerial(request.phoneSerial);
            String serverDeviceId = stableDeviceIdFromSerial(phoneSerial);
            callback.onSuccess(new ProvisioningSnapshot(
                    request.installId,
                    serverDeviceId,
                    "mock_device_" + UUID.randomUUID(),
                    phoneSerial,
                    normalizedMac,
                    request.glassesName,
                    request.venue,
                    1L,
                    System.currentTimeMillis()));
        });
    }

    @Override
    public void reset(String operatorToken, String deviceId, Callback<Void> callback) {
        deliver(() -> {
            if (!isValidToken(operatorToken)) {
                callback.onFailure("运营登录已失效，请重新登录");
                return;
            }
            if (deviceId == null || deviceId.trim().isEmpty()) {
                callback.onFailure("设备尚未初始化");
                return;
            }
            callback.onSuccess(null);
        });
    }

    public static String normalizeMac(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public static String normalizePhoneSerial(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isValidPhoneSerial(String value) {
        return normalizePhoneSerial(value).matches("[A-Z0-9][A-Z0-9._-]{3,63}");
    }

    /** Mirrors the backend rule: a manually verified phone SN owns one stable device ID. */
    static String stableDeviceIdFromSerial(String phoneSerial) {
        String stableSeed = "qimu-phone-serial:v1:" + normalizePhoneSerial(phoneSerial);
        return "dev_" + UUID.nameUUIDFromBytes(
                stableSeed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private boolean isValidToken(String token) {
        return token != null && token.startsWith("mock_operator_");
    }

    private void deliver(Runnable runnable) {
        mainHandler.postDelayed(runnable, MOCK_DELAY_MS);
    }
}
