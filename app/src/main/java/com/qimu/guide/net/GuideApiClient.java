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
        /** 流式 ASR 中间结果（累积文本，可覆盖刷新"你说：…"气泡）。isFinal=VAD 判停。
         *  默认空实现：整段 SSE 路径不触发此回调，老回调无需改。 */
        default void onAsrPartial(String text, boolean isFinal) {}
        /** LLM 逐字增量（拼接即完整回复）。 */
        void onTextDelta(String delta);
        /** 一句 TTS 音频就绪，url 为可 GET 的签名 URL。 */
        void onAudioChunk(int sequence, String url, int durationMs);
        /** 流正常结束：transcribedText=ASR 识别文本，fullText=完整回复。 */
        void onDone(String transcribedText, String fullText, String aigcLabel);
        /** 出错（网络错误或后端 error 帧）。 */
        void onError(String message);
    }

    /** 图片上传结果：同时保留 file_id 和公网 url，供 RTC inject 链路使用。 */
    public static final class ImageUploadResult {
        public final String fileId;
        public final String url;

        ImageUploadResult(String fileId, String url) {
            this.fileId = fileId;
            this.url = url;
        }
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
                    .url(ApiConfig.uploadAudio())
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
    public ImageUploadResult uploadImage(File imageFile) {
        try {
            RequestBody fileBody = RequestBody.create(imageFile, MediaType.parse("image/jpeg"));
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", imageFile.getName(), fileBody)
                    .build();
            Request req = new Request.Builder()
                    .url(ApiConfig.uploadImage())
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
                JSONObject data = json.getJSONObject("data");
                String imageId = data.optString("file_id", "");
                String imageUrl = data.optString("url", "");
                Log.d(TAG, "uploadImage ok: " + imageId + " url=" + imageUrl);
                if (imageId.isEmpty() || imageUrl.isEmpty()) return null;
                return new ImageUploadResult(imageId, imageUrl);
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
                    .url(ApiConfig.query())
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
                    .url(ApiConfig.query())
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
     * 流式语音会话句柄：向上行 WS 推 PCM / 结束本轮 / 中止。
     */
    public interface StreamSession {
        /** 推一包 PCM（16k/mono/16bit raw）。 */
        void sendPcm(byte[] pcm, int length);
        /** 用户手动结束本轮（可选；服务端 VAD 判停也会自动结束）。 */
        void finish();
        /** 中止并关闭连接（如打断/离开页面）。 */
        void cancel();
    }

    /**
     * 发起流式语音 query（上行 WS）。非阻塞：立即返回 StreamSession，
     * 边采边 sendPcm()，说完 finish()。下行帧通过 cb 逐帧回调（后台线程）。
     *
     * 契约见后端 apps/api/routers/stream_query.py：
     *   建连 QUERY_STREAM_WS?venue_id=&session_id=&client_query_id=&language=
     *   上行 二进制帧=PCM；文本帧 {"type":"end"}=结束
     *   下行 文本帧 {"event":..,"data":{..}}，event 语义同 SSE
     */
    public StreamSession queryVoiceStream(QueryCallback cb) {
        SessionContext ctx = SessionContext.get();
        String url = ApiConfig.queryStreamWs()
                + "?venue_id=" + ctx.venueId()
                + "&session_id=" + ctx.sessionId()
                + "&client_query_id=" + ctx.nextClientQueryId()
                + "&language=zh";
        Request req = new Request.Builder()
                .url(url)
                .header("X-Client-Type", "android")
                .build();

        final okhttp3.WebSocket[] wsHolder = new okhttp3.WebSocket[1];
        okhttp3.WebSocket ws = client.newWebSocket(req, new okhttp3.WebSocketListener() {
            @Override
            public void onMessage(okhttp3.WebSocket webSocket, String text) {
                // 下行 {"event":..,"data":{..}} → 复用 dispatchFrame
                try {
                    JSONObject msg = new JSONObject(text);
                    String event = msg.optString("event", "");
                    JSONObject data = msg.optJSONObject("data");
                    dispatchFrame(event, data != null ? data.toString() : "{}", cb);
                } catch (Exception e) {
                    Log.e(TAG, "WS 帧解析失败: " + text, e);
                }
            }

            @Override
            public void onFailure(okhttp3.WebSocket webSocket, Throwable t, Response response) {
                Log.e(TAG, "WS 失败: " + t.getMessage(), t);
                cb.onError("stream WS 失败: " + t.getMessage());
            }
        });
        wsHolder[0] = ws;

        return new StreamSession() {
            @Override
            public void sendPcm(byte[] pcm, int length) {
                // OkHttp send(ByteString) 复制字节，需精确长度
                okio.ByteString bs = okio.ByteString.of(pcm, 0, length);
                wsHolder[0].send(bs);
            }

            @Override
            public void finish() {
                try {
                    wsHolder[0].send(new JSONObject().put("type", "end").toString());
                } catch (Exception ignored) {}
            }

            @Override
            public void cancel() {
                wsHolder[0].cancel();
            }
        };
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
                case "asr_partial":
                    cb.onAsrPartial(d.optString("text", ""), d.optBoolean("is_final", false));
                    break;
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

    // ── 火山 RTC 会话编排（feat/volc-rtc）──────────────────────────────

    /** 后端 /v1/rtc/session 返回的进房信息。 */
    public static final class RtcSessionInfo {
        public final String appId;
        public final String roomId;
        public final String uid;
        public final String token;
        public final String taskId;
        public final boolean mocked;  // true=后端未真调 StartVoiceChat（无凭证），AI 不会进房

        RtcSessionInfo(String appId, String roomId, String uid, String token, String taskId, boolean mocked) {
            this.appId = appId; this.roomId = roomId; this.uid = uid;
            this.token = token; this.taskId = taskId; this.mocked = mocked;
        }
    }

    /**
     * 创建 RTC 会话（后端签 Token + 调 StartVoiceChat 让 AI 进房）。阻塞调用，请在后台线程执行。
     *
     * @param venueId 场馆 UUID，可为 null（用默认馆 / 通用讲解员 prompt）
     * @return 进房信息，失败返回 null
     */
    public RtcSessionInfo createRtcSession(String venueId) {
        try {
            JSONObject reqBody = new JSONObject();
            if (venueId != null && !venueId.isEmpty()) reqBody.put("venue_id", venueId);
            Request req = new Request.Builder()
                    .url(ApiConfig.rtcSession())
                    .header("X-Client-Type", "android")
                    .post(RequestBody.create(reqBody.toString(), JSON))
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                String s = resp.body() != null ? resp.body().string() : "";
                JSONObject json = new JSONObject(s);
                if (json.optInt("code", -1) != 0) {
                    Log.e(TAG, "createRtcSession 后端错误: " + json.optString("message"));
                    return null;
                }
                JSONObject d = json.getJSONObject("data");
                return new RtcSessionInfo(
                        d.optString("app_id", ""), d.optString("room_id", ""),
                        d.optString("uid", ""), d.optString("token", ""),
                        d.optString("task_id", ""), d.optBoolean("mocked", false));
            }
        } catch (Exception e) {
            Log.e(TAG, "createRtcSession 异常: " + e.getMessage(), e);
            return null;
        }
    }

    /** 结束 RTC 会话（退房后调，避免持续计费）。阻塞调用，请在后台线程执行。 */
    public void stopRtcSession(String roomId, String taskId) {
        try {
            JSONObject reqBody = new JSONObject();
            reqBody.put("room_id", roomId);
            reqBody.put("task_id", taskId);
            Request req = new Request.Builder()
                    .url(ApiConfig.rtcSessionStop())
                    .header("X-Client-Type", "android")
                    .post(RequestBody.create(reqBody.toString(), JSON))
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                Log.d(TAG, "stopRtcSession resp=" + (resp.body() != null ? resp.body().string() : ""));
            }
        } catch (Exception e) {
            Log.e(TAG, "stopRtcSession 异常: " + e.getMessage(), e);
        }
    }

    /**
     * 把一段文本注入当前 RTC 对话（ExternalTextToLLM）。阻塞调用，请在后台线程执行。
     *
     * 用于拍照识物统一到火山：上传图片拿到公网 image_url 后，注入给同一个 RTC 会话。
     * 成功返回 true，失败返回 false。
     */
    public boolean injectRtcSession(String roomId, String taskId, String message, int interruptMode) {
        try {
            JSONObject reqBody = new JSONObject();
            reqBody.put("room_id", roomId);
            reqBody.put("task_id", taskId);
            reqBody.put("message", message);
            reqBody.put("interrupt_mode", interruptMode);
            Request req = new Request.Builder()
                    .url(ApiConfig.rtcSessionInject())
                    .header("X-Client-Type", "android")
                    .post(RequestBody.create(reqBody.toString(), JSON))
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                String s = resp.body() != null ? resp.body().string() : "";
                JSONObject json = new JSONObject(s);
                if (json.optInt("code", -1) != 0) {
                    Log.e(TAG, "injectRtcSession 后端错误: " + json.optString("message"));
                    return false;
                }
                return json.optJSONObject("data") != null
                        && json.getJSONObject("data").optBoolean("injected", false);
            }
        } catch (Exception e) {
            Log.e(TAG, "injectRtcSession 异常: " + e.getMessage(), e);
            return false;
        }
    }
}
