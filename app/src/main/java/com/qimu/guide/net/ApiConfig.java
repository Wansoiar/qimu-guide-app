package com.qimu.guide.net;

/** AI 导览后端端点。真机联调前执行 adb reverse tcp:8787 tcp:8787。 */
public final class ApiConfig {

    private ApiConfig() {
    }

    public static final String BASE_URL = "http://127.0.0.1:8787";
    public static final String SESSIONS = BASE_URL + "/sessions";
    public static final String UPLOAD_AUDIO = BASE_URL + "/v1/upload/audio";
    public static final String UPLOAD_IMAGE = BASE_URL + "/v1/upload/image";
    public static final String QUERY = BASE_URL + "/v1/query";
    public static final String RTC_SESSION = BASE_URL + "/v1/rtc/session";
    public static final String RTC_SESSION_STOP = BASE_URL + "/v1/rtc/session/stop";
    public static final String RTC_SESSION_INJECT = BASE_URL + "/v1/rtc/session/inject";
}
