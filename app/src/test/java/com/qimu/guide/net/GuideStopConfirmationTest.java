package com.qimu.guide.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import static org.junit.Assert.*;

public final class GuideStopConfirmationTest {
    private static final String STOPPED = "{\"code\":0,\"message\":\"ok\",\"data\":{\"stopped\":true}}";

    @Test public void onlyExplicitStoppedWithSuccessfulEnvelopeAuthorizesCleanup() {
        assertTrue(GuideApiClient.parseRtcStopResult(200, STOPPED).ok);
        assertFalse(GuideApiClient.parseRtcStopResult(503, STOPPED).ok);
        assertFalse(GuideApiClient.parseRtcStopResult(200, STOPPED.replace("\"code\":0", "\"code\":502")).ok);
    }

    @Test public void pendingVendorStopNeverReportsSuccessOrShowsOkAsError() {
        GuideApiClient.RtcStopResult result = GuideApiClient.parseRtcStopResult(200,
                "{\"code\":0,\"message\":\"ok\",\"data\":{\"stopped\":false,\"pending\":true}}");
        assertFalse(result.ok);
        assertNull(result.serverMessage);
    }

    @Test public void missingAndNonBooleanAcknowledgementsRemainUnconfirmed() {
        for (String body : new String[]{"{\"code\":0}", "{\"code\":0,\"data\":{}}",
                "{\"code\":0,\"data\":null}", STOPPED.replace("true", "\"true\""),
                STOPPED.replace("true", "1"), STOPPED.replace("true", "null"),
                STOPPED.replace("\"code\":0", "\"code\":\"0\"")}) {
            assertFalse(body, GuideApiClient.parseRtcStopResult(200, body).ok);
        }
    }

    @Test public void malformedEmptyAndProxyResponsesRemainUnconfirmed() {
        for (String body : new String[]{"", "null", "[]", "{", "<html>gateway failure</html>"}) {
            assertFalse(body, GuideApiClient.parseRtcStopResult(200, body).ok);
        }
    }

    @Test public void backendFailureReasonIsPreservedForRetryFeedback() {
        GuideApiClient.RtcStopResult result = GuideApiClient.parseRtcStopResult(503,
                "{\"code\":502,\"message\":\" vendor unavailable \"}");
        assertFalse(result.ok);
        assertEquals("vendor unavailable", result.serverMessage);
    }

