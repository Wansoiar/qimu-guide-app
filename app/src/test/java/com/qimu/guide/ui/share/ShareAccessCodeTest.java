package com.qimu.guide.ui.share;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ShareAccessCodeTest {

    @Test
    public void acceptsExactlyFourAsciiDigits() {
        assertTrue(ShareAccessCode.isValid("0123"));
        assertTrue(ShareAccessCode.isValid("9876"));
    }

    @Test
    public void rejectsOtherValues() {
        assertFalse(ShareAccessCode.isValid(null));
        assertFalse(ShareAccessCode.isValid("123"));
        assertFalse(ShareAccessCode.isValid("12345"));
        assertFalse(ShareAccessCode.isValid("12a4"));
        assertFalse(ShareAccessCode.isValid("１２３４"));
    }
}
