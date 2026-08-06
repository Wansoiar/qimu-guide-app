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
    /** WebSocket 基址（与 BASE_URL 同主机，http→ws）。 */
    public static final String WS_BASE_URL = "ws://127.0.0.1:8787";

    public static final String UPLOAD_AUDIO = BASE_URL + "/v1/upload/audio";
    public static final String UPLOAD_IMAGE = BASE_URL + "/v1/upload/image";
    public static final String QUERY = BASE_URL + "/v1/query";
    /** 流式语音上行 WS（边采边推 PCM，边收 asr_partial/text_delta/audio_chunk/done）。 */
    public static final String QUERY_STREAM_WS = WS_BASE_URL + "/v1/query/stream";

    // ── 火山 RTC 会话编排（feat/volc-rtc）──────────────────────────────
    /** 创建 RTC 会话：签进房 Token + 调 StartVoiceChat，返回 app_id/room_id/uid/token/task_id。 */
    public static final String RTC_SESSION = BASE_URL + "/v1/rtc/session";
    /** 结束 RTC 会话（退房后调，避免持续计费）。 */
    public static final String RTC_SESSION_STOP = BASE_URL + "/v1/rtc/session/stop";
}
