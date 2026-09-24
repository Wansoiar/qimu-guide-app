package com.qimu.guide.service;

import org.junit.Test;

import static com.qimu.guide.service.RtcRecoveryController.Action.*;
import static com.qimu.guide.service.RtcRecoveryController.Cause.*;
import static com.qimu.guide.service.RtcRecoveryController.Intent.*;
import static com.qimu.guide.service.RtcRecoveryController.Phase.ONLINE;
import static com.qimu.guide.service.RtcRecoveryController.Phase.REBUILDING;
import static com.qimu.guide.service.RtcRecoveryController.Phase.STOPPED;
import static com.qimu.guide.service.RtcRecoveryController.Phase.FAILED;
import static org.junit.Assert.*;

public final class RtcRecoveryControllerTest {
    private RtcRecoveryController interrupted(RtcRecoveryController.Intent intent) {
        RtcRecoveryController controller = new RtcRecoveryController();
        controller.start();
        assertTrue(controller.interrupt(NETWORK, intent, 1_000));
        return controller;
    }

    @Test public void sdkRecoveryInvalidatesDeadlineWithoutRejoinOrRebuild() {
        RtcRecoveryController controller = interrupted(LISTENING);
        long ticket = controller.ticket();
        assertEquals(NONE, controller.onDeadline(ticket, 20_999));
        controller.connected();
        assertEquals(ONLINE, controller.phase());
        assertEquals(NONE, controller.onDeadline(ticket, 21_000));
        assertFalse(controller.showIssue(21_000));
        assertEquals(LISTENING, controller.intent());
    }

    @Test public void sdkTimeoutRejoinsExistingBeforeAnyReplacement() {
        RtcRecoveryController controller = interrupted(LISTENING);
        long firstTicket = controller.ticket();
        assertEquals(REJOIN_EXISTING, controller.onDeadline(firstTicket, 21_000));
        assertEquals(NONE, controller.onDeadline(firstTicket, 22_000));
        long joinTicket = controller.ticket();
        controller.connected();
        assertEquals(NONE, controller.onDeadline(joinTicket, 41_000));
    }

    @Test public void onlyExistingJoinTimeoutCanRequestReplacement() {
        RtcRecoveryController controller = interrupted(READY);
        controller.onDeadline(controller.ticket(), 21_000);
        long ticket = controller.ticket();
        assertEquals(NONE, controller.onDeadline(ticket, 40_999));
        assertEquals(REBUILD_TASK, controller.onDeadline(ticket, 41_000));
        assertEquals(REBUILDING, controller.phase());
        assertEquals(NONE, controller.onDeadline(ticket, 50_000));
        assertEquals(NONE, controller.onDeadline(controller.ticket(), 100_000));
    }

    @Test public void repeatedInterruptionsKeepOriginalDeadlineAndUserIntent() {
        RtcRecoveryController controller = interrupted(LISTENING);
        long ticket = controller.ticket();
        assertFalse(controller.interrupt(AGENT, READY, 19_000));
        assertEquals(ticket, controller.ticket());
        assertEquals(21_000, controller.deadline());
        assertEquals(LISTENING, controller.intent());
        assertTrue(controller.showIssue(19_000));
    }

    @Test public void issueAppearsAtThreeSecondsAndKeepsOriginalThresholdAcrossStages() {
        RtcRecoveryController controller = interrupted(PAUSED);
        assertFalse(controller.showIssue(3_999));
        assertTrue(controller.showIssue(4_000));
        assertEquals(1, controller.issueDelay(3_999));
        controller.onDeadline(controller.ticket(), 21_000);
        assertEquals(0, controller.issueDelay(21_000));
        assertTrue(controller.showIssue(21_000));
    }

