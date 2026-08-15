package com.qimu.guide.net;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Blocking client for the APP side of the tour share-bundle protocol. */
public final class ShareBundleApiClient implements Closeable {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_ATTEMPTS = 3;
    private static final long[] RETRY_DELAYS_MS = {1000L, 2000L};

    private final String appToken;
    private final String deviceId;
    private final OkHttpClient client;
    private final Set<Call> activeCalls = java.util.Collections.newSetFromMap(
            new ConcurrentHashMap<Call, Boolean>());
    private volatile boolean closed;

    public ShareBundleApiClient(@NonNull String appToken, @NonNull String deviceId) {
        this.appToken = appToken.trim();
        this.deviceId = deviceId.trim().isEmpty() ? "android-unknown" : deviceId.trim();
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .build();
    }

    @NonNull
    public CreateResult createBundle(@NonNull String phone,
                                     int expectedPhotoCount,
                                     boolean needVlog,
                                     @Nullable String venueId) throws IOException {
        try {
            JSONObject payload = new JSONObject();
            payload.put("phone", phone);
            payload.put("expected_photo_count", expectedPhotoCount);
            payload.put("need_vlog", needVlog);
            if (venueId != null && !venueId.trim().isEmpty()) {
                payload.put("venue_id", venueId.trim());
            }
            JSONObject data = execute(() -> authenticated(
                    new Request.Builder().url(ApiConfig.shareBundles()))
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build());
            return new CreateResult(
                    requiredString(data, "bundle_id"),
                    requiredString(data, "share_url"),
                    requiredString(data, "expires_at"));
        } catch (JSONException error) {
            throw new IOException("创建分享包响应解析失败", error);
        }
    }

    @NonNull
    public UploadResult uploadPhoto(@NonNull String bundleId,
                                    @NonNull String fileName,
                                    @NonNull String mimeType,
                                    @NonNull byte[] bytes,
                                    int sortOrder,
                                    @NonNull String sha256) throws IOException {
        return uploadPhoto(bundleId, fileName, mimeType, bytes, sortOrder, sha256, null);
    }

    @NonNull
    public UploadResult uploadPhoto(@NonNull String bundleId,
                                    @NonNull String fileName,
                                    @NonNull String mimeType,
                                    @NonNull byte[] bytes,
                                    int sortOrder,
                                    @NonNull String sha256,
                                    @Nullable String sessionId) throws IOException {
        MultipartBody body = buildPhotoUploadBody(
                fileName, mimeType, bytes, sortOrder, sha256, sessionId);
        JSONObject data = null;
        for (int integrityAttempt = 0; integrityAttempt < MAX_ATTEMPTS; integrityAttempt++) {
            try {
                data = execute(() -> authenticated(new Request.Builder()
                        .url(ApiConfig.shareBundles() + "/" + bundleId + "/photos"))
                        .post(body)
                        .build());
                break;
            } catch (ApiException error) {
                if (error.code != 30018 || integrityAttempt == MAX_ATTEMPTS - 1) throw error;
            }
        }
        if (data == null) throw new IOException("照片上传响应为空");
        return new UploadResult(
                data.optInt("uploaded_count", 0),
                data.optInt("expected_count", 0),
                data.optBoolean("is_duplicate", false));
    }

