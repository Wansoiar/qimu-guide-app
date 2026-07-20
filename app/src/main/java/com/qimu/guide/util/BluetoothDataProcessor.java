package com.qimu.guide.util;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;

/** 蓝牙数据处理工具，用于 sendAiDialogue 时处理文本长度限制 */
public class BluetoothDataProcessor {
    private static final int MAX_SIZE_KB = 2 * 1024;
    private static final String ELLIPSIS = " [...]";
    private static final byte[] ELLIPSIS_BYTES = ELLIPSIS.getBytes(StandardCharsets.UTF_8);

    public static ProcessResult processDataForBluetooth(ByteString originalData) {
        int originalSize = originalData.size();
        if (originalSize <= MAX_SIZE_KB) {
            return new ProcessResult(originalData, originalSize, false, originalSize, "OK");
        }
        ByteString truncatedData = truncateWithEllipsis(originalData);
        return new ProcessResult(truncatedData, truncatedData.size(), true, originalSize, "Truncated");
    }

    private static ByteString truncateWithEllipsis(ByteString data) {
        try {
            String text = data.toStringUtf8();
            int availableBytes = MAX_SIZE_KB - ELLIPSIS_BYTES.length;
            String truncatedText = truncateStringByBytes(text, availableBytes);
            return ByteString.copyFromUtf8(truncatedText + ELLIPSIS);
        } catch (Exception e) {
            int truncateLength = MAX_SIZE_KB - ELLIPSIS_BYTES.length;
            return data.substring(0, truncateLength).concat(ByteString.copyFrom(ELLIPSIS_BYTES));
        }
    }

    private static String truncateStringByBytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;
        int truncateLength = maxBytes;
        while (truncateLength > 0 && (bytes[truncateLength] & 0xC0) == 0x80) {
            truncateLength--;
        }
        if (truncateLength == 0) truncateLength = maxBytes - 1;
        return new String(bytes, 0, truncateLength, StandardCharsets.UTF_8);
    }

    public static class ProcessResult {
        public final ByteString processedData;
        public final int processedSize;
        public final boolean wasTruncated;
        public final int originalSize;
        public final String message;

        public ProcessResult(ByteString processedData, int processedSize, boolean wasTruncated, int originalSize, String message) {
            this.processedData = processedData;
            this.processedSize = processedSize;
            this.wasTruncated = wasTruncated;
            this.originalSize = originalSize;
            this.message = message;
        }
    }
}
