package com.qimu.guide.net;

import com.qimu.guide.BuildConfig;

/** AI 导览线上后端端点。所有构建类型统一直连线上服务。 */
public final class ApiConfig {

    private ApiConfig() {
    }

    public static String baseUrl() {
        String configured = BuildConfig.API_BASE_URL == null
                ? "" : BuildConfig.API_BASE_URL.trim();
        while (configured.endsWith("/")) {
            configured = configured.substring(0, configured.length() - 1);
        }
        return configured;
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

    public static String shareBundles() {
        return baseUrl() + "/v1/share-bundles";
    }

}
