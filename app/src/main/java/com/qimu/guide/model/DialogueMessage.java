package com.qimu.guide.model;

import java.io.File;

/**
 * 对话消息数据模型
 */
public class DialogueMessage {

    public enum Type {
        /** 用户拍照 */
        PHOTO,
        /** 用户语音 */
        VOICE,
        /** AI 回复 */
        AI_REPLY,
        /** 居中状态提示条（如拍照中/照片已收到） */
        STATUS_HINT
    }

    public enum Status {
        SENDING,
        SUCCESS,
        FAILED
    }

    private Type type;
    private Status status;
    private String text;
    private File imageFile;
    private byte[] audioData;
    private long timestamp;

    public DialogueMessage(Type type, String text, long timestamp) {
        this.type = type;
        this.text = text;
        this.timestamp = timestamp;
        this.status = Status.SUCCESS;
    }

    public DialogueMessage(Type type, File imageFile, long timestamp) {
        this.type = type;
        this.imageFile = imageFile;
        this.timestamp = timestamp;
        this.status = Status.SUCCESS;
    }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public File getImageFile() { return imageFile; }
    public void setImageFile(File imageFile) { this.imageFile = imageFile; }

    public byte[] getAudioData() { return audioData; }
    public void setAudioData(byte[] audioData) { this.audioData = audioData; }

    public long getTimestamp() { return timestamp; }
}
