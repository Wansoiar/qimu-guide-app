package com.qimu.guide.net;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Minimal client for the PRD's create tour-session (rental start) endpoint. */
public final class TourSessionApiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final TourSessionApiClient INSTANCE = new TourSessionApiClient();

    public interface CreateCallback {
        void onSuccess(String sessionId);
        void onError(String message, boolean transportUnavailable);
    }

    public static TourSessionApiClient get() {
        return INSTANCE;
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    private TourSessionApiClient() {
    }

    /**
     * 开通一次导览（POST /v1/session/start）：后端建 session 并绑设备，返回唯一 session_id。
     *
     * 设备标识取初始化后端落盘的值（glassesId=眼镜 MAC，phoneDeviceId=手机 device_id）；
     * 游客手机号 phoneNumber 作为本次租借的游客标识，后端落到 session.user_id。
     * 全链路只有一个 session_id：后续 RealtimeGuideManager 会带着它去调 /v1/rtc/session，
     * 由后端把两步映射到 session 表同一条数据。
     */
    public void startRental(String venueId, String glassesId, String phoneDeviceId,
                            String phoneNumber, CreateCallback callback) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("venue_id", venueId);
            if (glassesId != null && !glassesId.trim().isEmpty()) {
                payload.put("device_glasses_id", glassesId.trim());
            }
            if (phoneDeviceId != null && !phoneDeviceId.trim().isEmpty()) {
                payload.put("device_phone_id", phoneDeviceId.trim());
            }
            if (phoneNumber != null && !phoneNumber.trim().isEmpty()) {
                payload.put("phone_number", phoneNumber.trim());
            }
            Request request = new Request.Builder()
                    .url(ApiConfig.rentalsStart())
                    .header("X-Client-Type", "android")
                    .header("X-Staff-Pin", "0000")
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build();
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    callback.onError("创建会话失败，请检查导览服务后重试", true);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (Response closedResponse = response) {
                        String body = closedResponse.body() == null
                                ? "" : closedResponse.body().string();
                        JSONObject json = body.isEmpty() ? new JSONObject() : new JSONObject(body);
                        String message = responseMessage(json);
                        if (!closedResponse.isSuccessful()
                                || (json.has("code") && json.optInt("code", 0) != 0)) {
                            callback.onError(message.isEmpty()
                                            ? "创建会话失败，请重试" : message,
                                    closedResponse.code() == 404);
                            return;
                        }

                        JSONObject data = json.optJSONObject("data");
                        String sessionId = data == null ? "" : data.optString("session_id", "");
                        if (sessionId.trim().isEmpty()) {
                            callback.onError("会话服务未返回 session_id", false);
                        } else {
                            callback.onSuccess(sessionId.trim());
                        }
                    } catch (Exception e) {
                        callback.onError("会话响应解析失败", false);
                    }
                }
            });
        } catch (Exception e) {
            callback.onError("创建会话请求无效", false);
        }
    }

    private String responseMessage(JSONObject json) {
        String message = json.optString("message", "");
        if (!message.isEmpty()) return message;
        JSONObject error = json.optJSONObject("error");
        return error == null ? "" : error.optString("message", "");
    }
}
