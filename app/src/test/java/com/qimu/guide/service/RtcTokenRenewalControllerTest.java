package com.qimu.guide.service;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RtcTokenRenewalControllerTest {
    @Test public void duplicateExpiryCallbacksShareOneRequestWindow() {
        RtcTokenRenewalController controller = new RtcTokenRenewalController();
        long ticket = controller.begin();
        assertTrue(ticket >= 0);
        assertEquals(-1, controller.begin());
        controller.waitForExpiry(ticket);
        assertEquals(-1, controller.begin());
        assertFalse(controller.retryAtExpiry(ticket, 999, 1_000));
        assertTrue(controller.isCurrent(ticket));
    }

    @Test public void onlyOneExtraRetryWindowIsAllowedAfterExpiry() {
        RtcTokenRenewalController controller = new RtcTokenRenewalController();
        long ticket = controller.begin();
        controller.waitForExpiry(ticket);
        assertTrue(controller.retryAtExpiry(ticket, 1_000, 1_000));
        long retry = controller.begin();
        assertTrue(retry > ticket);
        controller.waitForExpiry(retry);
        assertFalse(controller.retryAtExpiry(retry, 1_010, 1_000));
        assertEquals(-1, controller.begin());
    }

    @Test public void rejoinInvalidatesLateResponseWithoutRestoringGraceBudget() {
        RtcTokenRenewalController controller = new RtcTokenRenewalController();
        long ticket = controller.begin();
        controller.waitForExpiry(ticket);
        assertTrue(controller.retryAtExpiry(ticket, 1_000, 1_000));
        long retry = controller.begin();
        controller.invalidate();
        assertFalse(controller.isCurrent(retry));
        long next = controller.begin();
        controller.waitForExpiry(next);
        assertFalse(controller.retryAtExpiry(next, 1_020, 1_000));
    }

    @Test public void newTokenInvalidatesOldResponseAndGetsFreshRetryBudget() {
        RtcTokenRenewalController controller = new RtcTokenRenewalController();
        long old = controller.begin();
        controller.waitForExpiry(old);
        controller.retryAtExpiry(old, 1_000, 1_000);
        controller.newCredentials();
        assertFalse(controller.isCurrent(old));
        controller.waitForExpiry(old);
        long next = controller.begin();
        assertTrue(next >= 0);
        controller.waitForExpiry(next);
        assertTrue(controller.retryAtExpiry(next, 2_000, 2_000));
    }

    @Test public void graceEndsAtOneHundredTwentySeconds() {
        RtcTokenRenewalController controller = new RtcTokenRenewalController();
        long ticket = controller.begin();
        controller.waitForExpiry(ticket);
        assertFalse(controller.retryAtExpiry(ticket, 1_121, 1_000));
        assertTrue(controller.retryAtExpiry(ticket, 1_120, 1_000));
    }

    @Test public void httpAttemptsAreBoundedAndCannotExceedProofGrace() {
        assertTrue(RtcTokenRenewalController.canRetryHttp(0, 900, 1_000));
        assertTrue(RtcTokenRenewalController.canRetryHttp(1, 1_120, 1_000));
        assertFalse(RtcTokenRenewalController.canRetryHttp(2, 900, 1_000));
        assertFalse(RtcTokenRenewalController.canRetryHttp(0, 1_121, 1_000));
    }

    @Test public void exitInvalidationRejectsDelayedExpiryRetry() {
        RtcTokenRenewalController controller = new RtcTokenRenewalController();
        long ticket = controller.begin();
        controller.waitForExpiry(ticket);
        controller.invalidate();
        assertFalse(controller.isCurrent(ticket));
        assertFalse(controller.retryAtExpiry(ticket, 1_000, 1_000));
    }
}
