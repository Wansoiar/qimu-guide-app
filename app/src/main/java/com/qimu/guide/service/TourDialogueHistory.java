package com.qimu.guide.service;

import com.qimu.guide.model.DialogueMessage;

import java.util.Collections;
import java.util.List;

/** In-process history for one tour, independent of Activity/Fragment instances. */
public final class TourDialogueHistory {
    private String sessionId;
    private SubtitleTimeline timeline = new SubtitleTimeline();

    public synchronized void beginTour(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("Missing tour identity");
        if (id.equals(sessionId)) return;
        sessionId = id;
        timeline = new SubtitleTimeline();
    }

    public synchronized boolean append(String expectedSessionId, DialogueMessage message) {
        if (!matches(expectedSessionId) || message == null) return false;
        timeline.append(message);
        return true;
    }

    public synchronized void upsert(String expectedSessionId, SubtitleTranscript.Entry entry) {
        if (matches(expectedSessionId) && entry != null) timeline.upsert(entry);
    }

    public synchronized List<DialogueMessage> snapshot(String expectedSessionId) {
        return matches(expectedSessionId) ? timeline.snapshot() : Collections.emptyList();
    }

    public synchronized void clear() {
        sessionId = null;
        timeline = new SubtitleTimeline();
    }

    private boolean matches(String id) {
        return sessionId != null && sessionId.equals(id);
    }
}
