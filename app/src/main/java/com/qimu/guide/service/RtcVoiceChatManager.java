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

import org.json.JSONObject;

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
        /** 实时字幕。fromSelf=true 是本人说话的 ASR，false 是 AI 的回复。definite=最终结果。 */
        void onSubtitle(boolean fromSelf, String text, boolean definite);
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

        // 1. 创建引擎（AI 对话音频场景 + 私有参数，见火山文档 3.60 AI 场景优化）
        EngineConfig cfg = new EngineConfig();
        cfg.context = appContext;
        cfg.appID = s.appId;
        try {
            cfg.parameters = new JSONObject().put("rtc.fg_config", "aigc_media_360=true");
        } catch (Exception ignore) {}
        engine = RTCEngine.createRTCEngine(cfg, engineHandler);
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
            if (listener == null || subtitles == null) return;
            for (SubtitleMessage m : subtitles) {
                if (m == null || m.text == null || m.text.isEmpty()) continue;
                boolean fromSelf = selfUid != null && selfUid.equals(m.userId);
                listener.onSubtitle(fromSelf, m.text, m.definite);
            }
        }
    };
}
