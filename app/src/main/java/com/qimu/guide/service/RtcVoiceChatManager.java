package com.qimu.guide.service;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.qimu.guide.net.GuideApiClient;
import com.ss.bytertc.engine.RTCEngine;
import com.ss.bytertc.engine.RTCRoom;
import com.ss.bytertc.engine.RTCRoomConfig;
import com.ss.bytertc.engine.UserInfo;
import com.ss.bytertc.engine.data.AudioChannel;
import com.ss.bytertc.engine.data.AudioPropertiesConfig;
import com.ss.bytertc.engine.data.AudioRenderType;
import com.ss.bytertc.engine.data.AudioRoute;
import com.ss.bytertc.engine.data.AudioSampleRate;
import com.ss.bytertc.engine.data.RemoteAudioPropertiesInfo;
import com.ss.bytertc.engine.data.AudioSourceType;
import com.ss.bytertc.engine.data.EngineConfig;
import com.ss.bytertc.engine.handler.IRTCEngineEventHandler;
import com.ss.bytertc.engine.handler.IRTCRoomEventHandler;
import com.ss.bytertc.engine.type.AudioScenarioType;
import com.ss.bytertc.engine.type.ChannelProfile;
import com.ss.bytertc.engine.type.RoomState;
import com.ss.bytertc.engine.type.RoomStateChangeReason;
import com.ss.bytertc.engine.type.SubtitleMessage;
import com.ss.bytertc.engine.utils.AudioFrame;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 火山 RTC 纯音频房间。
 *
 * 本端不打开手机麦克风。眼镜 Translation 回调给出的 16 kHz/mono/PCM16 会先重帧为
 * 10 ms，再通过 ByteRTC external audio source 注入；AI 下行音频仍由 SDK 自动订阅播放。
 */
public class RtcVoiceChatManager {

    private static final String TAG = "RtcVoiceChat";
    private static final int PCM_FRAME_BYTES = 320; // 10 ms, 16kHz, mono, PCM16
    private static final int PCM_FRAME_SAMPLES = 160;
    private static final int PCM_QUEUE_CAPACITY = 50; // 最多缓存 500 ms，避免延迟无限增长

    public interface Listener {
        void onRoomJoined(boolean success, String reason);
        default void onRoomInterrupted(boolean recoverable, String reason) { }
        default void onTokenWillExpire() { }
        void onAgentJoined(String uid);
        void onUserLeave(String uid);
        void onSubtitle(boolean fromSelf, String text, boolean definite, int sequence);
        default void onCommand(String senderUid, String payload) { }
        /**
         * 火山 client-side Function Calling：模型下发工具调用指令（如 take_photo）。
         * 端侧执行后需调 {@link #sendFunctionResult} 把结果回填给模型继续讲解。
         */
        default void onFunctionCall(String senderUid, String toolCallId, String functionName) { }
        void onError(int code, String desc);
    }

    private final Context appContext;
    private final Object pcmLock = new Object();
    private final ArrayBlockingQueue<byte[]> pcmFrames =
            new ArrayBlockingQueue<>(PCM_QUEUE_CAPACITY);
    private final byte[] pendingPcm = new byte[PCM_FRAME_BYTES];

    private volatile RTCEngine engine;
    private RTCRoom room;
    private volatile Listener listener;
    private volatile boolean inputEnabled;
    private int pendingPcmSize;
    private String selfUid;
    private ScheduledExecutorService framePump;
    // 下行外部渲染播放器：走 VOICE_CALL 通话流，替代 RTC 内部媒体流渲染（见类注释）。
    private volatile RtcDownlinkVoicePlayer downlinkPlayer;
    // AIGC 的 subv 二进制字幕在部分服务版本里不带 sequence。为同一说话人的
    // interim/final 维护稳定序号，使 UI 能覆盖更新而不是重复追加气泡。
    private int nextBinarySubtitleSequence = 1_000_000;
    private int selfBinarySubtitleSequence = -1;
    private int agentBinarySubtitleSequence = -1;
    // VoiceChat 可能在后续 subv 包里再次携带已经结束的字幕项。完整 JSON 相同就属于
    // 同一个服务端事件，必须幂等；否则 definite 后序号已重置，会被 UI 当成新消息。
    private static final int MAX_FINAL_SUBTITLE_FINGERPRINTS = 256;
    private final Set<String> finalBinarySubtitleFingerprints = new LinkedHashSet<>();

