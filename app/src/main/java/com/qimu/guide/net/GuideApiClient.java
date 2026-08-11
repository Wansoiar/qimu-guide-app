package com.qimu.guide.net;

import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import okhttp3.ResponseBody;

/** 语音上传与 AI 问答 SSE 客户端。 */
public class GuideApiClient {

    private static final String TAG = "GuideApiClient";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    private final Set<Call> activeCalls = java.util.Collections.newSetFromMap(
            new ConcurrentHashMap<Call, Boolean>());
    private final Object callLock = new Object();
    private final Object visionCallLock = new Object();
    private Call activeVisionCall;
    private boolean visionCallsCancelled;
    private boolean closed;

    public interface QueryCallback {
        void onTextDelta(String delta);

        void onAudioChunk(int sequence, String url, int durationMs);

        void onDone(String transcribedText, String fullText, String aigcLabel);

        void onError(String message);
    }

    public static final class UploadedImage {
        public final String fileId;
        public final String url;

        UploadedImage(String fileId, String url) {
            this.fileId = fileId;
            this.url = url;
        }
    }

    /** 后端创建 RTC 房间并启动 VoiceChat Agent 后返回的进房信息。 */
    public static final class RtcSessionInfo {
        public final String sessionId;
        public final String appId;
        public final String roomId;
        public final String uid;
        public final String token;
        public final String taskId;
        public final String botUid;
        public final boolean photoEnabled;
        public final boolean mocked;

        RtcSessionInfo(String sessionId, String appId, String roomId, String uid,
                       String token, String taskId, String botUid,
                       boolean photoEnabled, boolean mocked) {
            this.sessionId = sessionId;
            this.appId = appId;
            this.roomId = roomId;
            this.uid = uid;
            this.token = token;
            this.taskId = taskId;
            this.botUid = botUid;
            this.photoEnabled = photoEnabled;
            this.mocked = mocked;
        }
    }

    /** 上传整段 WAV，成功返回 audio_id；失败返回 null。 */
    public String uploadAudio(File wavFile) {
        Call call = null;
        try {
            RequestBody fileBody = RequestBody.create(wavFile, MediaType.parse("audio/wav"));
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", wavFile.getName(), fileBody)
                    .build();
            Request request = new Request.Builder()
                    .url(ApiConfig.uploadAudio())
                    .header("X-Client-Type", "android")
                    .post(body)
                    .build();
            call = client.newCall(request);
            if (!register(call)) return null;
            try (Response response = call.execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(responseBody);
                if (json.optInt("code", -1) != 0) {
                    Log.e(TAG, "uploadAudio 后端错误: " + json.optString("message"));
                    return null;
                }
                String audioId = json.getJSONObject("data").optString("audio_id", "");
                return audioId.isEmpty() ? null : audioId;
            }
        } catch (Exception e) {
            if (call != null && call.isCanceled()) return null;
            Log.e(TAG, "uploadAudio 异常", e);
            return null;
        } finally {
            unregister(call);
        }
    }

    /** 上传眼镜照片，返回供 RTC Agent 访问的图片 URL。阻塞调用。 */
    public UploadedImage uploadImage(File imageFile) {
        Call call = null;
        try {
            RequestBody fileBody = RequestBody.create(imageFile, MediaType.parse("image/jpeg"));
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", imageFile.getName(), fileBody)
                    .build();
            Request request = new Request.Builder()
                    .url(ApiConfig.uploadImage())
                    .header("X-Client-Type", "android")
                    .post(body)
                    .build();
            call = client.newCall(request);
            if (!register(call) || !registerVisionCall(call)) return null;
            try (Response response = call.execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(responseBody);
                if (!response.isSuccessful() || json.optInt("code", -1) != 0) {
                    Log.e(TAG, "uploadImage 后端错误: " + json.optString("message"));
                    return null;
                }
                JSONObject data = json.getJSONObject("data");
                String fileId = data.optString("file_id", "");
                String url = data.optString("url", "");
                return fileId.isEmpty() || url.isEmpty() ? null : new UploadedImage(fileId, url);
            }
        } catch (Exception e) {
            if (call != null && call.isCanceled()) return null;
            Log.e(TAG, "uploadImage 异常", e);
            return null;
        } finally {
            unregisterVisionCall(call);
            unregister(call);
        }
    }

