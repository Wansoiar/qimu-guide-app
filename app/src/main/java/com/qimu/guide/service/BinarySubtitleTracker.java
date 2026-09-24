package com.qimu.guide.service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Identity fallback for VoiceChat subv packets without native sequences. */
final class BinarySubtitleTracker {
    private static final int MAX_FINAL_FINGERPRINTS = 256;
    private static final long UNIDENTIFIED_REPLAY_WINDOW_MS = 1_500L;
    private final Map<String, Long> finals = new LinkedHashMap<>();
    private final int[] activeSequence = {-1, -1};
    private final int[] activeRound = {0, 0};
    private int nextSequence = 1_000_000;

    synchronized int sequence(boolean fromSelf, int explicit, int roundId, boolean definite) {
        int speaker = fromSelf ? 0 : 1;
        int sequence = explicit;
        if (sequence < 0) {
            sequence = activeSequence[speaker];
            if (sequence < 0 || (roundId > 0 && activeRound[speaker] > 0
                    && activeRound[speaker] != roundId)) sequence = nextSequence++;
        }
        activeSequence[speaker] = definite ? -1 : sequence;
        activeRound[speaker] = definite ? 0 : roundId;
        return sequence;
    }

    synchronized boolean rememberFinal(String fingerprint, boolean identified, long elapsedMs) {
        Long previous = finals.get(fingerprint);
        if (previous != null && (identified
                || elapsedMs - previous <= UNIDENTIFIED_REPLAY_WINDOW_MS)) return false;
        // A packet with neither round nor sequence cannot be distinguished from a later
        // genuine repetition. Bound its replay suppression instead of hiding it forever.
        finals.put(fingerprint, elapsedMs);
        if (finals.size() > MAX_FINAL_FINGERPRINTS) {
            Iterator<String> iterator = finals.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    synchronized void reset() {
        nextSequence = 1_000_000;
        activeSequence[0] = activeSequence[1] = -1;
        activeRound[0] = activeRound[1] = 0;
        finals.clear();
    }
}
