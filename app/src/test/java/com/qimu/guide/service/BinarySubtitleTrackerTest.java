package com.qimu.guide.service;

import com.qimu.guide.ui.dialogue.SubtitleTimeline;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class BinarySubtitleTrackerTest {
    @Test
    public void interimAndFinalShareSequenceButNextRoundCannotReuseIt() {
        BinarySubtitleTracker tracker = new BinarySubtitleTracker();
        int first = tracker.sequence(false, -1, 7, false);
        assertEquals(first, tracker.sequence(false, -1, 7, true));
        assertNotEquals(first, tracker.sequence(false, -1, 8, true));
    }

    @Test
    public void roundChangeWithoutOldFinalGetsANewSyntheticSequence() {
        BinarySubtitleTracker tracker = new BinarySubtitleTracker();
        int oldRound = tracker.sequence(false, -1, 7, false);
        assertNotEquals(oldRound, tracker.sequence(false, -1, 8, true));
    }

    @Test
    public void identifiedReplayIsSuppressedUntilReconnectReset() {
        BinarySubtitleTracker tracker = new BinarySubtitleTracker();
        assertTrue(tracker.rememberFinal("round7-seq1", true, 0));
        assertFalse(tracker.rememberFinal("round7-seq1", true, 60_000));
        tracker.reset();
        assertTrue(tracker.rememberFinal("round7-seq1", true, 60_001));
    }

    @Test
    public void packetWithoutRoundOrSequenceCanRepeatAfterBoundedReplayWindow() {
        BinarySubtitleTracker tracker = new BinarySubtitleTracker();
        assertTrue(tracker.rememberFinal("你好", false, 0));
        assertFalse(tracker.rememberFinal("你好", false, 100));
        assertTrue(tracker.rememberFinal("你好", false, 2_000));
    }

    @Test
    public void unidentifiedRawReplayAndLaterRepetitionReachCorrectBubbles() {
        BinarySubtitleTracker tracker = new BinarySubtitleTracker();
        SubtitleTranscript transcript = new SubtitleTranscript();
        SubtitleTimeline timeline = new SubtitleTimeline();
        receive(tracker, transcript, timeline, 0, -1, 0);
        receive(tracker, transcript, timeline, 0, -1, 100);
        receive(tracker, transcript, timeline, 0, -1, 11_000);
        assertEquals(2, transcript.snapshot().size());
        assertEquals(2, timeline.snapshot().size());
        assertEquals("你好。", timeline.snapshot().get(0).getText());
        assertEquals("你好。", timeline.snapshot().get(1).getText());
    }

    @Test
    public void identifiedNewRoundAndNewSequenceSurviveTrackerThroughProjection() {
        BinarySubtitleTracker tracker = new BinarySubtitleTracker();
        SubtitleTranscript transcript = new SubtitleTranscript();
        SubtitleTimeline timeline = new SubtitleTimeline();
        receive(tracker, transcript, timeline, 1, 1, 0);
        receive(tracker, transcript, timeline, 1, 2, 100);
        receive(tracker, transcript, timeline, 2, 1, 200);
        assertEquals(3, transcript.snapshot().size());
        assertEquals(2, timeline.snapshot().size());
        assertEquals("你好。你好。", timeline.snapshot().get(0).getText());
        assertEquals("你好。", timeline.snapshot().get(1).getText());
    }

    private static void receive(BinarySubtitleTracker tracker, SubtitleTranscript transcript,
                                SubtitleTimeline timeline, int round, int nativeSequence, long time) {
        String text = "你好。";
        String fingerprint = "agent:" + round + ":" + nativeSequence + ":" + text;
        if (!tracker.rememberFinal(fingerprint, round > 0 || nativeSequence >= 0, time)) return;
        int sequence = tracker.sequence(false, nativeSequence, round, true);
        SubtitleTranscript.Entry entry = transcript.record(
                1, false, text, true, sequence, round, SubtitleTranscript.Source.BINARY, time, time);
        if (entry != null) timeline.upsert(entry);
    }
}
