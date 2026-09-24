package com.qimu.guide.ui.dialogue;

import com.qimu.guide.model.DialogueMessage;
import com.qimu.guide.service.SubtitleTranscript;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure Java bubble projection; both live subtitles and snapshots use this path. */
public final class SubtitleTimeline {
    // SDK-only fallback: keep sentence fragments together, but never grow one bubble forever.
    static final long UNKNOWN_ROUND_GAP_MS = 10_000L;
    static final long UNKNOWN_ROUND_MAX_SPAN_MS = 45_000L;

    private final List<Object> events = new ArrayList<>();
    private final Map<Long, Integer> subtitlePositions = new HashMap<>();

    public void upsert(SubtitleTranscript.Entry entry) {
        Integer position = subtitlePositions.get(entry.id);
        if (position == null) {
            subtitlePositions.put(entry.id, events.size());
            events.add(entry);
        } else {
            events.set(position, entry);
        }
    }

    /** Photos, capture status and local errors are explicit visual boundaries. */
    public void append(DialogueMessage message) {
        events.add(message);
    }

    public List<DialogueMessage> snapshot() {
        List<Object> projected = new ArrayList<>();
        Map<String, Bubble> rounds = new HashMap<>();
        Bubble active = null;
        for (Object event : events) {
            if (event instanceof DialogueMessage) {
                projected.add(event);
                rounds.clear();
                active = null;
                continue;
            }
            SubtitleTranscript.Entry entry = (SubtitleTranscript.Entry) event;
            Bubble bubble;
            if (entry.roundId > 0) {
                String key = roundKey(entry);
                bubble = rounds.get(key);
                if (bubble == null) {
                    if (active != null && active.roundId == 0 && active.canJoinUnknown(entry)) {
                        bubble = active;
                        bubble.roundId = entry.roundId;
                    } else {
                        bubble = new Bubble(entry);
                        projected.add(bubble);
                    }
                    rounds.put(key, bubble);
                }
            } else if (active != null && active.canJoinUnknown(entry)) {
                bubble = active;
            } else {
                bubble = new Bubble(entry);
                projected.add(bubble);
            }
            bubble.entries.add(entry);
            // A late old-round final updates that bubble without changing the live anchor.
            if (projected.get(projected.size() - 1) == bubble) active = bubble;
        }

        List<DialogueMessage> result = new ArrayList<>(projected.size());
        for (Object item : projected) {
            result.add(item instanceof Bubble ? ((Bubble) item).message() : (DialogueMessage) item);
        }
        return result;
    }

    private static String roundKey(SubtitleTranscript.Entry entry) {
        return entry.generation + ":" + entry.boundary + ":" + entry.fromSelf + ":" + entry.roundId;
    }

    private static final class Bubble {
        final SubtitleTranscript.Entry first;
        final List<SubtitleTranscript.Entry> entries = new ArrayList<>();
        int roundId;

        Bubble(SubtitleTranscript.Entry first) {
            this.first = first;
            roundId = first.roundId;
        }

        boolean canJoinUnknown(SubtitleTranscript.Entry entry) {
            SubtitleTranscript.Entry last = entries.get(entries.size() - 1);
            return first.generation == entry.generation && first.boundary == entry.boundary
                    && first.fromSelf == entry.fromSelf
                    && entry.receivedElapsedMs - last.receivedElapsedMs <= UNKNOWN_ROUND_GAP_MS
                    && entry.receivedElapsedMs - first.receivedElapsedMs <= UNKNOWN_ROUND_MAX_SPAN_MS;
        }

        DialogueMessage message() {
            // Native sequences order late fragments within a round. Different sources have
            // independent sequence domains; keep their arrival order if only one channel saw each.
            boolean sameSource = true;
            for (SubtitleTranscript.Entry entry : entries) {
                if (entry.source != first.source) sameSource = false;
            }
            if (sameSource) {
                entries.sort(Comparator.comparingInt((SubtitleTranscript.Entry e) -> e.sequence)
                        .thenComparingLong(e -> e.id));
            }
            StringBuilder text = new StringBuilder();
            for (SubtitleTranscript.Entry entry : entries) text.append(entry.text);
            return new DialogueMessage(first.fromSelf
                    ? DialogueMessage.Type.VOICE : DialogueMessage.Type.AI_REPLY,
                    text.toString(), first.timestamp);
        }
    }
}
