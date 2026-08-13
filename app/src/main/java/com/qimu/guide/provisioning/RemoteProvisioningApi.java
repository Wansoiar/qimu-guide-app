package com.qimu.guide.provisioning;

import android.os.Handler;
import android.os.Looper;

import com.qimu.guide.QimuApplication;
import com.qimu.guide.net.ApiConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 运营后台真实接口的 {@link ProvisioningApi} 实现。
 *
 * 回调统一切回主线程；所有请求使用统一 Envelope（code==0 为成功），需要 Bearer token 的
 * 接口在 401 时清除本地运营登录态，引导重新登录。
 */
public final class RemoteProvisioningApi implements ProvisioningApi {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String FAILED_LOGIN_LOCKED = "登录失败，账号锁定，剩余 %d 秒";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void login(String username, String password, Callback<AuthSession> callback) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("username", username == null ? "" : username.trim());
            payload.put("password", password == null ? "" : password);
            Request request = new Request.Builder()
                    .url(ApiConfig.adminLogin())
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build();
            client.newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    deliverFailure(callback, "登录失败，请检查网络后重试");
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (Response closed = response) {
                        JSONObject json = parseBody(closed);
                        if (!closed.isSuccessful()
                                || (json.has("code") && json.optInt("code", 0) != 0)) {
                            deliverFailure(callback, loginFailureMessage(json));
                            return;
                        }
                        JSONObject data = json.optJSONObject("data");
                        String accessToken = data == null ? "" : data.optString("access_token", "");
                        if (accessToken.trim().isEmpty()) {
                            deliverFailure(callback, "登录响应缺少 access_token");
                            return;
                        }
                        long expiresInSec = data.optLong("expires_in", 0L);
                        JSONObject admin = data.optJSONObject("admin");
                        String displayName = admin == null ? "" : admin.optString("display_name", "");
                        AuthSession session = new AuthSession(
                                accessToken.trim(),
                                displayName,
                                System.currentTimeMillis()
                                        + Math.max(0L, expiresInSec) * 1000L);
                        deliverSuccess(callback, session);
                    } catch (Exception e) {
                        deliverFailure(callback, "登录响应解析失败");
                    }
                }
            });
        } catch (Exception e) {
            deliverFailure(callback, "登录请求无效");
        }
    }

    @Override
    public void listVenues(String operatorToken, Callback<List<Venue>> callback) {
        try {
            Request request = new Request.Builder()
                    .url(ApiConfig.adminVenues())
                    .header("Authorization", bearer(operatorToken))
                    .get()
                    .build();
            client.newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    deliverFailure(callback, "场馆列表加载失败，请检查网络后重试");
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (Response closed = response) {
                        JSONObject json = parseBody(closed);
                        if (!closed.isSuccessful()
                                || (json.has("code") && json.optInt("code", 0) != 0)) {
                            deliverFailure(callback, failureMessage(json, closed.code()));
                            return;
                        }
                        List<Venue> venues = new ArrayList<>();
                        JSONObject data = json.optJSONObject("data");
                        JSONArray items = data == null ? null : data.optJSONArray("items");
                        if (items != null) {
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.optJSONObject(i);
                                if (item == null) continue;
                                // APP 只展示启用中的场馆。
                                if (!"active".equalsIgnoreCase(item.optString("status", ""))) {
                                    continue;
                                }
                                venues.add(new Venue(
                                        item.optString("id", ""),
                                        item.optString("code", ""),
                                        item.optString("name", ""),
                                        item.optString("address", "")));
                            }
                        }
                        deliverSuccess(callback, venues);
                    } catch (Exception e) {
                        deliverFailure(callback, "场馆列表响应解析失败");
                    }
                }
            });
        } catch (Exception e) {
            deliverFailure(callback, "场馆列表请求无效");
        }
    }

    @Override
    public void initialize(String operatorToken, DeviceReportRequest request,
                           Callback<ProvisioningSnapshot> callback) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("phone_sn", request.phoneSerial);
            payload.put("model", request.phoneModel);
            payload.put("os_version", request.osVersion);
            payload.put("app_version", request.appVersion);
            payload.put("glasses_mac", request.glassesId);
            payload.put("glasses_name", request.glassesName);
            payload.put("venue_id", request.venue == null ? "" : request.venue.id);
            Request httpRequest = new Request.Builder()
                    .url(ApiConfig.deviceReport())
                    .header("Authorization", bearer(operatorToken))
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build();
            client.newCall(httpRequest).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    deliverFailure(callback, "设备上报失败，请检查网络后重试");
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (Response closed = response) {
                        JSONObject json = parseBody(closed);
                        if (!closed.isSuccessful()
                                || (json.has("code") && json.optInt("code", 0) != 0)) {
                            deliverFailure(callback, failureMessage(json, closed.code()));
                            return;
                        }
                        JSONObject data = json.optJSONObject("data");
                        if (data == null) {
                            deliverFailure(callback, "设备上报响应缺少 data");
                            return;
                        }
                        String deviceId = data.optString("device_id", "").trim();
                        if (deviceId.isEmpty()) {
                            deliverFailure(callback, "设备上报响应缺少 device_id");
                            return;
                        }
                        JSONObject venueJson = data.optJSONObject("venue");
                        Venue venue = new Venue(
                                venueJson == null ? "" : venueJson.optString("id", ""),
                                venueJson == null ? "" : venueJson.optString("code", ""),
                                venueJson == null ? "" : venueJson.optString("name", ""),
                                venueJson == null ? "" : venueJson.optString("address", ""));
                        JSONObject glassesJson = data.optJSONObject("glasses");
                        String glassesId = glassesJson == null
                                ? "" : glassesJson.optString("device_id", "");
                        String glassesName = glassesJson == null
                                ? "" : glassesJson.optString("name", "");
                        long provisionedAt = parseIso8601(data.optString("provisioned_at", ""));
                        deliverSuccess(callback, new ProvisioningSnapshot(
                                deviceId,
                                data.optString("phone_sn", ""),
                                glassesId,
                                glassesName,
                                venue,
                                provisionedAt));
                    } catch (Exception e) {
                        deliverFailure(callback, "设备上报响应解析失败");
                    }
                }
            });
        } catch (Exception e) {
            deliverFailure(callback, "设备上报请求无效");
        }
    }

    @Override
    public void reset(String operatorToken, String deviceId, Callback<Void> callback) {
        try {
            Request httpRequest = new Request.Builder()
                    .url(ApiConfig.deviceReset(deviceId))
                    .header("Authorization", bearer(operatorToken))
                    .post(RequestBody.create("{}", JSON))
                    .build();
            client.newCall(httpRequest).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    deliverFailure(callback, "重置失败，请检查网络后重试");
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (Response closed = response) {
                        JSONObject json = parseBody(closed);
                        if (!closed.isSuccessful()
                                || (json.has("code") && json.optInt("code", 0) != 0)) {
                            deliverFailure(callback, failureMessage(json, closed.code()));
                            return;
                        }
                        deliverSuccess(callback, null);
                    } catch (Exception e) {
                        deliverFailure(callback, "重置响应解析失败");
                    }
                }
            });
        } catch (Exception e) {
            deliverFailure(callback, "重置请求无效");
        }
    }

    private String loginFailureMessage(JSONObject json) {
        JSONObject data = json.optJSONObject("data");
        long lockedSeconds = data == null ? -1L : data.optLong("locked_seconds", -1L);
        if (lockedSeconds > 0L) {
            return String.format(Locale.ROOT, FAILED_LOGIN_LOCKED, lockedSeconds);
        }
        String message = envelopeMessage(json);
        if (!message.isEmpty()) return message;
        JSONObject error = json.optJSONObject("error");
        message = error == null ? "" : error.optString("message", "");
        return message.isEmpty() ? "运营账号或密码错误" : message;
    }

    private String failureMessage(JSONObject json, int httpCode) {
        if (httpCode == 401) {
            OperatorSessionStore.get(QimuApplication.getAppContext()).clear();
            if (json.optInt("code", 0) == 10207) return "运营账号不可用，请联系管理员";
            return "登录已过期，请重新登录";
        }
        String message = envelopeMessage(json);
        if (!message.isEmpty()) return message;
        JSONObject error = json.optJSONObject("error");
        message = error == null ? "" : error.optString("message", "");
        return message.isEmpty() ? "请求失败，请稍后重试" : message;
    }

    private String envelopeMessage(JSONObject json) {
        String message = json.optString("message", "");
        if (!message.isEmpty()) return message;
        JSONObject error = json.optJSONObject("error");
        return error == null ? "" : error.optString("message", "");
    }

    private JSONObject parseBody(Response response) {
        try {
            String body = response.body() == null ? "" : response.body().string();
            return body.isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private long parseIso8601(String value) {
        if (value == null || value.trim().isEmpty()) return System.currentTimeMillis();
        String normalized = value.trim().replace("Z", "+0000");
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ"};
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                return format.parse(normalized).getTime();
            } catch (ParseException ignored) {
                // 尝试下一种格式。
            }
        }
        return System.currentTimeMillis();
    }

    private String bearer(String token) {
        return token == null ? "Bearer " : "Bearer " + token.trim();
    }

    private <T> void deliverSuccess(Callback<T> callback, T value) {
        mainHandler.post(() -> callback.onSuccess(value));
    }

    private void deliverFailure(Callback<?> callback, String message) {
        mainHandler.post(() -> callback.onFailure(message));
    }
}
