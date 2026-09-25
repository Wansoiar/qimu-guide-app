package com.qimu.guide.net;

import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.util.Map;
import java.util.LinkedHashMap;
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

/** RTC 导览、图片上传与识图客户端。 */
public class GuideApiClient {

    private static final String TAG = "GuideApiClient";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final Set<Call> activeCalls = java.util.Collections.newSetFromMap(
            new ConcurrentHashMap<Call, Boolean>());
    private final Object callLock = new Object();
    private final Set<Call> rtcCalls = java.util.Collections.newSetFromMap(
            new ConcurrentHashMap<Call, Boolean>());
    private long rtcCallEpoch;
    private final Object visionCallLock = new Object();
    private Call activeVisionCall;
    private boolean visionCallsCancelled;
    private boolean closed;

    public GuideApiClient() {
        this(new OkHttpClient.Builder()
                .addInterceptor(AppAuthInterceptor.INSTANCE)
                .addInterceptor(HttpLog.rtcLogger())
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build());
    }

    GuideApiClient(OkHttpClient client) { this.client = client; }

    /** 给对话/RTC 链路请求统一加公参 header（X-Device-Id / X-Glasses-Sn / X-Phone-Number 等）。 */
    private Request.Builder withDialogueHeaders(Request.Builder builder) {
        return withDialogueHeaders(builder, AppContextHeaders.dialogue());
    }

