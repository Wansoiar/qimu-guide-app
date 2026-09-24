package com.qimu.guide.net;

import org.junit.Test;
import java.util.Map;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import static org.junit.Assert.*;

public final class GuideRtcContractTest {
    private GuideApiClient.RtcSessionInfo session(String session, String room, String task, String uid,
                                                  String token, long expiry) {
        return new GuideApiClient.RtcSessionInfo(session, "app", room, uid, token, task, "bot", false, expiry);
    }
    private GuideApiClient.RtcSessionInfo previous() { return session("tour", "room", "old-task", "user", "proof", 1_000); }

    @Test public void initialStartExplicitlyDisallowsRevivingEndedTour() {
        Map<String, Object> fields = GuideApiClient.rtcRequestFields(" tour ", null, false);
        assertEquals(2, fields.size());
        assertEquals("tour", fields.get("session_id"));
        assertEquals(true, fields.get("initial_only"));
    }

    @Test public void recoveryAlwaysCarriesPreviousTaskAndPossessionProof() {
        Map<String, Object> fields = GuideApiClient.rtcRequestFields("tour", previous(), false);
        assertEquals(5, fields.size());
        assertEquals("old-task", fields.get("previous_task_id"));
        assertEquals("room", fields.get("room_id"));
        assertEquals("user", fields.get("uid"));
        assertEquals("proof", fields.get("token"));
        assertFalse(fields.containsKey("task_id"));
        assertFalse(fields.containsKey("initial_only"));
    }

    @Test public void renewalKeepsCurrentTaskIdentityAndPossessionProof() {
        Map<String, Object> fields = GuideApiClient.rtcRequestFields("tour", previous(), true);
        assertEquals(5, fields.size());
        assertEquals("old-task", fields.get("task_id"));
        assertEquals("proof", fields.get("token"));
        assertFalse(fields.containsKey("previous_task_id"));
    }

    @Test(expected = IllegalArgumentException.class) public void renewalCannotFallBackToUnfencedCreate() {
        GuideApiClient.rtcRequestFields("tour", null, true);
    }

    @Test(expected = IllegalArgumentException.class) public void proofCannotBeUsedForDifferentTour() {
        GuideApiClient.rtcRequestFields("other-tour", previous(), false);
    }

    @Test public void onlyTransientHttpAndKnownBusinessErrorsRetry() {
        assertTrue(GuideApiClient.isRtcRetryable(200, 502));
        assertTrue(GuideApiClient.isRtcRetryable(409, 40911));
        assertTrue(GuideApiClient.isRtcRetryable(503, 50310));
        assertTrue(GuideApiClient.isRtcRetryable(0, -1));
        assertTrue(GuideApiClient.isRtcRetryable(408, -1));
        assertTrue(GuideApiClient.isRtcRetryable(429, -1));
        assertFalse(GuideApiClient.isRtcRetryable(200, 0));
        assertFalse(GuideApiClient.isRtcRetryable(200, 501));
        assertFalse(GuideApiClient.isRtcRetryable(404, -1));
        assertFalse(GuideApiClient.isRtcRetryable(422, -1));
    }

    @Test public void ownershipAndStateRejectionsAreTerminalEvenWithServerStatus() {
        for (int code : new int[]{40310, 40910, 40401}) {
            assertFalse(GuideApiClient.isRtcRetryable(200, code));
            assertFalse(GuideApiClient.isRtcRetryable(503, code));
            assertTrue(new GuideApiClient.RtcResult(null, 200, code).identityRejected());
        }
        assertFalse(GuideApiClient.isRtcRetryable(403, 502));
        assertFalse(GuideApiClient.isRtcRetryable(401, -1));
        assertFalse(new GuideApiClient.RtcResult(null, 409, 40911).identityRejected());
    }

    @Test public void renewalResponseMustKeepEveryIdentityField() {
        GuideApiClient.RtcSessionInfo prior = previous();
        assertTrue(GuideApiClient.isRtcResponseValid("tour", prior, true,
                session("tour", "room", "old-task", "user", "new-token", 2_000)));
        assertFalse(GuideApiClient.isRtcResponseValid("tour", prior, true,
                session("other-tour", "room", "old-task", "user", "new", 2_000)));
        assertFalse(GuideApiClient.isRtcResponseValid("tour", prior, true,
                session("tour", "other-room", "old-task", "user", "new", 2_000)));
        assertFalse(GuideApiClient.isRtcResponseValid("tour", prior, true,
                session("tour", "room", "other-task", "user", "new", 2_000)));
        assertFalse(GuideApiClient.isRtcResponseValid("tour", prior, true,
                session("tour", "room", "old-task", "other-user", "new", 2_000)));
    }

