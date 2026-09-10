package com.qimu.guide.net;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.qimu.guide.BuildConfig;
import com.qimu.guide.QimuApplication;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * App 端统一共享密钥鉴权拦截器（后端 AppPublicAuthMiddleware，见后端 .env → APP_SHARED_SECRET）。
 *
 * 1. 给所有请求注入 X-App-Token（值 = BuildConfig.APP_SHARED_SECRET，与后端 .env 同源）。
 * 2. 请求返 401 + code 30001（密钥不匹配/未配置）时，置鉴权失败标记并 Toast 一次
 *    「配置错误，请联系运维」，上层据此中止重试，而不是静默重试。
 *
 * 注意：/v1/rtc/subtitle 是火山回调给后端，App 从不调用，不需要排除。
 */
public final class AppAuthInterceptor implements Interceptor {

    private static final String TAG = "AppAuth";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean authError;

    private AppAuthInterceptor() {
    }

    public static final AppAuthInterceptor INSTANCE = new AppAuthInterceptor();

    /** 若有鉴权失败，返回 true 并清除标记（供重试逻辑判定「不要再重试」）。 */
    public static boolean consumeAuthError() {
        boolean had = authError;
        authError = false;
        return had;
    }

    public static boolean hasAuthError() {
        return authError;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Request tokenized = request.newBuilder()
                .header("X-App-Token", BuildConfig.APP_SHARED_SECRET)
                .build();
        Response response = chain.proceed(tokenized);
        if (response.code() == 401 && response.body() != null) {
            try {
                ResponseBody peek = response.peekBody(4096);
                JSONObject json = new JSONObject(peek.string());
                if (json.optInt("code", -1) == 30001) {
                    reportAuthError();
                }
            } catch (Exception parseError) {
                Log.w(TAG, "解析 401 响应失败", parseError);
            }
        }
        return response;
    }

    private static void reportAuthError() {
        if (authError) return;
        authError = true;
        MAIN.post(() -> {
            try {
                Toast.makeText(QimuApplication.getAppContext(),
                        "配置错误，请联系运维", Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
                // Toast 失败不影响主流程
            }
        });
    }
}