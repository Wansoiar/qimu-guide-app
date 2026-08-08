package com.qimu.guide.service;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.qimu.guide.net.GuideApiClient;
import com.ss.bytertc.engine.RTCEngine;
import com.ss.bytertc.engine.RTCRoom;
import com.ss.bytertc.engine.RTCRoomConfig;
import com.ss.bytertc.engine.UserInfo;
import com.ss.bytertc.engine.data.EngineConfig;
import com.ss.bytertc.engine.handler.IRTCEngineEventHandler;
import com.ss.bytertc.engine.handler.IRTCRoomEventHandler;
import com.ss.bytertc.engine.type.AudioScenarioType;
import com.ss.bytertc.engine.type.ChannelProfile;
import com.ss.bytertc.engine.type.SubtitleMessage;

/**
 * 火山 RTC 语音对话管理器（feat/volc-rtc 阶段3，纯音频）。
 *
 * 职责：手机端作为 RTC 房间的真人用户进房，与已在房内的 AI 智能体（后端 StartVoiceChat 拉起）实时语音对话。
 * 纯音频场景——不做任何视频采集/渲染。AI 音频靠 RTCRoomConfig 的自动订阅播放。
 *
 * 用法：
 *   mgr = new RtcVoiceChatManager(ctx);
 *   mgr.start(sessionInfo, listener);  // sessionInfo 来自 GuideApiClient.createRtcSession()
 *   ...
 *   mgr.stop();                        // 退房 + 销毁；调用方另需 stopRtcSession() 关后端智能体
 *
 * 所有回调在 SDK 线程触发，UI 操作需自行切主线程。
 */
public class RtcVoiceChatManager {

    private static final String TAG = "RtcVoiceChat";

    /** 进房与对话状态回调。 */
    public interface Listener {
        /** 本端进房结果。success=false 时 reason 为错误码/说明（如 token 错误）。 */
        void onRoomJoined(boolean success, String reason);
        /** AI 智能体进房（可据此提示"AI 已就绪"）。uid 为 bot 的 user id。 */
        void onAgentJoined(String uid);
        /** AI 或用户离开房间。 */
        void onUserLeave(String uid);
        /**
         * 实时字幕。fromSelf=true 是本人说话的 ASR，false 是 AI 的回复。
         * speakerKnown=false 表示字幕 payload 里没带说话人，fromSelf 是兜底推断值。
         */
        void onSubtitle(boolean fromSelf, boolean speakerKnown, String text, boolean definite);
        /** 非字幕 AIGC 事件，仅供调试/开发态展示，不给终端用户播报。 */
        void onDebugEvent(String category, String text);
        /** 引擎/房间错误码。 */
        void onError(int code, String desc);
    }

    private final Context appContext;
    private RTCEngine engine;
    private RTCRoom room;
    private Listener listener;
    private String selfUid;

    public RtcVoiceChatManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * 进房并开始语音对话。
     *
     * @param s        后端返回的进房信息（app_id/room_id/uid/token）
     * @param listener 状态回调
     */
    public void start(@NonNull GuideApiClient.RtcSessionInfo s, @NonNull Listener listener) {
        this.listener = listener;
        this.selfUid = s.uid;

        // 前置校验：appId 必须非空，否则 createRTCEngine 会返回 null
        if (s.appId == null || s.appId.isEmpty()) {
            Log.e(TAG, "start 失败：appId 为空");
            listener.onError(-100, "appId 为空（后端返回异常）");
            return;
        }

        // 1. 创建引擎（官方最小配置：context + appID）。
        //    注：AI 音质优化私有参数 aigc_media_360 待引擎能正常创建后再评估加入，先跑通。
        EngineConfig cfg = new EngineConfig();
        cfg.context = appContext;
        cfg.appID = s.appId;
        engine = RTCEngine.createRTCEngine(cfg, engineHandler);
        if (engine == null) {
            // createRTCEngine 返回 null：多为 native 库加载失败 / appId 非法 / 引擎已存在未销毁
            Log.e(TAG, "createRTCEngine 返回 null，appId=" + s.appId);
            listener.onError(-101, "RTC 引擎创建失败（createRTCEngine 返回 null）");
            return;
        }
        engine.setAudioScenario(AudioScenarioType.AICLIENT);

        // 2. 开启音频采集（纯音频，不做视频）
        engine.startAudioCapture();

        // 3. 建房 + 设监听 + 进房（自动发布音频、自动订阅音频；不发布/订阅视频）
        room = engine.createRTCRoom(s.roomId);
        room.setRTCRoomEventHandler(roomHandler);
        UserInfo userInfo = new UserInfo(s.uid, "");
        RTCRoomConfig roomConfig = new RTCRoomConfig(
                ChannelProfile.CHANNEL_PROFILE_CHAT_ROOM,
                /* isAutoPublishAudio */ true,
                /* isAutoPublishVideo */ false,
                /* isAutoSubscribeAudio */ true,
                /* isAutoSubscribeVideo */ false);
        int ret = room.joinRoom(s.token, userInfo, true, roomConfig);
        Log.d(TAG, "joinRoom ret=" + ret + " room=" + s.roomId + " uid=" + s.uid);
    }

    /** 退房并销毁引擎（幂等）。注意：关闭后端智能体任务请另调 GuideApiClient.stopRtcSession()。 */
    public void stop() {
        try {
            if (room != null) {
                room.leaveRoom();
                room.destroy();
                room = null;
            }
            if (engine != null) {
                engine.stopAudioCapture();
                RTCEngine.destroyRTCEngine();
                engine = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "stop 异常: " + e.getMessage(), e);
        }
    }

