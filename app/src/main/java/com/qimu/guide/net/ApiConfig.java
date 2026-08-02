package com.qimu.guide.net;

/**
 * 后端 ai-guided 服务的接入配置。
 *
 * 联调方式（阶段 2）：安卓手机 USB 直连电脑，执行
 *   adb reverse tcp:8787 tcp:8787
 * 后，手机上的 127.0.0.1:8787 会被反向转发到电脑本机后端，无需路由器/局域网 IP。
 * 明文 http 已在 network_security_config.xml 放开（cleartextTrafficPermitted=true）。
 *
 * 上云后把 BASE_URL 换成公网域名即可（后续可做成可配置项）。
 */
public final class ApiConfig {

    private ApiConfig() {}

    /** 后端基址（adb reverse 场景走本机回环）。 */
    public static final String BASE_URL = "http://127.0.0.1:8787";

    public static final String UPLOAD_AUDIO = BASE_URL + "/v1/upload/audio";
    public static final String UPLOAD_IMAGE = BASE_URL + "/v1/upload/image";
    public static final String QUERY = BASE_URL + "/v1/query";
}
