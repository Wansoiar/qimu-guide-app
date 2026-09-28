package com.qimu.guide.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

public class CapturedJpegTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void acceptsCompleteEnvelope() throws Exception {
        assertTrue(CapturedJpeg.hasCompleteEnvelope(write(255, 216, 1, 2, 255, 217)));
    }

    @Test public void rejectsTruncatedPhotoEvenWhenHeaderExists() throws Exception {
        assertFalse(CapturedJpeg.hasCompleteEnvelope(write(255, 216, 1, 2)));
        assertFalse(CapturedJpeg.hasCompleteEnvelope(write(255, 216, 1, 255)));
    }

    @Test public void rejectsMissingOrNonJpegData() throws Exception {
        assertFalse(CapturedJpeg.hasCompleteEnvelope(null));
        assertFalse(CapturedJpeg.hasCompleteEnvelope(new File(temporary.getRoot(), "missing")));
        assertFalse(CapturedJpeg.hasCompleteEnvelope(temporary.getRoot()));
        assertFalse(CapturedJpeg.hasCompleteEnvelope(write()));
        assertFalse(CapturedJpeg.hasCompleteEnvelope(write(1, 2, 255, 217)));
    }

    private File write(int... values) throws Exception {
        File file = temporary.newFile();
        try (FileOutputStream out = new FileOutputStream(file)) {
            for (int value : values) out.write(value);
        }
        return file;
    }
}