    /** 发起语音查询并流式派发 text_delta、audio_chunk、done。 */
    public void queryVoice(String audioId, QueryCallback callback) {
        SessionContext context = SessionContext.get();
        Call call = null;
        try {
            JSONObject body = new JSONObject();
            body.put("venue_id", context.venueId());
            body.put("session_id", context.sessionId());
            body.put("client_query_id", context.nextClientQueryId());
            body.put("query_type", "voice");
            body.put("audio_id", audioId);
            body.put("language", "zh");

            Request request = new Request.Builder()
                    .url(ApiConfig.query())
                    .header("X-Client-Type", "android")
                    .header("Accept", "text/event-stream")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            call = client.newCall(request);
            if (!register(call)) return;
            try (Response response = call.execute()) {
                if (call.isCanceled()) return;
                if (!response.isSuccessful() || response.body() == null) {
                    callback.onError("query HTTP " + response.code());
                    return;
                }
                boolean terminalFrameReceived = parseSse(response.body(), callback);
                if (!terminalFrameReceived && !call.isCanceled()) {
                    callback.onError("流式响应提前结束");
                }
            }
        } catch (Exception e) {
            if (call != null && call.isCanceled()) return;
            Log.e(TAG, "queryVoice 异常", e);
            callback.onError("query 异常: " + e.getMessage());
        } finally {
            unregister(call);
        }
    }

    /** 创建 RTC 会话；当前后端会同时启动 VoiceChat Agent。阻塞调用。 */
    public RtcSessionInfo createRtcSession(@Nullable String venueId) {
        Call call = null;
        try {
            JSONObject body = new JSONObject();
            if (venueId != null && !venueId.trim().isEmpty()) {
                body.put("venue_id", venueId.trim());
            }
            Request request = new Request.Builder()
                    .url(ApiConfig.rtcSession())
                    .header("X-Client-Type", "android")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            call = client.newCall(request);
            if (!register(call)) return null;
            try (Response response = call.execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(responseBody);
                if (!response.isSuccessful() || json.optInt("code", -1) != 0) {
                    Log.e(TAG, "createRtcSession 后端错误 HTTP " + response.code()
                            + ": " + responseBody);
                    return null;
                }
                JSONObject data = json.getJSONObject("data");
                JSONObject settings = data.optJSONObject("settings");
                return new RtcSessionInfo(
                        data.optString("session_id", ""),
                        data.optString("app_id", ""),
                        data.optString("room_id", ""),
                        data.optString("uid", ""),
                        data.optString("token", ""),
                        data.optString("task_id", ""),
                        data.optString("bot_uid", ""),
                        settings == null || settings.optBoolean("photo_enabled", true),
                        data.optBoolean("mocked", false));
            }
        } catch (Exception e) {
            if (call != null && call.isCanceled()) return null;
            Log.e(TAG, "createRtcSession 异常", e);
            return null;
        } finally {
            unregister(call);
        }
    }

    /** 停止后端 VoiceChat Agent，避免结束游览后继续占用。阻塞调用。 */
    public boolean stopRtcSession(String roomId, String taskId) {
        Call call = null;
        try {
            JSONObject body = new JSONObject();
            body.put("room_id", roomId);
            body.put("task_id", taskId);
            Request request = new Request.Builder()
                    .url(ApiConfig.rtcSessionStop())
                    .header("X-Client-Type", "android")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            call = client.newCall(request);
            if (!register(call)) return false;
            try (Response response = call.execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(responseBody);
                if (!response.isSuccessful() || json.optInt("code", -1) != 0) {
                    Log.e(TAG, "stopRtcSession 后端错误: " + json.optString("message"));
                    return false;
                }
                JSONObject data = json.optJSONObject("data");
                return data == null || data.optBoolean("stopped", true);
            }
        } catch (Exception e) {
            if (call != null && call.isCanceled()) return false;
            Log.e(TAG, "stopRtcSession 异常", e);
            return false;
        } finally {
            unregister(call);
        }
    }