    /** 静音/取消静音本端麦克风（用 muteAudioCapture 保证最低切换延迟，见火山文档建议）。 */
    public void setMuted(boolean muted) {
        if (engine != null) engine.muteAudioCapture(muted);
    }

    /** 临时关闭/恢复远端音频订阅；拍照识物窗口期用来压住抢答语音。 */
    public void setRemoteAudioEnabled(boolean enabled) {
        try {
            if (room != null) room.subscribeAllStreamsAudio(enabled);
        } catch (Exception e) {
            Log.e(TAG, "setRemoteAudioEnabled 异常: " + e.getMessage(), e);
        }
    }

    private final IRTCEngineEventHandler engineHandler = new IRTCEngineEventHandler() {
        @Override
        public void onError(int err) {
            Log.e(TAG, "engine onError=" + err);
            if (listener != null) listener.onError(err, "engine error");
        }
    };

    private final IRTCRoomEventHandler roomHandler = new IRTCRoomEventHandler() {
        @Override
        public void onRoomStateChanged(String roomId, String uid, int state, String extraInfo) {
            // state==0 表示进房成功，其余为失败（如 -1000=token 错误）
            Log.d(TAG, "onRoomStateChanged state=" + state + " extra=" + extraInfo);
            if (listener != null) {
                if (state == 0) listener.onRoomJoined(true, null);
                else listener.onRoomJoined(false, "state=" + state + " " + extraInfo);
            }
        }

        @Override
        public void onUserJoined(UserInfo userInfo) {
            // 房内新增用户——AI 智能体进房时会触发（uid 为 bot 的 user id）
            String uid = userInfo != null ? userInfo.getUid() : "";
            Log.d(TAG, "onUserJoined uid=" + uid);
            if (listener != null) listener.onAgentJoined(uid);
        }

        @Override
        public void onUserLeave(String uid, int reason) {
            Log.d(TAG, "onUserLeave uid=" + uid + " reason=" + reason);
            if (listener != null) listener.onUserLeave(uid);
        }

        @Override
        public void onSubtitleMessageReceived(SubtitleMessage[] subtitles) {
            // startSubtitle 字幕翻译服务的回调；AIGC 场景字幕不走这里（走 onRoomBinaryMessageReceived）
            if (listener == null || subtitles == null) return;
            for (SubtitleMessage m : subtitles) {
                if (m == null || m.text == null || m.text.isEmpty()) continue;
                boolean speakerKnown = m.userId != null && !m.userId.isEmpty();
                boolean fromSelf = selfUid != null && selfUid.equals(m.userId);
                listener.onSubtitle(fromSelf, speakerKnown, m.text, m.definite);
            }
        }

        // AIGC 字幕/状态走房间二进制消息（magic 'subv' + JSON）。解析 subv 提取字幕。
        @Override
        public void onRoomBinaryMessageReceived(String uid, java.nio.ByteBuffer message) {
            parseAigcBinary(uid, message);
        }
    };

    /** 解析 AIGC 二进制消息：magic 前缀（subv=字幕）+ JSON。提取字幕文本 → onSubtitle。 */
    private void parseAigcBinary(String uid, java.nio.ByteBuffer message) {
        if (listener == null || message == null) return;
        try {
            byte[] bytes = new byte[message.remaining()];
            message.get(bytes);
            String raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            int magicEnd = raw.indexOf('{');  // 跳过 magic 头，定位 JSON 起始
            if (magicEnd < 0) return;
            String magic = raw.substring(0, Math.min(4, Math.max(0, magicEnd)));
            // [临时排障] 把所有非字幕(subv)的 AIGC 事件都打出来——火山的 function call / MCP
            // 决策事件走别的 magic 前缀(如 func/conv/tool)，之前被直接忽略，导致看不见 bot
            // 到底有没有决定调 knowledge_search / MCP 连接报了什么错。验收后清理。
            if (!magic.contains("subv")) {
                String payload = raw.substring(magicEnd, Math.min(raw.length(), magicEnd + 500));
                Log.w(TAG, "AIGC非字幕事件 magic=[" + magic + "] json=" + payload);
                listener.onDebugEvent(magic, payload);
                return;  // 仍只处理字幕；conv(状态)/func 等只 log 不上屏
            }
            org.json.JSONObject root = new org.json.JSONObject(raw.substring(magicEnd));
            org.json.JSONArray data = root.optJSONArray("data");
            if (data == null) return;
            for (int i = 0; i < data.length(); i++) {
                org.json.JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                String text = item.optString("text", "");
                if (text.isEmpty()) continue;
                String speaker = item.optString("userId", item.optString("user_id", ""));
                boolean definite = item.optBoolean("definite", item.optBoolean("paragraph", false));
                boolean speakerKnown = speaker != null && !speaker.isEmpty();
                boolean fromSelf;
                if (speakerKnown) {
                    fromSelf = selfUid != null && selfUid.equals(speaker);
                } else {
                    // 火山偶尔不带 data.userId；退回房间消息 uid，减少用户字幕被误判成 AI。
                    fromSelf = selfUid != null && selfUid.equals(uid);
                }
                listener.onSubtitle(fromSelf, speakerKnown, text, definite);
            }
        } catch (Exception e) {
            Log.d(TAG, "parseAigcBinary 忽略: " + e.getMessage());
        }
    }
}