    public RtcVoiceChatManager(@NonNull Context context) {
        appContext = context.getApplicationContext();
    }

    /** 创建引擎并进房；默认保持静音，调用 setInputEnabled(true) 后才会注入眼镜 PCM。 */
    public void start(@NonNull GuideApiClient.RtcSessionInfo session,
                      @NonNull Listener listener) {
        stop();
        this.listener = listener;
        selfUid = session.uid;
        resetBinarySubtitleSequences();

        if (session.appId == null || session.appId.isEmpty()) {
            listener.onError(-100, "appId 为空（后端返回异常）");
            return;
        }

        EngineConfig config = new EngineConfig();
        config.context = appContext;
        config.appID = session.appId;
        RTCEngine created = RTCEngine.createRTCEngine(config, engineHandler);
        if (created == null) {
            listener.onError(-101, "RTC 引擎创建失败");
            return;
        }
        engine = created;
        created.setAudioScenario(AudioScenarioType.AICLIENT);
        // 方案 D（2026-08-16）：下行改「外部渲染」。RTC 内部渲染实测把下行钉在媒体流
        // (mStreamType=3)，通话模式下被压到 tVol~0.0075。改 EXTERNAL 后 RTC 不再自己播，
        // 由 RtcDownlinkVoicePlayer 拉流并用 VOICE_CALL 通话流 AudioTrack 播出 → 像打电话一样不被压低。
        int renderRet = created.setAudioRenderType(AudioRenderType.AUDIO_RENDER_TYPE_EXTERNAL);
        Log.i(TAG, "setAudioRenderType(EXTERNAL) ret=" + renderRet);
        // setPlaybackVolume 是远端混音增益(100=原始，最大 400)，外部渲染前的混音阶段仍生效，作为额外放大。
        int volRet = created.setPlaybackVolume(400);
        Log.i(TAG, "setPlaybackVolume(400) ret=" + volRet);
        int sourceResult = created.setAudioSourceType(
                AudioSourceType.AUDIO_SOURCE_TYPE_EXTERNAL);
        if (sourceResult != 0) {
            listener.onError(sourceResult, "RTC 外部音频源初始化失败");
            stop();
            return;
        }

        // 下行音量探针：每 500ms 报一次远端实际音量（linearVolume 0~255），
        // 把「感觉小」变成数字，用来判定改场景/路由是否真的抬升了下行电平。
        try {
            created.enableAudioPropertiesReport(new AudioPropertiesConfig(500));
        } catch (RuntimeException e) {
            Log.w(TAG, "enableAudioPropertiesReport 失败", e);
        }
        startFramePump();
        // 外部渲染播放器随引擎启动；它内部拉流线程在有下行数据时才出声（无数据=静默），
        // 早启动不影响，且能确保 AI 首帧就有播放通道。
        downlinkPlayer = new RtcDownlinkVoicePlayer(created);
        downlinkPlayer.start();
        room = created.createRTCRoom(session.roomId);
        if (room == null) {
            listener.onError(-102, "RTC 房间创建失败");
            stop();
            return;
        }
        room.setRTCRoomEventHandler(roomHandler);
        UserInfo userInfo = new UserInfo(session.uid, "");
        RTCRoomConfig roomConfig = new RTCRoomConfig(
                ChannelProfile.CHANNEL_PROFILE_CHAT_ROOM,
                true,
                false,
                true,
                false);
        int joinResult = room.joinRoom(session.token, userInfo, true, roomConfig);
        Log.d(TAG, "joinRoom ret=" + joinResult + " room=" + session.roomId);
        if (joinResult != 0) {
            listener.onRoomJoined(false, "joinRoom=" + joinResult);
        }
    }

