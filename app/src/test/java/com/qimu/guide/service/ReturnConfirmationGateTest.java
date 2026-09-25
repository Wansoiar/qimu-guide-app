package com.qimu.guide.service;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static com.qimu.guide.service.ReturnConfirmationGate.Resolution.*;

public final class ReturnConfirmationGateTest {
    @Test public void timeoutThenLateSuccessCannotReachCleanup() {
        ReturnConfirmationGate gate = new ReturnConfirmationGate();
        gate.begin(1, 0, 20_000);
        assertEquals(FAILED, gate.resolve(1, false, 20_000));
        assertFalse(gate.isPending(1));
        assertEquals(IGNORED, gate.resolve(1, true, 20_001));
    }

    @Test public void confirmedCleanupStartsOnceAndCannotBeOverriddenByTimeout() {
        ReturnConfirmationGate gate = new ReturnConfirmationGate();
        gate.begin(1, 0, 20_000);
        assertEquals(CONFIRMED, gate.resolve(1, true, 5_000));
        assertEquals(IGNORED, gate.resolve(1, false, 20_000));
        assertFalse(gate.isPending(1));
    }

    @Test public void retriedReturnRejectsBothSuccessAndFailureFromPreviousAttempt() {
        ReturnConfirmationGate gate = new ReturnConfirmationGate();
        gate.begin(1, 0, 20_000);
        gate.resolve(1, false, 20_000);
        gate.begin(2, 21_000, 20_000);
        assertFalse(gate.isPending(1));
        assertEquals(IGNORED, gate.resolve(1, true, 21_001));
        assertTrue(gate.isPending(2));
        assertEquals(CONFIRMED, gate.resolve(2, true, 26_000));
    }

    @Test public void racingTimeoutAndHttpResultHaveExactlyOneWinner() throws Exception {
        ReturnConfirmationGate gate = new ReturnConfirmationGate();
        gate.begin(1, 0, 20_000);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        Runnable resolve = () -> {
            try { start.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
            if (gate.resolve(1, true, 20_000) != IGNORED) winners.incrementAndGet();
        };
        Thread timeout = new Thread(resolve);
        Thread response = new Thread(resolve);
        timeout.start(); response.start(); start.countDown();
        timeout.join(); response.join();
        assertEquals(1, winners.get());
    }

    @Test public void fastRetriesUseRemainingBudgetWithoutExtendingTheDeadline() {
        ReturnConfirmationGate gate = new ReturnConfirmationGate();
        gate.begin(1, 1_000, 20_000);
        assertEquals(20_000, gate.remainingMs(1, 1_000));
        assertEquals(19_650, gate.remainingMs(1, 1_350));
        assertEquals(19_050, gate.remainingMs(1, 1_950));
        assertEquals(0, gate.remainingMs(1, 21_000));
        assertEquals(0, gate.remainingMs(1, 99_000));
    }

    @Test public void successDeliveredAfterDeadlineCannotBeatADelayedUiTimeout() {
        ReturnConfirmationGate gate = new ReturnConfirmationGate();
        gate.begin(1, 1_000, 20_000);
        assertEquals(FAILED, gate.resolve(1, true, 21_000));
        gate.begin(2, 30_000, 20_000);
        assertEquals(FAILED, gate.resolve(2, true, 50_500));
    }
}
