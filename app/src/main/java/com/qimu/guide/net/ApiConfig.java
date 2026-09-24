package com.qimu.guide.net;

import com.qimu.guide.BuildConfig;

/** AI 导览后端端点。正式环境固定走 HTTPS 域名，开发环境可显式覆盖。 */
public final class ApiConfig {

    private static final String PRODUCTION_API_BASE_URL = "https://api.equavision.cn";

    private ApiConfig() {
    }

    public static String baseUrl() {
        String configured = BuildConfig.API_BASE_URL == null
                ? "" : BuildConfig.API_BASE_URL.trim();
        while (configured.endsWith("/")) {
            configured = configured.substring(0, configured.length() - 1);
        }
        if (BuildConfig.IS_PRODUCTION_ENV
                && !PRODUCTION_API_BASE_URL.equals(configured)) {
            throw new IllegalStateException(
                    "正式环境只允许通过 " + PRODUCTION_API_BASE_URL + " 访问服务");
        }
        return configured;
    }

    /** 会话：开通一次导览（建 session + 绑设备），返回唯一 session_id。 */
    public static String rentalsStart() {
        return baseUrl() + "/v1/session/start";
    }

    public static String uploadImage() {
        return baseUrl() + "/v1/upload/image";
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

    public static String rtcSessionDescribeImage() {
        return baseUrl() + "/v1/rtc/session/describe-image";
    }

    public static String shareBundles() {
        return baseUrl() + "/v1/share-bundles";
    }

    public static String adminLogin() {
        return baseUrl() + "/v1/admin/auth/login";
    }

    public static String adminVenues() {
        return baseUrl() + "/v1/admin/venues";
    }

    public static String deviceReport() {
        return baseUrl() + "/v1/admin/devices/report";
    }

    public static String deviceReset(String deviceId) {
        return baseUrl() + "/v1/admin/devices/" + deviceId + "/reset";
    }

}