    @NonNull
    static MultipartBody buildPhotoUploadBody(@NonNull String fileName,
                                              @NonNull String mimeType,
                                              @NonNull byte[] bytes,
                                              int sortOrder,
                                              @NonNull String sha256,
                                              @Nullable String sessionId) {
        MediaType mediaType = MediaType.parse(mimeType);
        if (mediaType == null) mediaType = MediaType.parse("image/jpeg");
        RequestBody fileBody = RequestBody.create(bytes, mediaType);
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", safeFileName(fileName, mimeType), fileBody)
                .addFormDataPart("sort_order", String.valueOf(sortOrder))
                .addFormDataPart("sha256", sha256);
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            builder.addFormDataPart("session_id", sessionId.trim());
        }
        return builder.build();
    }

    @NonNull
    public FinishResult finishBundle(@NonNull String bundleId) throws IOException {
        JSONObject data = execute(() -> authenticated(new Request.Builder()
                .url(ApiConfig.shareBundles() + "/" + bundleId + "/finish"))
                .post(RequestBody.create("", JSON))
                .build());
        try {
            JSONObject vlog = data.optJSONObject("vlog");
            return new FinishResult(
                    requiredString(data, "share_url"),
                    requiredString(data, "expires_at"),
                    data.optInt("photo_count", 0),
                    vlog != null && vlog.optBoolean("enabled", false),
                    vlog == null ? "" : vlog.optString("status", ""));
        } catch (JSONException error) {
            throw new IOException("完成分享响应解析失败", error);
        }
    }

    @NonNull
    private JSONObject execute(@NonNull RequestFactory requestFactory) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            ensureOpen();
            Call call = client.newCall(requestFactory.create());
            activeCalls.add(call);
            try (Response response = call.execute()) {
                String body = response.body() == null ? "" : response.body().string();
                JSONObject envelope = parseEnvelope(body, response.code());
                int code = envelope.optInt("code", response.isSuccessful() ? -1 : response.code());
                if (response.code() >= 500 && attempt < MAX_ATTEMPTS - 1) {
                    lastFailure = new IOException("分享服务暂时不可用（HTTP "
                            + response.code() + "）");
                    waitBeforeRetry(attempt);
                    continue;
                }
                if (!response.isSuccessful() || code != 0) {
                    throw new ApiException(
                            code,
                            envelope.optString("message", "share_request_failed"),
                            envelope.optString("request_id", ""),
                            response.code());
                }
                JSONObject data = envelope.optJSONObject("data");
                if (data == null) throw new IOException("分享服务返回数据为空");
                return data;
            } catch (ApiException error) {
                throw error;
            } catch (IOException error) {
                if (closed || call.isCanceled()) throw new IOException("上传已取消", error);
                lastFailure = error;
                if (attempt >= MAX_ATTEMPTS - 1) break;
                waitBeforeRetry(attempt);
            } finally {
                activeCalls.remove(call);
            }
        }
        throw lastFailure == null ? new IOException("分享请求失败") : lastFailure;
    }

    @NonNull
    private Request.Builder authenticated(@NonNull Request.Builder builder) {
        return builder
                .header("X-App-Token", appToken)
                .header("X-Device-Id", deviceId)
                .header("X-Client-Type", "android");
    }

    private static JSONObject parseEnvelope(String body, int httpCode) throws IOException {
        if (body == null || body.trim().isEmpty()) {
            throw new IOException("分享服务返回空响应（HTTP " + httpCode + "）");
        }
        try {
            return new JSONObject(body);
        } catch (JSONException error) {
            throw new IOException("分享服务响应格式错误（HTTP " + httpCode + "）", error);
        }
    }

    private static String requiredString(JSONObject data, String key) throws JSONException {
        String value = data.getString(key);
        if (value.trim().isEmpty()) throw new JSONException(key + " is empty");
        return value;
    }

    private static String safeFileName(String fileName, String mimeType) {
        String trimmed = fileName.trim();
        int extensionStart = trimmed.lastIndexOf('.');
        String base = extensionStart > 0 ? trimmed.substring(0, extensionStart) : trimmed;
        return (base.isEmpty() ? "photo" : base)
                + ("image/png".equalsIgnoreCase(mimeType) ? ".png" : ".jpg");
    }

    private static void waitBeforeRetry(int attempt) throws IOException {
        try {
            Thread.sleep(RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)]);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("上传已取消", error);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("上传已取消");
    }

    @NonNull
    public static String sha256(@NonNull byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("当前设备不支持 SHA-256", error);
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Call call : activeCalls) call.cancel();
        activeCalls.clear();
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
    }

    private interface RequestFactory {
        Request create();
    }

    public static final class ApiException extends IOException {
        public final int code;
        public final String serviceMessage;
        public final String requestId;
        public final int httpStatus;

        ApiException(int code, String serviceMessage, String requestId, int httpStatus) {
            super(serviceMessage + (requestId.isEmpty() ? "" : "（" + requestId + "）"));
            this.code = code;
            this.serviceMessage = serviceMessage;
            this.requestId = requestId;
            this.httpStatus = httpStatus;
        }
    }

    public static final class CreateResult {
        public final String bundleId;
        public final String shareUrl;
        public final String expiresAt;

        CreateResult(String bundleId, String shareUrl, String expiresAt) {
            this.bundleId = bundleId;
            this.shareUrl = shareUrl;
            this.expiresAt = expiresAt;
        }
    }

    public static final class UploadResult {
        public final int uploadedCount;
        public final int expectedCount;
        public final boolean duplicate;

        UploadResult(int uploadedCount, int expectedCount, boolean duplicate) {
            this.uploadedCount = uploadedCount;
            this.expectedCount = expectedCount;
            this.duplicate = duplicate;
        }
    }

    public static final class FinishResult {
        public final String shareUrl;
        public final String expiresAt;
        public final int photoCount;
        public final boolean vlogEnabled;
        public final String vlogStatus;

        FinishResult(String shareUrl, String expiresAt, int photoCount,
                     boolean vlogEnabled, String vlogStatus) {
            this.shareUrl = shareUrl;
            this.expiresAt = expiresAt;
            this.photoCount = photoCount;
            this.vlogEnabled = vlogEnabled;
            this.vlogStatus = vlogStatus;
        }
    }
}
