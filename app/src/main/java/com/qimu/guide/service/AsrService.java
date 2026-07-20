package com.qimu.guide.service;

import android.util.Base64;
import android.util.Log;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.util.UUID;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AsrService {
    private static final String TAG = "AsrService";
    private static final String ASR_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash";
    private static final String APP_ID = "4528732279";
    private static final String ACCESS_TOKEN = "FcCYfiOGTFRaRzHQPoir85Ycpfe-iVkc";
    private static final String RESOURCE_ID = "volc.bigasr.auc_turbo";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build();

    public interface AsrCallback {
        void onResult(String text, int durationMs);
        void onError(String message);
    }

    public void recognize(File wavFile, AsrCallback callback) {
        new Thread(() -> {
            try {
                byte[] audioBytes = new byte[(int) wavFile.length()];
                FileInputStream fis = new FileInputStream(wavFile);
                fis.read(audioBytes); fis.close();
                String b64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP);

                JSONObject body = new JSONObject();
                body.put("user", new JSONObject().put("uid", APP_ID));
                body.put("audio", new JSONObject().put("data", b64));
                body.put("request", new JSONObject().put("model_name", "bigmodel"));

                Request req = new Request.Builder().url(ASR_URL)
                        .addHeader("X-Api-App-Key", APP_ID)
                        .addHeader("X-Api-Access-Key", ACCESS_TOKEN)
                        .addHeader("X-Api-Resource-Id", RESOURCE_ID)
                        .addHeader("X-Api-Request-Id", UUID.randomUUID().toString())
                        .addHeader("X-Api-Sequence", "-1")
                        .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                        .build();

                Response resp = client.newCall(req).execute();
                String status = resp.header("X-Api-Status-Code", "");
                if (!"20000000".equals(status)) {
                    callback.onError("ASR失败: " + resp.header("X-Api-Message", "") + " (status=" + status + ")");
                    return;
                }
                JSONObject result = new JSONObject(resp.body().string());
                String text = result.optJSONObject("result").optString("text", "");
                callback.onResult(text, result.optJSONObject("audio_info").optInt("duration", 0));
            } catch (Exception e) {
                callback.onError("ASR异常: " + e.getMessage());
            }
        }).start();
    }
}