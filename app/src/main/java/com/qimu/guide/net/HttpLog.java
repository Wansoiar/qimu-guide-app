package com.qimu.guide.net;

import android.util.Log;

import com.qimu.guide.BuildConfig;

import okhttp3.Interceptor;
import okhttp3.logging.HttpLoggingInterceptor;

/**
 * Debug 构建下的全量 HTTP 日志：每个请求打印入参（完整 URL/Header/Body）与出参（状态码/响应体）。
 *
 * 日志走 logcat（tag "okhttp"），用法：
 *   adb logcat -s okhttp 或 adb logcat | grep okhttp
 */
public final class HttpLog {

    private static final String TAG = "okhttp";

    private HttpLog() {
    }

    /** 返回日志拦截器：debug 打印 BODY 级日志，release 为空操作。 */
    public static Interceptor debugLogger() {
        if (!BuildConfig.DEBUG) {
            return chain -> chain.proceed(chain.request());
        }
        // OkHttp 4.x 默认 Logger 在 Android 上走 java.util.logging，logcat 标签不是 okhttp，
        // 必须显式指定 android.util.Log Logger 才能用 adb logcat -s okhttp 抓到。
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor(
                message -> Log.i(TAG, message));
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);
        return logging;
    }
}
