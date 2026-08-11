package com.qimu.guide.service;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * 手机麦克风录音。
 *
 * 输出与眼镜音频一致：16 kHz、单声道、16 bit PCM。手机长按和眼镜左键
 * 因而可以共用同一套 WAV 封装及后端问答链路。
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

    /** 开始录音。调用方必须先获得 RECORD_AUDIO 权限。 */
    @SuppressLint("MissingPermission")
    public boolean start() {
        if (recording) return true;

        int minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) {
            Log.e(TAG, "getMinBufferSize 失败: " + minBufferSize);
            return false;
        }

        int bufferSize = minBufferSize * 2;
        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord 创建失败", e);
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
        try {
            audioRecord.startRecording();
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord 启动失败", e);
            recording = false;
            audioRecord.release();
            audioRecord = null;
            return false;
        }

        readThread = new Thread(() -> {
            byte[] chunk = new byte[bufferSize];
            while (recording) {
                AudioRecord recorder = audioRecord;
                if (recorder == null) break;
                int count;
                try {
                    count = recorder.read(chunk, 0, chunk.length);
                } catch (Exception e) {
                    if (recording) Log.e(TAG, "读取麦克风失败", e);
                    break;
                }
                if (count > 0) {
                    synchronized (buffer) {
                        buffer.write(chunk, 0, count);
                    }
                }
            }
        }, "mic-read");
        readThread.start();
        Log.d(TAG, "录音开始");
        return true;
    }

    /** 停止录音，返回整段 16 kHz/mono/16 bit PCM。 */
    public byte[] stop() {
        if (!recording) return new byte[0];

        recording = false;
        AudioRecord recorder = audioRecord;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
        }

        Thread thread = readThread;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        readThread = null;

        if (recorder != null) {
            recorder.release();
            if (audioRecord == recorder) audioRecord = null;
        }

        synchronized (buffer) {
            byte[] pcm = buffer.toByteArray();
            Log.d(TAG, "录音结束, " + pcm.length + " bytes (~"
                    + (pcm.length / (SAMPLE_RATE * 2f)) + "s)");
            return pcm;
        }
    }

    public void release() {
        if (recording) {
            stop();
            return;
        }
        AudioRecord recorder = audioRecord;
        audioRecord = null;
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
        }
    }
}