    @Test public void replacementMustNameNewTaskAndValidJoinData() {
        GuideApiClient.RtcSessionInfo prior = previous();
        assertTrue(GuideApiClient.isRtcResponseValid("tour", prior, false,
                session("tour", "new-room", "new-task", "new-user", "new", 2_000)));
        assertFalse(GuideApiClient.isRtcResponseValid("tour", prior, false, prior));
        assertFalse(GuideApiClient.isRtcResponseValid("tour", prior, false,
                session("tour", "new-room", "new-task", "new-user", "", 2_000)));
        assertFalse(GuideApiClient.isRtcResponseValid("tour", prior, false,
                session("tour", "new-room", "new-task", "new-user", "new", 0)));
    }

    @Test public void staleIdempotentResponseCannotStopTheTaskAlreadyAttached() {
        GuideApiClient.RtcSessionInfo attached = previous();
        assertFalse(GuideApiClient.shouldCleanUpRtcResponse(
                session("tour", "room", "old-task", "user", "renewed-token", 2_000), attached));
        assertTrue(GuideApiClient.shouldCleanUpRtcResponse(
                session("tour", "old-room", "orphan-task", "user", "old-token", 1_000), attached));
        assertTrue(GuideApiClient.shouldCleanUpRtcResponse(attached, null));
        assertFalse(GuideApiClient.shouldCleanUpRtcResponse(null, attached));
    }

    @Test public void exitAndObsoleteCleanupHaveDifferentStopPurposes() {
        assertEquals(true, GuideApiClient.rtcStopFields("room", "task", " tour ", true).get("end_session"));
        Map<String, Object> cleanup = GuideApiClient.rtcStopFields("room", "task", "tour", false);
        assertEquals(false, cleanup.get("end_session"));
        assertEquals("tour", cleanup.get("session_id"));
        assertEquals("task", cleanup.get("task_id"));
        assertEquals(4, cleanup.size());
    }

    @Test public void exitBeforeInitialResponseEndsBusinessSessionWithoutRtcIds() {
        Map<String, Object> fields = GuideApiClient.rtcStopFields(null, null, " tour ", true);
        assertEquals(2, fields.size());
        assertEquals("tour", fields.get("session_id"));
        assertEquals(true, fields.get("end_session"));
        assertFalse(fields.containsKey("room_id"));
        assertFalse(fields.containsKey("task_id"));
        assertEquals(fields, GuideApiClient.rtcStopFields("", "", "tour", true));
    }

    @Test(expected = IllegalArgumentException.class) public void orphanCleanupMustIdentifyItsTask() {
        GuideApiClient.rtcStopFields(null, null, "tour", false);
    }

    @Test(expected = IllegalArgumentException.class) public void stopRejectsPartialTaskIdentity() {
        GuideApiClient.rtcStopFields("room", null, "tour", true);
    }

    @Test public void cancellationRejectsQueuedRequestBeforeRegistration() {
        OkHttpClient http = new OkHttpClient();
        GuideApiClient client = new GuideApiClient(http);
        long epoch = client.rtcCallEpoch();
        client.cancelRtcCalls();
        Call delayed = http.newCall(new Request.Builder().url("http://127.0.0.1/unused").build());
        assertFalse(client.registerRtcCall(delayed, epoch));
        assertTrue(delayed.isCanceled());
    }

    @Test public void cancellationStopsRegisteredCallsButAllowsNewTourRequests() {
        OkHttpClient http = new OkHttpClient();
        GuideApiClient client = new GuideApiClient(http);
        Call first = http.newCall(new Request.Builder().url("http://127.0.0.1/unused").build());
        assertTrue(client.registerRtcCall(first, client.rtcCallEpoch()));
        client.cancelRtcCalls();
        assertTrue(first.isCanceled());
        Call next = http.newCall(first.request());
        assertTrue(client.registerRtcCall(next, client.rtcCallEpoch()));
        assertFalse(next.isCanceled());
        client.cancelAll();
        assertTrue(next.isCanceled());
    }

    @Test public void rtcLoggerNeverUsesHeadersOrBodies() {
        assertEquals(HttpLoggingInterceptor.Level.BASIC,
                ((HttpLoggingInterceptor) HttpLog.rtcLogger()).getLevel());
    }
}