    /** Translation PCM 的唯一入口；任意长度均可，内部按 10 ms 重帧并限流。 */
    public void pushExternalPcm(byte[] pcm) {
        if (!inputEnabled || pcm == null || pcm.length == 0) return;
        synchronized (pcmLock) {
            if (!inputEnabled) return;
            int offset = 0;
            while (offset < pcm.length) {
                int copyLength = Math.min(PCM_FRAME_BYTES - pendingPcmSize,
                        pcm.length - offset);
                System.arraycopy(pcm, offset, pendingPcm, pendingPcmSize, copyLength);
                pendingPcmSize += copyLength;
                offset += copyLength;
                if (pendingPcmSize == PCM_FRAME_BYTES) {
                    byte[] frame = pendingPcm.clone();
                    if (!pcmFrames.offer(frame)) {
                        pcmFrames.poll();
                        pcmFrames.offer(frame);
                        Log.w(TAG, "PCM 队列已满，丢弃最旧 10 ms 音频");
                    }
                    pendingPcmSize = 0;
                }
            }
        }
    }

    /** 开关外部 PCM gate。关闭时立即丢弃尚未发送的数据，避免暂停后迟到音频上送。 */
    public void setInputEnabled(boolean enabled) {
        synchronized (pcmLock) {
            inputEnabled = enabled;
            if (!enabled) clearPcmQueueLocked();
        }
    }

    public boolean isInputEnabled() {
        return inputEnabled;
    }

    /**
     * 让 SDK 感知当前走蓝牙路由。真机日志发现：external source + 外部手动 SCO 组合下，
     * SDK 未识别蓝牙路由，把它内部播放 AudioTrack 的 track 音量掐到 ~0.0075（近静音），
     * 导致系统/SDK/设备三层音量拉满仍偏小。通知 SDK 路由为蓝牙后，SDK 应按正常路由
     * 处理 track 音量。须在 SCO 建立（call mode 就绪）后调用。
     */
    public void routeToBluetooth() {
        RTCEngine currentEngine = engine;
        if (currentEngine == null) return;
        try {
            int ret = currentEngine.setAudioRoute(AudioRoute.AUDIO_ROUTE_HEADSET_BLUETOOTH);
            Log.i(TAG, "setAudioRoute(BLUETOOTH) ret=" + ret
                    + " now=" + currentEngine.getAudioRoute());
        } catch (RuntimeException e) {
            Log.w(TAG, "setAudioRoute 失败", e);
        }
    }

    /** 兼容旧测试入口；外部音频模式下“静音”就是关闭 PCM gate。 */
    public void setMuted(boolean muted) {
        setInputEnabled(!muted);
    }

    /** 退房并销毁引擎。后端 VoiceChat Agent 仍需由 API 显式停止。 */
    public void stop() {
        // 先切断回调，再触发 leave/destroy。部分 RTC 实现会在 leaveRoom 期间同步回调，
        // 若此时新会话已经开始，旧会话事件不能再污染新的游览状态。
        listener = null;
        setInputEnabled(false);
        stopFramePump();
        RtcDownlinkVoicePlayer player = downlinkPlayer;
        downlinkPlayer = null;
        if (player != null) player.stop();
        RTCRoom currentRoom = room;
        room = null;
        if (currentRoom != null) {
            try {
                currentRoom.leaveRoom();
            } catch (RuntimeException e) {
                Log.w(TAG, "leaveRoom 失败", e);
            }
            try {
                currentRoom.destroy();
            } catch (RuntimeException e) {
                Log.w(TAG, "destroy room 失败", e);
            }
        }
        if (engine != null) {
            engine = null;
            try {
                RTCEngine.destroyRTCEngine();
            } catch (RuntimeException e) {
                Log.w(TAG, "destroy engine 失败", e);
            }
        }
        selfUid = null;
        resetBinarySubtitleSequences();
    }

