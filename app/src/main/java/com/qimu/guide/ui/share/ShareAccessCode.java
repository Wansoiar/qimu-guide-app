package com.qimu.guide.ui.share;

import androidx.annotation.Nullable;

/** Validation policy shared by the share setup UI and its unit tests. */
final class ShareAccessCode {

    private ShareAccessCode() {
    }

    static boolean isValid(@Nullable String value) {
        return value != null && value.matches("^[0-9]{4}$");
    }
}
