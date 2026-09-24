package com.qimu.guide.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Canonical final subtitles, shared by live delivery and view reconstruction. */
public final class SubtitleTranscript {
    static final long CROSS_CHANNEL_DEDUP_MS = 1_500L;

    public enum Source { SDK, BINARY }

    public static final class Entry {
        public final long id;
        public final int generation;
        public final int boundary;
        public final boolean fromSelf;
        public final String text;
        public final int sequence;
        public final int roundId;
        public final Source source;
        public final long receivedElapsedMs;
        public final long timestamp;

        private Entry(long id, int generation, int boundary, boolean fromSelf, String text,
                      int sequence, int roundId, Source source, long elapsedMs, long timestamp) {
            this.id = id;
            this.generation = generation;
            this.boundary = boundary;
            this.fromSelf = fromSelf;
            this.text = text;
            this.sequence = sequence;
            this.roundId = roundId;
            this.source = source;
            this.receivedElapsedMs = elapsedMs;
            this.timestamp = timestamp;
        }
    }

    private static final class Record {
        Entry entry;
        int sources;

        Record(Entry entry) {
            this.entry = entry;
            sources = sourceMask(entry.source);
        }
    }

    private final List<Record> records = new ArrayList<>();
    private final Map<String, Record> events = new HashMap<>();
    private long nextId;
    private int boundary;

    /** No interim text is displayed. A final event is immutable except for late round metadata. */
    public synchronized Entry record(int generation, boolean fromSelf, String text,
                                     boolean definite, int sequence, int roundId, Source source,
                                     long elapsedMs, long timestamp) {
        if (!definite || text == null || text.trim().isEmpty()) return null;
        String normalized = text.trim();
        int knownRound = Math.max(0, roundId);
        String eventKey = generation + ":" + boundary + ":" + fromSelf + ":"
                + source + ":" + knownRound + ":" + sequence;
        if (events.containsKey(eventKey)) return null;

        // Match one event from each channel, never two sequences from the same channel.
        // Include room lifetime, reconnect boundary and known round to preserve repeated words.
        for (int i = records.size() - 1; i >= 0; i--) {
            Record candidate = records.get(i);
            Entry prior = candidate.entry;
            if (elapsedMs - prior.receivedElapsedMs > CROSS_CHANNEL_DEDUP_MS) break;
            if (prior.generation != generation || prior.boundary != boundary
                    || prior.fromSelf != fromSelf || !prior.text.equals(normalized)
                    || (candidate.sources & sourceMask(source)) != 0
                    || (prior.roundId > 0 && knownRound > 0 && prior.roundId != knownRound)) {
                continue;
            }
            candidate.sources |= sourceMask(source);
            events.put(eventKey, candidate);
            // SDK has no roundId. If it arrived first, publish the enriched entry so the
            // same projection can move it out of a provisional bubble into the right round.
            int mergedRound = prior.roundId > 0 ? prior.roundId : knownRound;
            Source mergedSource = source == Source.BINARY ? source : prior.source;
            int mergedSequence = source == Source.BINARY ? sequence : prior.sequence;
            if (mergedRound == prior.roundId && mergedSource == prior.source
                    && mergedSequence == prior.sequence) return null;
            candidate.entry = new Entry(prior.id, generation, boundary, fromSelf, normalized,
                    mergedSequence, mergedRound, mergedSource, prior.receivedElapsedMs,
                    prior.timestamp);
            return candidate.entry;
        }

        Entry entry = new Entry(++nextId, generation, boundary, fromSelf, normalized,
                sequence, knownRound, source, elapsedMs, timestamp);
        Record record = new Record(entry);
        records.add(record);
        events.put(eventKey, record);
        return entry;
    }

    /** Reconnecting within an existing RTC instance must also start a new bubble. */
    public synchronized void breakGroup() {
        boundary++;
    }

    public synchronized List<Entry> snapshot() {
        List<Entry> result = new ArrayList<>(records.size());
        for (Record record : records) result.add(record.entry);
        return result;
    }

    public synchronized void clear() {
        records.clear();
        events.clear();
        boundary++;
    }

    private static int sourceMask(Source source) {
        return 1 << source.ordinal();
    }
}