    @Test public void headersAndSessionAreCapturedBeforeTheActiveTourIsCleared() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Phone-Number", "fixture-phone");
        GuideApiClient.RtcStopRequest request = new GuideApiClient.RtcStopRequest(
                " room ", " task ", " tour ", headers);
        headers.clear();
        assertEquals("fixture-phone", request.headers.get("X-Phone-Number"));
        assertEquals("tour", request.sessionId);
        assertEquals("room", request.roomId);
        assertEquals("task", request.taskId);
    }

    @Test public void restartOrInitialCreateWithoutCompleteRtcIdentityStillEndsItsSession() {
        for (String[] identity : new String[][]{{null,null},{"room",null},{null,"task"},{"",""}}) {
            GuideApiClient.RtcStopRequest request = new GuideApiClient.RtcStopRequest(
                    identity[0], identity[1], "tour", new LinkedHashMap<>());
            Map<String, Object> fields = GuideApiClient.rtcStopFields(
                    request.roomId, request.taskId, request.sessionId, true);
            assertEquals(2, fields.size());
            assertEquals("tour", fields.get("session_id"));
            assertEquals(true, fields.get("end_session"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingSessionCannotBeTreatedAsNothingToStop() {
        new GuideApiClient.RtcStopRequest(null, null, " ", new LinkedHashMap<>());
    }

    @Test public void actualHttpRequestPreservesIdentityHeadersAndWholeCallDeadline() {
        long remainingBudgetMs = 19_500;
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            assertEquals("fixture-device", chain.request().header("X-Device-Id"));
            Buffer body = new Buffer();
            chain.request().body().writeTo(body);
            JsonObject json = new JsonParser().parse(body.readUtf8()).getAsJsonObject();
            assertEquals("tour", json.get("session_id").getAsString());
            assertTrue(json.get("end_session").getAsBoolean());
            assertFalse(json.has("room_id"));
            assertFalse(json.has("task_id"));
            assertEquals(TimeUnit.MILLISECONDS.toNanos(remainingBudgetMs), chain.call().timeout().timeoutNanos());
            assertEquals(remainingBudgetMs, chain.connectTimeoutMillis());
            assertEquals(remainingBudgetMs, chain.readTimeoutMillis());
            assertEquals(remainingBudgetMs, chain.writeTimeoutMillis());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(ResponseBody.create(STOPPED, MediaType.get("application/json"))).build();
        }).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Device-Id", "fixture-device");
        assertTrue(new GuideApiClient(http).stopRtcSessionWithResult(
                null, null, "tour", true, headers, remainingBudgetMs).ok);
    }

    @Test public void cancelledConfirmationCannotRegisterAnotherHttpAttempt() {
        GuideApiClient client = new GuideApiClient(new OkHttpClient.Builder().addInterceptor(chain -> {
            fail("cancelled confirmation must not reach transport");
            throw new AssertionError();
        }).build());
        client.cancelAll();
        assertFalse(client.stopRtcSessionWithResult(null, null, "tour", true,
                new LinkedHashMap<>(), 4000).ok);
    }

    @Test(timeout = 30_000) public void realHttpStopCanWaitFiveSecondsForVendorAcknowledgement() throws Exception {
        try (DelayedStopServer server = new DelayedStopServer(5_000)) {
            long started = System.nanoTime();
            GuideApiClient.RtcStopResult result = new GuideApiClient(server.client())
                    .stopRtcSessionWithResult(null, null, "tour", true,
                            new LinkedHashMap<>(), 20_000);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(result.ok);
            assertTrue("response must really be delayed", elapsed >= 5_000);
            assertTrue("response must fit shared budget", elapsed < 20_000);
        }
    }

    @Test(timeout = 10_000) public void realHttpCallIsCancelledWhenRemainingBudgetExpires() throws Exception {
        try (DelayedStopServer server = new DelayedStopServer(5_000)) {
            long started = System.nanoTime();
            GuideApiClient.RtcStopResult result = new GuideApiClient(server.client())
                    .stopRtcSessionWithResult(null, null, "tour", true,
                            new LinkedHashMap<>(), 400);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(server.requestReceived.await(1, TimeUnit.SECONDS));
            assertFalse(result.ok);
            assertTrue("call must not wait for the late success", elapsed < 3_000);
        }
    }

    /** A real loopback HTTP peer, with no dependency on production or MockWebServer. */
    private static final class DelayedStopServer implements AutoCloseable {
        final ServerSocket listener;
        final CountDownLatch requestReceived = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread worker;

        DelayedStopServer(long delayMs) throws Exception {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    BufferedReader input = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.UTF_8));
                    int length = 0;
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) {
                        if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                            length = Integer.parseInt(line.substring(15).trim());
                        }
                    }
                    for (int i = 0; i < length; i++) if (input.read() == -1) return;
                    requestReceived.countDown();
                    release.await(delayMs, TimeUnit.MILLISECONDS);
                    byte[] body = STOPPED.getBytes(StandardCharsets.UTF_8);
                    String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                            + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                } catch (Exception ignored) {
                    // The deadline test intentionally closes its client before this write.
                }
            }, "test-delayed-stop-http");
            worker.setDaemon(true);
            worker.start();
        }

        OkHttpClient client() {
            return new OkHttpClient.Builder().addInterceptor(chain -> chain.proceed(
                    chain.request().newBuilder().url("http://127.0.0.1:"
                            + listener.getLocalPort() + "/stop").build())).build();
        }

        @Override public void close() throws Exception {
            release.countDown();
            listener.close();
            worker.join(1_000);
        }
    }
}
