package com.qimu.guide.net;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 后端 ai-guided 客户端。
 *
 * 提供两步：
 *   1) uploadAudio(wav) → audio_id      （POST /v1/upload/audio，multipart 字段 file）
 *   2) queryVoice(audio_id, callback)   （POST /v1/query，SSE 逐帧回调）
 *
 * SSE 帧序（后端实测）：start → (text_delta / audio_chunk 交错)* → done，失败发 error。
 * 逐帧解析设计成流式友好：text_delta 可实时上屏，audio_chunk 可边到边下载串播。
 */
public class GuideApiClient {

    private static final String TAG = "GuideApiClient";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)   // SSE 长连接，读超时放宽
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    /** SSE 逐帧回调。所有回调在后台线程触发，UI 操作需自行切主线程。 */
    public interface QueryCallback {
        /** LLM 逐字增量（拼接即完整回复）。 */
        void onTextDelta(String delta);
        /** 一句 TTS 音频就绪，url 为可 GET 的签名 URL。 */
        void onAudioChunk(int sequence, String url, int durationMs);
        /** 流正常结束：transcribedText=ASR 识别文本，fullText=完整回复。 */
        void onDone(String transcribedText, String fullText, String aigcLabel);
        /** 出错（网络错误或后端 error 帧）。 */
        void onError(String message);
    }

    /**
     * 上传整段音频（WAV），返回 audio_id。阻塞调用，请在后台线程执行。
     *
     * @return audio_id，失败返回 null
     */
    public String uploadAudio(File wavFile) {
        try {
            RequestBody fileBody = RequestBody.create(wavFile, MediaType.parse("audio/wav"));
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", wavFile.getName(), fileBody)
                    .build();
            Request req = new Request.Builder()
                    .url(ApiConfig.UPLOAD_AUDIO)
                    .header("X-Client-Type", "android")
                    .post(body)
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                String s = resp.body() != null ? resp.body().string() : "";
                JSONObject json = new JSONObject(s);
                if (json.optInt("code", -1) != 0) {
                    Log.e(TAG, "uploadAudio 后端错误: " + json.optString("message"));
                    return null;
                }
                String audioId = json.getJSONObject("data").optString("audio_id", "");
                Log.d(TAG, "uploadAudio ok: " + audioId);
                return audioId.isEmpty() ? null : audioId;
            }
        } catch (Exception e) {
            Log.e(TAG, "uploadAudio 异常: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 上传整段图片（JPEG），返回 image_id（后端 data.file_id）。阻塞调用，请在后台线程执行。
     *
     * 对齐 uploadAudio：multipart 字段 file，仅端点/取值字段/MIME 不同。
     * 后端 /v1/upload/image 返回 {file_id, url, expires_at}，不入资料库，供以图搜图。
     *
     * @return image_id，失败返回 null
     */
    public String uploadImage(File imageFile) {
        try {
            RequestBody fileBody = RequestBody.create(imageFile, MediaType.parse("image/jpeg"));
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", imageFile.getName(), fileBody)
                    .build();
            Request req = new Request.Builder()
                    .url(ApiConfig.UPLOAD_IMAGE)
                    .header("X-Client-Type", "android")
                    .post(body)
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                String s = resp.body() != null ? resp.body().string() : "";
                JSONObject json = new JSONObject(s);
                if (json.optInt("code", -1) != 0) {
                    Log.e(TAG, "uploadImage 后端错误: " + json.optString("message"));
                    return null;
                }
                String imageId = json.getJSONObject("data").optString("file_id", "");
                Log.d(TAG, "uploadImage ok: " + imageId);
                return imageId.isEmpty() ? null : imageId;
            }
        } catch (Exception e) {
            Log.e(TAG, "uploadImage 异常: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 发起语音 query 并逐帧回调 SSE。阻塞调用，请在后台线程执行。
     */
    public void queryVoice(String audioId, QueryCallback cb) {
        SessionContext ctx = SessionContext.get();
        try {
            JSONObject reqBody = new JSONObject();
            reqBody.put("venue_id", ctx.venueId());
            reqBody.put("session_id", ctx.sessionId());
            reqBody.put("client_query_id", ctx.nextClientQueryId());
            reqBody.put("query_type", "voice");
            reqBody.put("audio_id", audioId);
            reqBody.put("language", "zh");

            Request req = new Request.Builder()
                    .url(ApiConfig.QUERY)
                    .header("X-Client-Type", "android")
                    .header("Accept", "text/event-stream")
                    .post(RequestBody.create(reqBody.toString(), JSON))
                    .build();

            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    cb.onError("query HTTP " + resp.code());
                    return;
                }
                parseSse(resp.body(), cb);
            }
        } catch (Exception e) {
            Log.e(TAG, "queryVoice 异常: " + e.getMessage(), e);
            cb.onError("query 异常: " + e.getMessage());
        }
    }

    /**
     * 发起拍照识物 query 并逐帧回调 SSE。阻塞调用，请在后台线程执行。
     *
     * 对齐 queryVoice：query_type=photo、body 带 image_id（无 audio_id）。
     * 复用同一套 SSE 解析与回调；后端 done 帧 transcribed_text 为 null（拍照无 ASR），
     * onDone 的 transcribedText 会是空串，调用方据此不回填"你说"气泡即可。
     */
    public void queryPhoto(String imageId, QueryCallback cb) {
        SessionContext ctx = SessionContext.get();
        try {
            JSONObject reqBody = new JSONObject();
            reqBody.put("venue_id", ctx.venueId());
            reqBody.put("session_id", ctx.sessionId());
            reqBody.put("client_query_id", ctx.nextClientQueryId());
            reqBody.put("query_type", "photo");
            reqBody.put("image_id", imageId);
            reqBody.put("language", "zh");

            Request req = new Request.Builder()
                    .url(ApiConfig.QUERY)
                    .header("X-Client-Type", "android")
                    .header("Accept", "text/event-stream")
                    .post(RequestBody.create(reqBody.toString(), JSON))
                    .build();

            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    cb.onError("query HTTP " + resp.code());
                    return;
                }
                parseSse(resp.body(), cb);
            }
        } catch (Exception e) {
            Log.e(TAG, "queryPhoto 异常: " + e.getMessage(), e);
            cb.onError("query 异常: " + e.getMessage());
        }
    }

    /**
     * 解析 SSE 流。帧格式：
     *   event: <name>\n
     *   data: <json>\n
     *   \n            (空行 = 一帧结束)
     */
    private void parseSse(ResponseBody body, QueryCallback cb) throws Exception {
        InputStream in = body.byteStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        String event = null;
        StringBuilder data = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                // 一帧结束，派发
                if (event != null && data.length() > 0) {
                    dispatchFrame(event, data.toString(), cb);
                }
                event = null;
                data.setLength(0);
                continue;
            }
            if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring("data:".length()).trim());
            }
            // 其余行（注释 / id: 等）忽略
        }
        // 流结束前若还有未派发的残帧
        if (event != null && data.length() > 0) {
            dispatchFrame(event, data.toString(), cb);
        }
    }

    private void dispatchFrame(String event, String dataJson, QueryCallback cb) {
        try {
            JSONObject d = new JSONObject(dataJson);
            switch (event) {
                case "text_delta":
                    cb.onTextDelta(d.optString("delta", ""));
                    break;
                case "audio_chunk":
                    cb.onAudioChunk(
                            d.optInt("sequence", 0),
                            d.optString("url", ""),
                            d.optInt("duration_ms", 0));
                    break;
                case "done":
                    cb.onDone(
                            d.optString("transcribed_text", ""),
                            d.optString("full_text", ""),
                            d.optString("aigc_label", ""));
                    break;
                case "error":
                    cb.onError(d.optString("message", "后端 error 帧"));
                    break;
                case "start":
                default:
                    // start / 未知帧：忽略
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "解析帧失败 event=" + event + " data=" + dataJson, e);
        }
    }
}
