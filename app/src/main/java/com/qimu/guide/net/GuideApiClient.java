package com.qimu.guide.net;

import android.util.Log;

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
    private boolean closed;

    public interface QueryCallback {
        void onTextDelta(String delta);

        void onAudioChunk(int sequence, String url, int durationMs);

        void onDone(String transcribedText, String fullText, String aigcLabel);

        void onError(String message);
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
                    .url(ApiConfig.UPLOAD_AUDIO)
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
                    .url(ApiConfig.QUERY)
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
