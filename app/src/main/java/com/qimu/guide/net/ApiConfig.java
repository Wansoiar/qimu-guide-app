package com.qimu.guide.net;

import android.content.Context;
import android.content.SharedPreferences;

/** AI 导览后端端点。Debug 默认通过 adb reverse 联调，Release 默认连接线上服务。 */
public final class ApiConfig {

    private static final String PREFS = "api_config";
    private static final String KEY_HOST = "host";
    private static final String DEV_HOST = "http://127.0.0.1:8787";
    private static final String PROD_HOST = "http://115.190.147.152:8787";

    private static volatile String host = com.qimu.guide.BuildConfig.DEBUG
            ? DEV_HOST : PROD_HOST;
    private static SharedPreferences preferences;

    private ApiConfig() {
    }

    public static void init(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = preferences.getString(KEY_HOST, null);
        if (saved != null && !saved.trim().isEmpty()) host = normalize(saved);
    }

    public static void setHost(String newHost) {
        if (newHost == null || newHost.trim().isEmpty()) return;
        host = normalize(newHost);
        SharedPreferences current = preferences;
        if (current != null) current.edit().putString(KEY_HOST, host).apply();
    }

    public static String baseUrl() {
        return host;
    }

    public static String sessions() {
        return baseUrl() + "/sessions";
    }

    public static String uploadAudio() {
        return baseUrl() + "/v1/upload/audio";
    }

    public static String uploadImage() {
        return baseUrl() + "/v1/upload/image";
    }

    public static String query() {
        return baseUrl() + "/v1/query";
    }

    public static String rtcSession() {
        return baseUrl() + "/v1/rtc/session";
    }

    public static String rtcSessionStop() {
        return baseUrl() + "/v1/rtc/session/stop";
    }

    public static String rtcSessionInject() {
        return baseUrl() + "/v1/rtc/session/inject";
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("/+$", "");
    }
}
