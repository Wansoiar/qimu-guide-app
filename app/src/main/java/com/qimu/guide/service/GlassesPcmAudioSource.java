package com.qimu.guide.service;

import android.util.Log;

import androidx.annotation.NonNull;

import com.moyoung.glasses.conn.CRPBleConnection;
import com.moyoung.glasses.conn.callback.CRPCommandCallback;

/**
 * 眼镜持续麦克风流的业务适配器。
 *
 * 厂商当前仅通过 Translation 指令暴露这条 Opus/PCM 通道，因此相关 SDK 命名被严格
 * 封装在本类；上层只把它视为可开始、暂停、恢复和停止的眼镜音频源。
 */
public final class GlassesPcmAudioSource {

    private static final String TAG = "GlassesPcmSource";

    public interface Listener {
        void onStarted();
        void onPcm(byte[] pcm);
        void onError(int code, String message);
    }

    private final Object lock = new Object();
    private CRPBleConnection connection;
    private Listener listener;
    private int generation;
    private boolean acceptingPcm;

    /** 首次开始或暂停后恢复。成功回调只代表硬件命令已确认。 */
    public void start(@NonNull CRPBleConnection newConnection,
                      @NonNull Listener newListener) {
        final int requestGeneration;
        synchronized (lock) {
            generation++;
            requestGeneration = generation;
            acceptingPcm = false;
            connection = newConnection;
            listener = newListener;
        }

        try {
            newConnection.setTranslationListener(audio -> {
                Listener currentListener;
                synchronized (lock) {
                    if (requestGeneration != generation || !acceptingPcm
                            || connection != newConnection || audio == null
                            || audio.length == 0) {
                        return;
                    }
                    currentListener = listener;
                }
                if (currentListener != null) currentListener.onPcm(audio);
            });
            newConnection.startTranslation(new CRPCommandCallback() {
                @Override
                public void onSuccess() {
                    Listener currentListener;
                    synchronized (lock) {
                        if (requestGeneration != generation || connection != newConnection) return;
                        acceptingPcm = true;
                        currentListener = listener;
                    }
                    if (currentListener != null) currentListener.onStarted();
                }

                @Override
                public void onFailure(int errorCode) {
                    Listener currentListener;
                    synchronized (lock) {
                        if (requestGeneration != generation || connection != newConnection) return;
                        acceptingPcm = false;
                        currentListener = listener;
                    }
                    if (currentListener != null) {
                        currentListener.onError(errorCode,
                                errorCode == 9 ? "眼镜音频通道正忙" : "眼镜音频通道启动失败");
                    }
                }
            });
        } catch (RuntimeException error) {
            Log.e(TAG, "启动眼镜音频通道失败", error);
            Listener currentListener;
            synchronized (lock) {
                if (requestGeneration != generation) return;
                acceptingPcm = false;
                currentListener = listener;
            }
            if (currentListener != null) currentListener.onError(-1, "眼镜音频通道启动异常");
        }
    }

    /** 暂停真实音频流；恢复时再次调用 start。 */
    public void pause() {
        CRPBleConnection currentConnection;
        synchronized (lock) {
            generation++;
            acceptingPcm = false;
            currentConnection = connection;
        }
        if (currentConnection == null) return;
        try {
            currentConnection.pauseTranslation();
        } catch (RuntimeException error) {
            Log.w(TAG, "暂停眼镜音频通道失败", error);
        }
    }

    /** 彻底停止并释放厂商 SDK 的全局音频监听器。 */
    public void stop() {
        CRPBleConnection currentConnection;
        synchronized (lock) {
            generation++;
            acceptingPcm = false;
            currentConnection = connection;
            connection = null;
            listener = null;
        }
        if (currentConnection == null) return;
        try {
            currentConnection.stopTranslation();
        } catch (RuntimeException error) {
            Log.w(TAG, "停止眼镜音频通道失败", error);
        }
        try {
            currentConnection.setTranslationListener(null);
        } catch (RuntimeException error) {
            Log.w(TAG, "释放眼镜音频监听器失败", error);
        }
    }
}