    @Test public void issueTextReflectsEvidenceAndHasNoSuccessMessage() {
        RtcRecoveryController controller = interrupted(PAUSED);
        assertEquals("网络连接暂时中断，正在恢复…", controller.issueMessage());
        controller.setCause(AGENT);
        assertEquals("AI 导览服务暂时未响应，正在恢复…", controller.issueMessage());
        controller.setCause(UNKNOWN);
        assertEquals("连接暂时中断，正在恢复…", controller.issueMessage());
        controller.connected();
        assertFalse(controller.showIssue(Long.MAX_VALUE));
    }

    @Test public void replacementJoinFailuresAreBoundedToThreeTasks() {
        RtcRecoveryController controller = interrupted(LISTENING);
        controller.onDeadline(controller.ticket(), 21_000);
        long now = 41_000;
        for (int i = 0; i < 3; i++) {
            assertEquals(REBUILD_TASK, controller.onDeadline(controller.ticket(), now));
            long rebuildTicket = controller.ticket();
            assertTrue(controller.rebuilt(rebuildTicket, now));
            assertFalse(controller.rebuilt(rebuildTicket, now + 1));
            now += 20_000;
        }
        assertEquals(FAIL, controller.onDeadline(controller.ticket(), now));
        assertEquals(FAILED, controller.phase());
        assertEquals(NONE, controller.onDeadline(controller.ticket(), now + 20_000));
    }

    @Test public void lateRebuildAfterStopCannotReviveTour() {
        RtcRecoveryController controller = interrupted(READY);
        controller.onDeadline(controller.ticket(), 21_000);
        controller.onDeadline(controller.ticket(), 41_000);
        long ticket = controller.ticket();
        controller.stop();
        assertFalse(controller.rebuilt(ticket, 42_000));
        controller.connected();
        assertEquals(STOPPED, controller.phase());
        assertEquals(NONE, controller.onDeadline(ticket, 100_000));
        assertFalse(controller.interrupt(NETWORK, LISTENING, 100_000));
    }

    @Test public void permanentFailureInvalidatesTimersAndConnectionSuccess() {
        RtcRecoveryController controller = interrupted(LISTENING);
        long ticket = controller.ticket();
        controller.fail();
        assertFalse(controller.isCurrent(ticket));
        assertEquals(NONE, controller.onDeadline(ticket, 50_000));
        controller.connected();
        assertEquals(FAILED, controller.phase());
    }

    @Test public void explicitRetryStartsWithExistingCredentials() {
        RtcRecoveryController controller = interrupted(PAUSED);
        controller.fail();
        controller.retryExisting(PAUSED, 50_000);
        assertEquals(RtcRecoveryController.Phase.REJOIN_EXISTING, controller.phase());
        assertEquals(70_000, controller.deadline());
        assertEquals(PAUSED, controller.intent());
    }

    @Test public void pausedTourNeverAutomaticallyStartsAudio() {
        RtcRecoveryController controller = interrupted(PAUSED);
        controller.connected();
        assertFalse(controller.shouldResumeAudio(true, true, false));
    }

    @Test public void listeningRequiresPermissionGlassesAndNoTransfer() {
        RtcRecoveryController controller = interrupted(LISTENING);
        assertTrue(controller.shouldResumeAudio(true, true, false));
        assertFalse(controller.shouldResumeAudio(false, true, false));
        assertFalse(controller.shouldResumeAudio(true, false, false));
        assertFalse(controller.shouldResumeAudio(true, true, true));
    }

    @Test public void userPauseDuringRecoveryWinsOverPriorListening() {
        RtcRecoveryController controller = interrupted(LISTENING);
        controller.setIntent(PAUSED);
        controller.interrupt(NETWORK, LISTENING, 2_000);
        controller.onDeadline(controller.ticket(), 21_000);
        controller.connected();
        assertEquals(PAUSED, controller.intent());
        assertFalse(controller.shouldResumeAudio(true, true, false));
    }

    @Test public void explicitResumeDuringRecoveryCanChangePausedIntent() {
        RtcRecoveryController controller = interrupted(PAUSED);
        controller.setIntent(LISTENING);
        controller.connected();
        assertTrue(controller.shouldResumeAudio(true, true, false));
    }
}