    /** 为一次“拍照→上传→注入”事务打开专用取消域。 */
    public void beginVisionCalls() {
        synchronized (visionCallLock) {
            if (activeVisionCall != null) activeVisionCall.cancel();
            activeVisionCall = null;
            visionCallsCancelled = false;
        }
    }

    /** 只取消识图相关 HTTP，不关闭 RTC create/stop 所共用的客户端。 */
    public void cancelVisionCalls() {
        synchronized (visionCallLock) {
            visionCallsCancelled = true;
            if (activeVisionCall != null) activeVisionCall.cancel();
        }
    }

    private boolean registerVisionCall(Call call) {
        synchronized (visionCallLock) {
            if (visionCallsCancelled) {
                call.cancel();
                return false;
            }
            activeVisionCall = call;
            return true;
        }
    }

    private void unregisterVisionCall(@Nullable Call call) {
        if (call == null) return;
        synchronized (visionCallLock) {
            if (activeVisionCall == call) activeVisionCall = null;
        }
    }

    /** 把图片 URL 作为一条外部用户消息注入当前 RTC Agent。阻塞调用。 */
    public boolean injectRtcMessage(String roomId, String taskId, String message) {
        Call call = null;
        try {
            JSONObject body = new JSONObject();
            body.put("room_id", roomId);
            body.put("task_id", taskId);
            body.put("message", message);
            body.put("interrupt_mode", 1);
            Request request = new Request.Builder()
                    .url(ApiConfig.rtcSessionInject())
                    .header("X-Client-Type", "android")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            call = client.newCall(request);
            if (!register(call) || !registerVisionCall(call)) return false;
            try (Response response = call.execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(responseBody);
                if (!response.isSuccessful() || json.optInt("code", -1) != 0) {
                    Log.e(TAG, "injectRtcMessage 后端错误: " + json.optString("message"));
                    return false;
                }
                return true;
            }
        } catch (Exception e) {
            if (call != null && call.isCanceled()) return false;
            Log.e(TAG, "injectRtcMessage 异常", e);
            return false;
        } finally {
            unregisterVisionCall(call);
            unregister(call);
        }
    }

    /**
     * Cancels upload/SSE calls owned by the current UI lifecycle and prevents a racing
     * background task from registering a new call after the view has been destroyed.
     * A client is intentionally single-lifecycle; create a new instance for a new view.
     */
    public void cancelAll() {
        synchronized (callLock) {
            closed = true;
            for (Call call : activeCalls) {
                call.cancel();
            }
            activeCalls.clear();
        }
    }

    private boolean register(Call call) {
        synchronized (callLock) {
            if (closed) {
                call.cancel();
                return false;
            }
            activeCalls.add(call);
            return true;
        }
    }

    private void unregister(Call call) {
        if (call == null) return;
        synchronized (callLock) {
            activeCalls.remove(call);
        }
    }

    private boolean parseSse(ResponseBody body, QueryCallback callback) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                body.byteStream(), StandardCharsets.UTF_8));
        String line;
        String event = null;
        StringBuilder data = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (event != null && data.length() > 0) {
                    if (dispatchFrame(event, data.toString(), callback)) return true;
                }
                event = null;
                data.setLength(0);
            } else if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring("data:".length()).trim());
            }
        }
        if (event != null && data.length() > 0) {
            return dispatchFrame(event, data.toString(), callback);
        }
        return false;
    }

    /** Returns true for a terminal done/error frame. */
    private boolean dispatchFrame(String event, String dataJson, QueryCallback callback) {
        try {
            JSONObject data = new JSONObject(dataJson);
            switch (event) {
                case "text_delta":
                    callback.onTextDelta(data.optString("delta", ""));
                    return false;
                case "audio_chunk":
                    callback.onAudioChunk(
                            data.optInt("sequence", 0),
                            data.optString("url", ""),
                            data.optInt("duration_ms", 0));
                    return false;
                case "done":
                    callback.onDone(
                            data.optString("transcribed_text", ""),
                            data.optString("full_text", ""),
                            data.optString("aigc_label", ""));
                    return true;
                case "error":
                    callback.onError(data.optString("message", "后端 error 帧"));
                    return true;
                default:
                    return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "解析 SSE 帧失败 event=" + event, e);
            callback.onError("响应解析失败");
            return true;
        }
    }
}