    private void startFramePump() {
        stopFramePump();
        framePump = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rtc-pcm-pump");
            thread.setDaemon(true);
            return thread;
        });
        // fixed-delay 避免 App 从 cached 状态恢复时补跑积压 tick，形成旧音频突发上送。
        framePump.scheduleWithFixedDelay(this::pushNextFrame, 0, 10, TimeUnit.MILLISECONDS);
    }

    private void stopFramePump() {
        ScheduledExecutorService pump = framePump;
        framePump = null;
        if (pump != null) pump.shutdownNow();
        clearPcmQueue();
    }

    private void clearPcmQueue() {
        synchronized (pcmLock) {
            clearPcmQueueLocked();
        }
    }

    private void clearPcmQueueLocked() {
        pendingPcmSize = 0;
        pcmFrames.clear();
    }

    private void pushNextFrame() {
        synchronized (pcmLock) {
            if (!inputEnabled) return;
            byte[] pcm = pcmFrames.poll();
            RTCEngine currentEngine = engine;
            if (pcm == null || currentEngine == null) return;
            try {
                AudioFrame frame = new AudioFrame(
                        pcm,
                        PCM_FRAME_SAMPLES,
                        AudioSampleRate.AUDIO_SAMPLE_RATE_16000,
                        AudioChannel.AUDIO_CHANNEL_MONO);
                int result = currentEngine.pushExternalAudioFrame(frame);
                if (result != 0) Log.w(TAG, "pushExternalAudioFrame ret=" + result);
            } catch (RuntimeException e) {
                Log.e(TAG, "外部 PCM 注入失败", e);
            }
        }
    }

    private final IRTCEngineEventHandler engineHandler = new IRTCEngineEventHandler() {
        @Override
        public void onError(int errorCode) {
            Listener current = listener;
            if (current != null) current.onError(errorCode, "engine error");
        }

        @Override
        public void onRemoteAudioPropertiesReport(
                RemoteAudioPropertiesInfo[] infos, int totalRemoteVolume) {
            // 下行电平探针：linearVolume 0~255。若这里数值正常(几十~上百)但耳朵仍小，
            // 说明 SDK 混音后电平是够的，小在系统 SCO 输出端(瓶颈在系统层非 RTC 层)；
            // 若这里本身就极小，说明瓶颈在 RTC 场景/路由映射(改 scenario 有救)。
            if (infos == null || infos.length == 0) return;
            int v = infos[0].audioPropertiesInfo != null
                    ? infos[0].audioPropertiesInfo.linearVolume : -1;
            Log.i(TAG, "下行音量探针 linearVolume=" + v
                    + " totalRemote=" + totalRemoteVolume + " streams=" + infos.length);
        }
    };

    private final IRTCRoomEventHandler roomHandler = new IRTCRoomEventHandler() {
        @Override
        public void onRoomStateChanged(String roomId, String uid,
                                       int state, String extraInfo) {
            // 兼容仍通过旧回调报告进房结果的 ByteRTC SDK/服务组合。
            Listener current = listener;
            if (current == null) return;
            if (state == 0) {
                current.onRoomJoined(true, null);
            } else {
                current.onRoomInterrupted(false,
                        "state=" + state + " " + extraInfo);
            }
        }

        @Override
        public void onRoomStateChangedWithReason(String roomId, String uid,
                                                 RoomState state,
                                                 RoomStateChangeReason reason) {
            Listener current = listener;
            if (current == null) return;
            if (state == RoomState.JOIN_SUCCESS) {
                current.onRoomJoined(true, null);
                return;
            }
            boolean recoverable = reason == RoomStateChangeReason.RECONNECT;
            current.onRoomInterrupted(recoverable,
                    "state=" + state + " reason=" + reason);
        }

        @Override
        public void onTokenWillExpire() {
            Listener current = listener;
            if (current != null) current.onTokenWillExpire();
        }

        @Override
        public void onUserJoined(UserInfo userInfo) {
            Listener current = listener;
            if (current != null) {
                current.onAgentJoined(userInfo == null ? "" : userInfo.getUid());
            }
        }

        @Override
        public void onUserLeave(String uid, int reason) {
            Listener current = listener;
            if (current != null) current.onUserLeave(uid);
        }

        @Override
        public void onSubtitleMessageReceived(SubtitleMessage[] subtitles) {
            Listener current = listener;
            if (current == null || subtitles == null) return;
            for (SubtitleMessage subtitle : subtitles) {
                if (subtitle == null || subtitle.text == null || subtitle.text.isEmpty()) continue;
                boolean fromSelf = selfUid != null && selfUid.equals(subtitle.userId);
                current.onSubtitle(fromSelf, subtitle.text, subtitle.definite,
                        subtitle.sequence);
            }
        }

        @Override
        public void onRoomBinaryMessageReceived(String uid, java.nio.ByteBuffer message) {
            parseAigcBinary(uid, message);
        }

        @Override
        public void onUserMessageReceived(String uid, String message) {
            dispatchCommand(uid, message);
        }

        @Override
        public void onUserMessageReceived(long messageId, String uid, String message) {
            dispatchCommand(uid, message);
        }

        @Override
        public void onRoomMessageReceived(String uid, String message) {
            dispatchCommand(uid, message);
        }

        @Override
        public void onRoomMessageReceived(long messageId, String uid, String message) {
            dispatchCommand(uid, message);
        }

        private void dispatchCommand(String uid, String message) {
            Listener current = listener;
            if (current == null || message == null || message.trim().isEmpty()) return;
            current.onCommand(uid, message);
        }
    };

    /**
     * 火山 VoiceChat 的 AIGC 字幕通过「subv + JSON」房间二进制消息下发，
     * 并不会稳定进入 onSubtitleMessageReceived。这里将它归一成同一字幕回调，
     * 非字幕消息仍交给控制命令解析，不把调试 payload 暴露到用户气泡。
     */
    private void parseAigcBinary(String uid, java.nio.ByteBuffer message) {
        Listener current = listener;
        if (current == null || message == null) return;
        try {
            byte[] bytes = new byte[message.remaining()];
            message.get(bytes);
            String raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            int jsonStart = raw.indexOf('{');
            if (jsonStart < 0) return;
            String magic = raw.substring(0, Math.min(4, jsonStart));
            String payload = raw.substring(jsonStart);
            // 火山 client-side Function Calling 二进制 TLV：magic=info(通知)/tool(指令)/func(结果)。
            // tool 指令里含 tool_calls，需解析出 function.name + id 交给上层执行拍照等本地动作。
            if (magic.contains("tool")) {
                handleFunctionCallTool(uid, payload);
                return;
            }
            if (magic.contains("info")) {
                Log.d(TAG, "FC info 通知: " + payload);
                return;
            }
            if (!magic.contains("subv")) {
                Log.d(TAG, "AIGC event magic=" + magic);
                current.onCommand(uid, payload);
                return;
            }

            org.json.JSONObject root = new org.json.JSONObject(payload);
            org.json.JSONArray data = root.optJSONArray("data");
            if (data == null) return;
            for (int i = 0; i < data.length(); i++) {
                org.json.JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                String text = item.optString("text", "").trim();
                if (text.isEmpty()) continue;
                String speaker = item.optString(
                        "userId", item.optString("user_id", ""));
                boolean fromSelf = speaker.isEmpty()
                        ? selfUid != null && selfUid.equals(uid)
                        : selfUid != null && selfUid.equals(speaker);
                boolean definite = item.optBoolean(
                        "definite", item.optBoolean("paragraph", false));
                if (definite && !rememberFinalBinarySubtitle(speaker, uid, item)) {
                    continue;
                }
                int sequence = resolveBinarySubtitleSequence(fromSelf, item, definite);
                current.onSubtitle(fromSelf, text, definite, sequence);
            }
        } catch (Exception error) {
            Log.d(TAG, "忽略无法解析的 AIGC 二进制消息", error);
        }
    }

    /**
     * 解析火山 FC `tool` 指令 payload（JSON，含 tool_calls 数组），取第一个函数调用的
     * id + name 交给上层执行。arguments 当前工具（take_photo）无参数，暂不解析。
     */
    private void handleFunctionCallTool(String uid, String payload) {
        Listener current = listener;
        if (current == null) return;
        try {
            org.json.JSONObject root = new org.json.JSONObject(payload);
            org.json.JSONArray calls = root.optJSONArray("tool_calls");
            if (calls == null || calls.length() == 0) {
                Log.w(TAG, "FC tool payload 无 tool_calls: " + payload);
                return;
            }
            org.json.JSONObject call = calls.optJSONObject(0);
            if (call == null) return;
            String toolCallId = call.optString("id", "");
            org.json.JSONObject fn = call.optJSONObject("function");
            String name = fn != null ? fn.optString("name", "") : "";
            Log.i(TAG, "FC tool 指令: name=" + name + " id=" + toolCallId);
            if (name.isEmpty() || toolCallId.isEmpty()) return;
            current.onFunctionCall(uid, toolCallId, name);
        } catch (Exception e) {
            Log.w(TAG, "解析 FC tool 指令失败: " + payload, e);
        }
    }

    /**
     * 回填 FC 执行结果给模型（magic=func TLV），模型据此继续用同一把 TTS 讲解。
     * @param botUid AI Bot 的 UserId（AgentConfig.UserId）
     * @param toolCallId 必须与收到的 tool 指令 id 一致
     * @param content 函数执行结果（如识图讲解素材 / 重拍提示）
     */
    public void sendFunctionResult(String botUid, String toolCallId, String content) {
        RTCRoom current = room;
        if (current == null || botUid == null || botUid.isEmpty()) {
            Log.w(TAG, "sendFunctionResult 跳过：room/botUid 为空");
            return;
        }
        try {
            org.json.JSONObject msg = new org.json.JSONObject();
            msg.put("ToolCallID", toolCallId);
            msg.put("Content", content);
            byte[] buf = buildTlv("func", msg.toString());
            long ret = current.sendUserBinaryMessage(
                    botUid, buf, com.ss.bytertc.engine.type.MessageConfig.RELIABLE_ORDERED);
            // 注：content 过长时火山虽发送成功(sendRet>0)也不生成讲解，长度由后端控制。
            Log.i(TAG, "FC func 结果已回填 id=" + toolCallId + " len=" + content.length()
                    + " bytes=" + buf.length + " sendRet=" + ret);
        } catch (Exception e) {
            Log.w(TAG, "sendFunctionResult 失败 id=" + toolCallId, e);
        }
    }

    /** 构造火山二进制 TLV：[4B magic ascii][4B big-endian length][UTF-8 payload]。 */
    private static byte[] buildTlv(String magic, String content) {
        byte[] magicBytes = magic.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] contentBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.ByteBuffer buffer =
                java.nio.ByteBuffer.allocate(magicBytes.length + 4 + contentBytes.length);
        buffer.order(java.nio.ByteOrder.BIG_ENDIAN);
        buffer.put(magicBytes);
        buffer.putInt(contentBytes.length);
        buffer.put(contentBytes);
        return buffer.array();
    }

    private synchronized int resolveBinarySubtitleSequence(
            boolean fromSelf, org.json.JSONObject item, boolean definite) {
        int explicit = item.optInt("sequence", item.optInt("seq", -1));
        if (explicit >= 0) return explicit;

        int active = fromSelf ? selfBinarySubtitleSequence : agentBinarySubtitleSequence;
        if (active < 0) active = nextBinarySubtitleSequence++;
        if (fromSelf) selfBinarySubtitleSequence = definite ? -1 : active;
        else agentBinarySubtitleSequence = definite ? -1 : active;
        return active;
    }

    private synchronized boolean rememberFinalBinarySubtitle(
            String speaker, String senderUid, org.json.JSONObject item) {
        String owner = speaker == null || speaker.isEmpty() ? senderUid : speaker;
        String fingerprint = owner + '\u0000' + item.toString();
        if (!finalBinarySubtitleFingerprints.add(fingerprint)) return false;
        if (finalBinarySubtitleFingerprints.size() > MAX_FINAL_SUBTITLE_FINGERPRINTS) {
            Iterator<String> iterator = finalBinarySubtitleFingerprints.iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        return true;
    }

    private synchronized void resetBinarySubtitleSequences() {
        nextBinarySubtitleSequence = 1_000_000;
        selfBinarySubtitleSequence = -1;
        agentBinarySubtitleSequence = -1;
        finalBinarySubtitleFingerprints.clear();
    }
}
