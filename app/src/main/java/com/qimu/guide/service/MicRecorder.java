package com.qimu.guide.service;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * 手机麦克风录音（无眼镜时的音频来源）。
 *
 * 输出与眼镜端一致：16kHz / 单声道 / 16bit PCM，
 * 因此下游封 WAV + 走后端的逻辑完全复用，后端无差别（端无关）。
 *
 * 用法：start() 开始（后台线程持续读），stop() 停止并返回整段 PCM。
 */
public class MicRecorder {

    private static final String TAG = "MicRecorder";
    public static final int SAMPLE_RATE = 16000;

    private AudioRecord audioRecord;
    private volatile boolean recording = false;
    private Thread readThread;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    public boolean isRecording() {
        return recording;
    }

    /** 开始录音。需已获得 RECORD_AUDIO 权限（调用方负责）。 */
    @SuppressLint("MissingPermission")
    public boolean start() {
        if (recording) return true;
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize 失败: " + minBuf);
            return false;
        }
        int bufSize = minBuf * 2;
        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize);
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord 创建失败: " + e.getMessage(), e);
            return false;
        }
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 未初始化");
            audioRecord.release();
            audioRecord = null;
            return false;
        }

        buffer.reset();
        recording = true;
        audioRecord.startRecording();
        readThread = new Thread(() -> {
            byte[] chunk = new byte[bufSize];
            while (recording) {
                int n = audioRecord.read(chunk, 0, chunk.length);
                if (n > 0) {
                    synchronized (buffer) {
                        buffer.write(chunk, 0, n);
                    }
                }
            }
        }, "mic-read");
        readThread.start();
        Log.d(TAG, "录音开始");
        return true;
    }

    /** 停止录音，返回整段 PCM 字节（16k/mono/16bit）。 */
    public byte[] stop() {
        if (!recording) return new byte[0];
        recording = false;
        try {
            if (readThread != null) readThread.join(500);
        } catch (InterruptedException ignored) {}
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception ignored) {}
            audioRecord.release();
            audioRecord = null;
        }
        synchronized (buffer) {
            byte[] pcm = buffer.toByteArray();
            Log.d(TAG, "录音结束, " + pcm.length + " bytes (~" + (pcm.length / (SAMPLE_RATE * 2)) + "s)");
            return pcm;
        }
    }

    public void release() {
        recording = false;
        if (audioRecord != null) {
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
    }
}
