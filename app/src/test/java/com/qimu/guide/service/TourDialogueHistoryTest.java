package com.qimu.guide.service;

import com.qimu.guide.model.DialogueMessage;

import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TourDialogueHistoryTest {
    private final TourDialogueHistory history = new TourDialogueHistory();
    private final SubtitleTranscript transcript = new SubtitleTranscript();

    @Test public void returningToSameTourKeepsPhotosBetweenSubtitles() {
        history.beginTour("first");
        SubtitleTranscript.Entry before = entry("Before photo", 1, 100);
        history.upsert("first", before);
        DialogueMessage photo = photo();
        history.append("first", photo);
        history.upsert("first", entry("After photo", 2, 200));

        history.beginTour("first");
        history.upsert("first", before); // Replayed metadata must not append or move a bubble.
        List<DialogueMessage> restored = history.snapshot("first");
        assertEquals(3, restored.size());
        assertEquals("Before photo", restored.get(0).getText());
        assertSame(photo, restored.get(1));
        assertEquals("After photo", restored.get(2).getText());
        restored.clear();
        assertEquals(3, history.snapshot("first").size());
    }

    @Test public void newTourRejectsPreviousPhotoAndSubtitleCallbacks() {
        history.beginTour("first");
        history.append("first", photo());
        history.beginTour("second");
        assertFalse(history.append("first", photo()));
        history.upsert("first", entry("Late old answer", 1, 100));
        assertTrue(history.snapshot("first").isEmpty());
        assertTrue(history.snapshot("second").isEmpty());
        assertTrue(history.append("second", photo()));
        assertEquals(1, history.snapshot("second").size());
    }

    @Test public void endingTourClearsHistoryAndRejectsLateCallbacks() {
        history.beginTour("first");
        history.append("first", photo());
        history.clear();
        assertFalse(history.append("first", photo()));
        history.upsert("first", entry("Late answer", 1, 100));
        assertTrue(history.snapshot("first").isEmpty());
        history.beginTour("second");
        assertTrue(history.snapshot("second").isEmpty());
    }

    private SubtitleTranscript.Entry entry(String text, int sequence, long time) {
        return transcript.record(1, false, text, true, sequence, 7,
                SubtitleTranscript.Source.BINARY, time, time);
    }

    private DialogueMessage photo() {
        return new DialogueMessage(DialogueMessage.Type.PHOTO, new File("test-photo.jpg"), 150);
    }
}
