package com.qimu.guide.net;

/** AI 导览线上后端端点。所有构建类型统一直连线上服务。 */
public final class ApiConfig {

    private static final String BASE_URL = "http://115.190.147.152:8787";

    private ApiConfig() {
    }

    public static String baseUrl() {
        return BASE_URL;
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

}