    private Request.Builder withDialogueHeaders(Request.Builder builder, Map<String, String> headers) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        return builder;
    }

    public static final class UploadedImage {
        public final String fileId;
        public final String url;

        UploadedImage(String fileId, String url) {
            this.fileId = fileId;
            this.url = url;
        }
    }

    /** /v1/rtc/session/describe-image 的识图结果。 */
    public static final class ImageDescribeResult {
        /** 后端置信度三态字符串（PRD §4.4）。 */
        public static final String HIGH_CONF = "high_conf";       // 已确定展品，summary 为讲解资料
        public static final String AMBIGUOUS = "ambiguous";       // 多个候选展品，summary 为引导确认语
        public static final String NOT_RECOGNIZED = "not_recognized"; // 未匹配到知识库展品

        /** 三态之一（旧版曾是 boolean，后端已改为字符串，勿再用 optBoolean 解析）。 */
        public final String recognized;
        public final String exhibitName;
        public final String summary;

        ImageDescribeResult(String recognized, String exhibitName, String summary) {
            this.recognized = recognized;
            this.exhibitName = exhibitName;
            this.summary = summary;
        }

        public boolean isHighConf() { return HIGH_CONF.equals(recognized); }
        public boolean isAmbiguous() { return AMBIGUOUS.equals(recognized); }
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
        public final boolean mocked;
        public final long expireAt;

        RtcSessionInfo(String sessionId, String appId, String roomId, String uid,
                       String token, String taskId, String botUid,
                       boolean mocked, long expireAt) {
            this.sessionId = sessionId;
            this.appId = appId;
            this.roomId = roomId;
            this.uid = uid;
            this.token = token;
            this.taskId = taskId;
            this.botUid = botUid;
            this.mocked = mocked;
            this.expireAt = expireAt;
        }

        public boolean sameTask(RtcSessionInfo other) {
            return other != null && sessionId.equals(other.sessionId) && roomId.equals(other.roomId)
                    && taskId.equals(other.taskId) && uid.equals(other.uid);
        }
    }

    public static final class RtcResult {
        public final RtcSessionInfo session;
        public final int httpStatus;
        public final int code;

        RtcResult(RtcSessionInfo session, int httpStatus, int code) {
            this.session = session;
            this.httpStatus = httpStatus;
            this.code = code;
        }

        public boolean retryable() { return isRtcRetryable(httpStatus, code); }
        public boolean identityRejected() { return isRtcIdentityRejected(httpStatus, code); }
    }

    /** 上传眼镜照片，返回供 RTC Agent 访问的图片 URL，并将对象归属到当前会话。阻塞调用。 */
    public UploadedImage uploadImage(File imageFile, @Nullable String sessionId) {
        Call call = null;
        try {
            MultipartBody body = buildImageUploadBody(imageFile, sessionId);
            Request request = withDialogueHeaders(new Request.Builder()
                    .url(ApiConfig.uploadImage())
                    .header("X-Client-Type", "android")
                    .post(body))
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

    static MultipartBody buildImageUploadBody(File imageFile, @Nullable String sessionId) {
        RequestBody fileBody = RequestBody.create(imageFile, MediaType.parse("image/jpeg"));
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", imageFile.getName(), fileBody);
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            builder.addFormDataPart("session_id", sessionId.trim());
        }
        return builder.build();
    }

    /** Initial start only. Recovery must use the overload carrying the previous task proof. */
    public RtcSessionInfo createRtcSession(String sessionId) {
        return createRtcSession(sessionId, null).session;
    }

    public RtcResult createRtcSession(String sessionId, @Nullable RtcSessionInfo previous) {
        return createRtcSession(sessionId, previous, rtcCallEpoch());
    }

    public RtcResult createRtcSession(String sessionId, @Nullable RtcSessionInfo previous, long epoch) {
        return rtcRequest(sessionId, previous, false, epoch);
    }

    public RtcResult renewRtcToken(RtcSessionInfo current) {
        return renewRtcToken(current, rtcCallEpoch());
    }

    public RtcResult renewRtcToken(RtcSessionInfo current, long epoch) {
        return rtcRequest(current.sessionId, current, true, epoch);
    }

    static Map<String, Object> rtcRequestFields(String sessionId, @Nullable RtcSessionInfo previous,
                                               boolean renewal) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("session_id", sessionId.trim());
        if (renewal && previous == null) throw new IllegalArgumentException("missing RTC identity");
        if (previous != null) {
            if (!sessionId.equals(previous.sessionId)) throw new IllegalArgumentException("RTC identity mismatch");
            fields.put(renewal ? "task_id" : "previous_task_id", previous.taskId);
            fields.put("room_id", previous.roomId);
            fields.put("uid", previous.uid);
            fields.put("token", previous.token);
        } else {
            fields.put("initial_only", true);
        }
        return fields;
    }

    static boolean isRtcRetryable(int status, int code) {
        if (isRtcIdentityRejected(status, code)) return false;
        // Older create endpoints report supplier failure as HTTP 200 / business code 502.
        return code == 40911 || code == 502 || status == 0 || status == 408
                || status == 429 || status >= 500;
    }

    static boolean isRtcIdentityRejected(int status, int code) {
        return code == 40310 || code == 40910 || code == 40401 || status == 401 || status == 403;
    }

    static boolean isRtcResponseValid(String sessionId, @Nullable RtcSessionInfo previous,
                                      boolean renewal, RtcSessionInfo result) {
        return result != null && sessionId.equals(result.sessionId) && !result.appId.isEmpty()
                && !result.roomId.isEmpty() && !result.uid.isEmpty() && !result.taskId.isEmpty()
                && !result.token.isEmpty() && result.expireAt > 0
                && (!renewal || result.sameTask(previous))
                && (renewal || previous == null || !result.taskId.equals(previous.taskId));
    }

    /** A stale idempotent response can name the active task; that task must not be cleaned up. */
    public static boolean shouldCleanUpRtcResponse(@Nullable RtcSessionInfo response,
                                                   @Nullable RtcSessionInfo attached) {
        return response != null && !response.sameTask(attached);
    }

    private RtcResult rtcRequest(String sessionId, @Nullable RtcSessionInfo previous,
                                  boolean renewal, long epoch) {
        Call call = null;
        try {
            JSONObject body = new JSONObject(rtcRequestFields(sessionId, previous, renewal));
            Request request = withDialogueHeaders(new Request.Builder()
                    .url(renewal ? ApiConfig.rtcSessionRenewToken() : ApiConfig.rtcSession())
                    .header("X-Client-Type", "android")
                    .post(RequestBody.create(body.toString(), JSON)))
                    .build();
            call = client.newCall(request);
            call.timeout().timeout(25, TimeUnit.SECONDS);
            if (!registerRtcCall(call, epoch)) return new RtcResult(null, 0, -1);
            try (Response response = call.execute()) {
                String raw = response.body() == null ? "" : response.body().string();
                JSONObject envelope;
                try {
                    envelope = new JSONObject(raw);
                } catch (Exception invalidJson) {
                    return new RtcResult(null, response.code(), -1);
                }
                int code = envelope.optInt("code", -1);
                if (!response.isSuccessful() || code != 0) {
                    Log.w(TAG, "RTC request failed HTTP=" + response.code() + " code=" + code);
                    return new RtcResult(null, response.code(), code);
                }
                JSONObject data = envelope.getJSONObject("data");
                RtcSessionInfo result = new RtcSessionInfo(
                        data.optString("session_id", ""),
                        renewal ? previous.appId : data.optString("app_id", ""),
                        data.optString("room_id", ""), data.optString("uid", ""),
                        data.optString("token", ""), data.optString("task_id", ""),
                        renewal ? previous.botUid : data.optString("bot_uid", ""),
                        renewal ? previous.mocked : data.optBoolean("mocked", false),
                        data.optLong("expire_at", 0));
                if (!isRtcResponseValid(sessionId, previous, renewal, result)) {
                    return new RtcResult(null, 422, -1);
                }
                return new RtcResult(result, response.code(), 0);
            }
        } catch (Exception error) {
            // Never include JSON/token contents, including exception messages, in logs.
            Log.w(TAG, "RTC request failed: " + error.getClass().getSimpleName());
            return new RtcResult(null, 0, -1);
        } finally {
            if (call != null) rtcCalls.remove(call);
            unregister(call);
        }
    }

    public void cancelRtcCalls() {
        synchronized (callLock) {
            rtcCallEpoch++;
            for (Call call : rtcCalls) call.cancel();
            rtcCalls.clear();
        }
    }

    /** Capture before enqueueing work, so cancellation also fences requests not registered yet. */
    public long rtcCallEpoch() {
        synchronized (callLock) { return rtcCallEpoch; }
    }

    boolean registerRtcCall(Call call, long expectedEpoch) {
        synchronized (callLock) {
            if (closed || expectedEpoch != rtcCallEpoch) {
                call.cancel();
                return false;
            }
            activeCalls.add(call);
            rtcCalls.add(call);
            return true;
        }
    }

    static Map<String, Object> rtcStopFields(String roomId, String taskId,
                                            @Nullable String sessionId, boolean endSession) {
        Map<String, Object> fields = new LinkedHashMap<>();
        boolean hasRoom = roomId != null && !roomId.trim().isEmpty();
        boolean hasTask = taskId != null && !taskId.trim().isEmpty();
        boolean hasSession = sessionId != null && !sessionId.trim().isEmpty();
        if (hasRoom != hasTask || (!hasRoom && (!endSession || !hasSession))) {
            throw new IllegalArgumentException("incomplete RTC stop identity");
        }
        if (hasRoom) {
            fields.put("room_id", roomId);
            fields.put("task_id", taskId);
        }
        fields.put("end_session", endSession);
        if (hasSession) fields.put("session_id", sessionId.trim());
        return fields;
    }

    /** Snapshot the identity and headers before freezing/clearing the active tour. */
    public static final class RtcStopRequest {
        @Nullable public final String roomId;
        @Nullable public final String taskId;
        public final String sessionId;
        public final Map<String, String> headers;

        public RtcStopRequest(@Nullable String roomId, @Nullable String taskId,
                              String sessionId, Map<String, String> headers) {
            if (sessionId == null || sessionId.trim().isEmpty()) {
                throw new IllegalArgumentException("tour stop requires session_id");
            }
            this.sessionId = sessionId.trim();
            // A restart may retain only one RTC field. End by session instead of sending
            // a partial identity (or the empty strings rejected by the backend).
            boolean complete = roomId != null && !roomId.trim().isEmpty()
                    && taskId != null && !taskId.trim().isEmpty();
            this.roomId = complete ? roomId.trim() : null;
            this.taskId = complete ? taskId.trim() : null;
            this.headers = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }
    }

    public static final class RtcStopResult {
        public final boolean ok;
        @Nullable public final String serverMessage;

        public RtcStopResult(boolean ok, @Nullable String serverMessage) {
            this.ok = ok;
            this.serverMessage = serverMessage;
        }
    }

    /** Destructive cleanup requires an explicit acknowledgement, never a missing field. */
    static RtcStopResult parseRtcStopResult(int httpStatus, String responseBody) {
        try {
            JsonObject json = new JsonParser().parse(responseBody).getAsJsonObject();
            JsonElement code = json.get("code");
            JsonElement data = json.get("data");
            JsonElement stopped = data != null && data.isJsonObject()
                    ? data.getAsJsonObject().get("stopped") : null;
            boolean ok = httpStatus >= 200 && httpStatus < 300
                    && code != null && code.isJsonPrimitive()
                    && code.getAsJsonPrimitive().isNumber() && code.getAsDouble() == 0
                    && stopped != null && stopped.isJsonPrimitive()
                    && stopped.getAsJsonPrimitive().isBoolean() && stopped.getAsBoolean();
            JsonElement message = json.get("message");
            String text = message != null && message.isJsonPrimitive()
                    && message.getAsJsonPrimitive().isString() ? message.getAsString().trim() : "";
            if (text.isEmpty() || "ok".equalsIgnoreCase(text) || "success".equalsIgnoreCase(text)) {
                text = null;
            }
            return new RtcStopResult(ok, ok ? null : text);
        } catch (RuntimeException invalidResponse) {
            return new RtcStopResult(false, null);
        }
    }

    /**
     * 停止后端 VoiceChat Agent，避免结束游览后继续占用。阻塞调用。
     *
     * @param sessionId 传入租借 session_id（rentals/start 返回、create 与重连复用同一条）
     *                  后，后端除停火山外还会把该 session 的 rtc_status 落 stopped 并清挂起
     *                  RAG 预取；传 null 则只停火山、不动状态。
     */
    public boolean stopRtcSession(String roomId, String taskId, @Nullable String sessionId) {
        return stopRtcSession(roomId, taskId, sessionId, true);
    }

    public boolean stopRtcSession(String roomId, String taskId, @Nullable String sessionId,
                                  boolean endSession) {
        return stopRtcSession(roomId, taskId, sessionId, endSession, AppContextHeaders.dialogue());
    }

    /** Uses the tour's captured headers even after the active UI session has been cleared. */
    public boolean stopRtcSession(String roomId, String taskId, @Nullable String sessionId,
                                  boolean endSession, Map<String, String> headers) {
        return stopRtcSessionWithResult(roomId, taskId, sessionId, endSession, headers, 0L).ok;
    }

    /** The timeout covers the entire HTTP call, including writes and slow response bodies. */
    public RtcStopResult stopRtcSessionWithResult(String roomId, String taskId,
                                                  @Nullable String sessionId, boolean endSession,
                                                  Map<String, String> headers, long timeoutMs) {
        Call call = null;
        try {
            String body = new Gson().toJson(rtcStopFields(roomId, taskId, sessionId, endSession));
            Request request = withDialogueHeaders(new Request.Builder()
                    .url(ApiConfig.rtcSessionStop())
                    .header("X-Client-Type", "android")
                    .post(RequestBody.create(body, JSON)), headers)
                    .build();
            OkHttpClient stopClient = timeoutMs > 0 ? client.newBuilder()
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS).build() : client;
            call = stopClient.newCall(request);
            if (!register(call)) return new RtcStopResult(false, null);
            try (Response response = call.execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                return parseRtcStopResult(response.code(), responseBody);
            }
        } catch (Exception e) {
            if (call != null && call.isCanceled()) return new RtcStopResult(false, null);
            Log.e(TAG, "stopRtcSession 异常", e);
            return new RtcStopResult(false, null);
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

    /** 把一段文本作为外部用户消息注入当前 RTC Agent（手动拍照讲解用）。阻塞调用。 */
    public boolean injectRtcMessage(String roomId, String taskId, String message) {
        Call call = null;
        try {
            JSONObject body = new JSONObject();
            body.put("room_id", roomId);
            body.put("task_id", taskId);
            body.put("message", message);
            body.put("interrupt_mode", 1);
            Request request = withDialogueHeaders(new Request.Builder()
                    .url(ApiConfig.rtcSessionInject())
                    .header("X-Client-Type", "android")
                    .post(RequestBody.create(body.toString(), JSON)))
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
     * 拍照识物：后端 CLIP 以图搜图 -> 返回识别结果与讲解素材（方案A FC 分支用）。
     *
     * @param sessionId 必传：当前 RTC 会话 id（/v1/rtc/session 返回的 session_id），
     *                  用于把识图结果挂到会话并落库。
     * @param roundId   触发拍照的语音轮次 roundId（火山 roundId）；语音触发拍照必传，
     *                  手动拍照按钮（无语音）传 0，后端回退为独立照片回合。
     */
    public ImageDescribeResult describeRtcImage(String venueId, String sessionId,
                                                String imageUrl, int roundId) {
        Call call = null;
        try {
            JSONObject body = new JSONObject();
            body.put("venue_id", venueId);
            body.put("session_id", sessionId.trim());
            body.put("image_url", imageUrl);
            if (roundId > 0) {
                body.put("round_id", roundId);
            }
            Request request = withDialogueHeaders(new Request.Builder()
                    .url(ApiConfig.rtcSessionDescribeImage())
                    .header("X-Client-Type", "android")
                    .post(RequestBody.create(body.toString(), JSON)))
                    .build();
            call = client.newCall(request);
            if (!register(call) || !registerVisionCall(call)) return null;
            try (Response response = call.execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(responseBody);
                if (!response.isSuccessful() || json.optInt("code", -1) != 0) {
                    Log.e(TAG, "describeRtcImage backend error: " + json.optString("message"));
                    return null;
                }
                JSONObject data = json.optJSONObject("data");
                if (data == null) return null;
                return new ImageDescribeResult(
                        data.optString("recognized", ImageDescribeResult.NOT_RECOGNIZED),
                        data.optString("exhibit_name", ""),
                        data.optString("summary", ""));
            }
        } catch (Exception e) {
            if (call != null && call.isCanceled()) return null;
            Log.e(TAG, "describeRtcImage exception", e);
            return null;
        } finally {
            unregisterVisionCall(call);
            unregister(call);
        }
    }

    /**
     * Cancels in-flight calls owned by the current UI lifecycle and prevents a racing
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

}
