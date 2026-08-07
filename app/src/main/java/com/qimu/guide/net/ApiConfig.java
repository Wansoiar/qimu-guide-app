package com.qimu.guide.net;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 后端 ai-guided 服务的接入配置（2026-08-07 改为运行时可切换环境）。
 *
 * host 存 SharedPreferences，可在 App 内（设备页）填写切换：
 *   本地联调：http://127.0.0.1:8787（USB + adb reverse tcp:8787）
 *   线上：http://115.190.147.152:8787（或域名）
 * 首次默认值按 BuildConfig：debug=本地回环，release=线上公网。
 * 明文 http 已在 network_security_config.xml 放开。
 *
 * 用法：Application/启动时调 ApiConfig.init(context) 一次；设备页调 setHost() 切换。
 */
public final class ApiConfig {

    private ApiConfig() {}

    private static final String PREFS = "api_config";
    private static final String KEY_HOST = "host";

    private static final String DEV_HOST = "http://127.0.0.1:8787";
    private static final String PROD_HOST = "http://115.190.147.152:8787";

    private static SharedPreferences sp;
    // 内存缓存当前 host（init 后填充；未 init 时回落默认）
    private static volatile String host = com.qimu.guide.BuildConfig.DEBUG ? DEV_HOST : PROD_HOST;

    /** 启动时初始化：从 SharedPreferences 读上次设置的 host（没有则用默认）。 */
    public static void init(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = sp.getString(KEY_HOST, null);
        if (saved != null && !saved.isEmpty()) host = saved;
    }

    /** 运行时切换 host（设备页调用）。传如 "http://115.190.147.152:8787"。 */
    public static void setHost(String newHost) {
        if (newHost == null || newHost.trim().isEmpty()) return;
        host = newHost.trim().replaceAll("/+$", "");  // 去尾部斜杠
        if (sp != null) sp.edit().putString(KEY_HOST, host).apply();
    }

    /** 当前后端基址。 */
    public static String baseUrl() { return host; }
    /** WebSocket 基址（http→ws / https→wss）。 */
    public static String wsBaseUrl() { return host.replaceFirst("^http", "ws"); }

    public static String uploadAudio() { return baseUrl() + "/v1/upload/audio"; }
    public static String uploadImage() { return baseUrl() + "/v1/upload/image"; }
    public static String query() { return baseUrl() + "/v1/query"; }
    /** 流式语音上行 WS。 */
    public static String queryStreamWs() { return wsBaseUrl() + "/v1/query/stream"; }

    // ── 火山 RTC 会话编排 ──────────────────────────────
    public static String rtcSession() { return baseUrl() + "/v1/rtc/session"; }
    public static String rtcSessionStop() { return baseUrl() + "/v1/rtc/session/stop"; }
}
