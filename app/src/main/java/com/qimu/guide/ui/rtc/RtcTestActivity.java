package com.qimu.guide.ui.rtc;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.qimu.guide.R;
import com.qimu.guide.net.GuideApiClient;
import com.qimu.guide.service.RtcVoiceChatManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 火山 RTC 语音对话测试页（feat/volc-rtc 阶段3）。
 *
 * 纯手机麦克风验证光链路（不接眼镜、不接 RAG）：
 *   点「开始对话」→ 调后端 /v1/rtc/session（AI 进房）→ 手机进房 → 说话，AI 语音回答 + 实时字幕。
 *   点「结束对话」→ 退房 + 关后端智能体任务（避免计费）。
 *
 * 独立入口，不改动主对话页。验证通过后再决定如何整合进主流程。
 */
public class RtcTestActivity extends AppCompatActivity {

    private static final int REQ_PERM = 1001;

    private TextView tvStatus;
    private TextView tvSubtitle;
    private Button btnStart;
    private Button btnStop;
    private Button btnMute;

    private final GuideApiClient api = new GuideApiClient();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private RtcVoiceChatManager rtc;
    private GuideApiClient.RtcSessionInfo session;
    private boolean muted = false;
    private final StringBuilder subtitleBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rtc_test);

        tvStatus = findViewById(R.id.tv_status);
        tvSubtitle = findViewById(R.id.tv_subtitle);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnMute = findViewById(R.id.btn_mute);

        rtc = new RtcVoiceChatManager(this);

        btnStart.setOnClickListener(v -> ensurePermThenStart());
        btnStop.setOnClickListener(v -> stopChat());
        btnMute.setOnClickListener(v -> {
            muted = !muted;
            rtc.setMuted(muted);
            btnMute.setText(muted ? "取消静音" : "静音");
        });
    }

    private void ensurePermThenStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM);
            return;
        }
        startChat();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startChat();
            } else {
                Toast.makeText(this, "需要麦克风权限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startChat() {
        setStatus("正在创建会话…");
        btnStart.setEnabled(false);
        io.execute(() -> {
            // venue_id 传 null（默认馆 / 通用讲解员）；后续可从选馆页传入
            GuideApiClient.RtcSessionInfo s = api.createRtcSession(null);
            runOnUiThread(() -> {
                if (s == null) {
                    setStatus("创建会话失败（看后端日志）");
                    btnStart.setEnabled(true);
                    return;
                }
                session = s;
                if (s.mocked) {
                    // 后端无凭证走 mock，AI 不会进房——明确提示，避免误判"进房了但没人说话"
                    setStatus("⚠️ 后端 mock 模式（无火山凭证），AI 不会进房。请在后端 .env 配置凭证。");
                }
                setStatus((s.mocked ? "⚠️ mock：" : "") + "进房中… room=" + s.roomId);
                rtc.start(s, listener);
                btnStop.setEnabled(true);
                btnMute.setEnabled(true);
            });
        });
    }

    private void stopChat() {
        setStatus("结束中…");
        btnStop.setEnabled(false);
        btnMute.setEnabled(false);
        rtc.stop();  // 先退房
        final GuideApiClient.RtcSessionInfo s = session;
        io.execute(() -> {
            if (s != null) api.stopRtcSession(s.roomId, s.taskId);  // 再关后端智能体
            runOnUiThread(() -> {
                setStatus("已结束");
                btnStart.setEnabled(true);
            });
        });
    }

    private final RtcVoiceChatManager.Listener listener = new RtcVoiceChatManager.Listener() {
        @Override
        public void onRoomJoined(boolean success, String reason) {
            runOnUiThread(() -> setStatus(success
                    ? "已进房 ✓ 可以开始说话了"
                    : "进房失败：" + reason));
        }

        @Override
        public void onAgentJoined(String uid) {
            runOnUiThread(() -> setStatus("AI 已就绪 ✓（" + uid + "），开始对话吧"));
        }

        @Override
        public void onUserLeave(String uid) {
            runOnUiThread(() -> appendSubtitle("[系统] " + uid + " 离开"));
        }

        @Override
        public void onSubtitle(boolean fromSelf, String text, boolean definite) {
            // 仅在最终结果时落一行，避免中间态刷屏（先跑通体验，后续可做覆盖式刷新）
            if (!definite) return;
            runOnUiThread(() -> appendSubtitle((fromSelf ? "你：" : "AI：") + text));
        }

        @Override
        public void onError(int code, String desc) {
            runOnUiThread(() -> setStatus("错误 code=" + code + " " + desc));
        }
    };

    private void setStatus(String s) {
        tvStatus.setText("状态：" + s);
    }

    private void appendSubtitle(String line) {
        subtitleBuf.append(line).append("\n");
        tvSubtitle.setText(subtitleBuf.toString());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        rtc.stop();
        io.shutdown();
    }
}
