package com.qimu.guide.service;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Reject a missing/truncated JPEG before displaying it or uploading it to the guide. */
public final class CapturedJpeg {
    private CapturedJpeg() { }

    // This checks the transfer envelope, not the integrity of every compressed scan.
    public static boolean hasCompleteEnvelope(File file) {
        if (file == null || !file.isFile()) return false;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() < 4 || input.readUnsignedShort() != 0xffd8) return false;
            input.seek(input.length() - 2);
            return input.readUnsignedShort() == 0xffd9;
        } catch (IOException e) {
            return false;
        }
    }
}
