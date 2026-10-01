package ai.kompile.app.mcp;

import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.mcp.ScopedMcpCapabilityService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives {@link McpSseController} over MockMvc against a real SDK 0.10.0 server to pin down how
 * the transport routes messages: every request gets its own stream carrying only its own
 * response, each server message goes to exactly one stream, and bad input is answered with a
 * status or an error response instead of leaving the client waiting.
 */
class McpStreamableHttpRoutingTest {

    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final long WAIT_MILLIS = 5_000;

    private final ObjectMapper mapper = new ObjectMapper();
    /** One permit per call of the blocking tool that has started. */
    private final Semaphore blockingCallsStarted = new Semaphore(0);
    private final CountDownLatch releaseBlockingCalls = new CountDownLatch(1);
    /** What the waiting roots tool's server request failed with. */
    private final BlockingQueue<RuntimeException> serverRequestFailures = new LinkedBlockingQueue<>();

    private SpringMvcSseServerTransport transport;
    private McpSyncServer server;
    private ScopedMcpCapabilityService scopedCapabilities;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ServerPortService ports = mock(ServerPortService.class);
        when(ports.getBaseUrl()).thenReturn("http://localhost:8081");
        transport = new SpringMvcSseServerTransport(mapper, ports::getBaseUrl, "/mcp/message", "/mcp/sse");
        server = McpServer.sync(transport)
                .serverInfo("routing-test", "1")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).logging().build())
                .tools(
                        tool("block", (exchange, arguments) -> {
                            blockingCallsStarted.release();
                            awaitRelease();
                            return text(String.valueOf(arguments.get("tag")));
                        }),
                        tool("quick", (exchange, arguments) -> text("quick:" + arguments.get("text"))),
                        tool("log", (exchange, arguments) -> {
                            exchange.loggingNotification(new McpSchema.LoggingMessageNotification(
                                    McpSchema.LoggingLevel.INFO, "test", "hello"));
                            return text("logged");
                        }),
                        tool("roots", (exchange, arguments) ->
                                text("roots:" + exchange.listRoots().roots().get(0).uri())),
                        tool("waitThenRoots", (exchange, arguments) -> {
                            blockingCallsStarted.release();
                            awaitRelease();
                            try {
                                return text("roots:" + exchange.listRoots().roots().get(0).uri());
                            } catch (RuntimeException e) {
                                serverRequestFailures.add(e);
                                throw e;
                            }
                        }),
                        tool("nothing", (exchange, arguments) -> null))
                .build();
        scopedCapabilities = new ScopedMcpCapabilityService(mapper, ports);
        mvc = MockMvcBuilders.standaloneSetup(new McpSseController(transport, scopedCapabilities)).build();
    }

    @AfterEach
    void tearDown() {
        releaseBlockingCalls.countDown();
        scopedCapabilities.close();
        server.close();
        transport.close();
    }

    @Test
    void initializeAlwaysStartsANewSession() throws Exception {
        String first = initialize();
        assertTrue(transport.hasSession(first));

        // Neither a live nor a stale session header may stop a client from starting over.
        for (String header : List.of(first, "no-such-session")) {
            MvcResult again = send(header, initializeRequest(1));
            again.getAsyncResult(WAIT_MILLIS);
            String next = again.getResponse().getHeader(SESSION_HEADER);
            assertNotNull(next);
            assertNotEquals(first, next);
        }
        assertEquals(3, transport.getSessionCount());
        assertTrue(transport.hasSession(first));
    }

    @Test
    void overlappingRequestsEachReceiveOnlyTheirOwnResponse() throws Exception {
        String session = initialize();
        MvcResult first = send(session, toolCall(2, "block", "{\"tag\":\"first\"}"));
        MvcResult second = send(session, toolCall(3, "block", "{\"tag\":\"second\"}"));
        assertTrue(blockingCallsStarted.tryAcquire(2, WAIT_MILLIS, TimeUnit.MILLISECONDS),
                "both tool calls must be in flight at the same time");
        // A slow tool must not look like a dead server: headers go out before the response.
        assertTrue(first.getResponse().isCommitted());
        assertTrue(second.getResponse().isCommitted());

        releaseBlockingCalls.countDown();
        first.getAsyncResult(WAIT_MILLIS);
        second.getAsyncResult(WAIT_MILLIS);

        JsonNode firstResponse = only(messages(first));
        assertEquals(2, firstResponse.path("id").asInt());
        assertEquals("first", resultText(firstResponse));
        JsonNode secondResponse = only(messages(second));
        assertEquals(3, secondResponse.path("id").asInt());
        assertEquals("second", resultText(secondResponse));
    }

    @Test
    void withoutAServerStreamANotificationRidesTheRequestStreamAheadOfTheResponse() throws Exception {
        String session = initialize();
        MvcResult call = send(session, toolCall(4, "log", "{}"));
        call.getAsyncResult(WAIT_MILLIS);

        List<JsonNode> messages = messages(call);
        assertEquals(2, messages.size(), call.getResponse().getContentAsString());
        assertEquals("notifications/message", messages.get(0).path("method").asText());
        assertEquals("hello", messages.get(0).path("params").path("data").asText());
        assertEquals(4, messages.get(1).path("id").asInt());
        assertEquals("logged", resultText(messages.get(1)));
    }

    @Test
    void serverMessagesGoToTheNewestServerStreamAndResponsesNeverDo() throws Exception {
        String session = initialize();
        MvcResult older = openServerStream(session);

        MvcResult firstCall = send(session, toolCall(5, "log", "{}"));
        firstCall.getAsyncResult(WAIT_MILLIS);
        assertEquals(5, only(messages(firstCall)).path("id").asInt());
        awaitMessage(older, isMethod("notifications/message"));

        MvcResult newer = openServerStream(session);
        MvcResult secondCall = send(session, toolCall(6, "log", "{}"));
        secondCall.getAsyncResult(WAIT_MILLIS);
        assertEquals(6, only(messages(secondCall)).path("id").asInt());
        awaitMessage(newer, isMethod("notifications/message"));

        List<JsonNode> onOlder = messages(older);
        assertEquals(1, onOlder.size(), "each server message goes to one stream only");
        assertTrue(onOlder.stream().noneMatch(message -> message.has("id")),
                "responses never use a server stream");
        assertEquals(1, messages(newer).size());
    }

    @Test
    void aClientResponseToAServerRequestIsAcceptedAndCompletesTheTool() throws Exception {
        String session = initialize();
        MvcResult call = send(session, toolCall(7, "roots", "{}"));
        JsonNode rootsRequest = awaitMessage(call, isMethod("roots/list"));
        assertTrue(rootsRequest.hasNonNull("id"), rootsRequest.toString());

        String reply = "{\"jsonrpc\":\"2.0\",\"id\":" + rootsRequest.get("id")
                + ",\"result\":{\"roots\":[{\"uri\":\"file:///workspace\",\"name\":\"workspace\"}]}}";
        assertEquals(202, send(session, reply).getResponse().getStatus());

        call.getAsyncResult(WAIT_MILLIS);
        List<JsonNode> messages = messages(call);
        JsonNode response = messages.get(messages.size() - 1);
        assertEquals(7, response.path("id").asInt());
        assertEquals("roots:file:///workspace", resultText(response));
    }

    @Test
    void aServerRequestWithNowhereToGoFailsAtOnce() throws Exception {
        String session = initialize();
        SseEmitter requestStream = transport.handleStreamableHttpMessage(
                session, toolCall(16, "waitThenRoots", "{}"));
        assertTrue(blockingCallsStarted.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        // The client hangs up on the only stream the roots request could use.
        requestStream.complete();

        releaseBlockingCalls.countDown();
        // Well inside the SDK's 10 s request timeout, which a dropped request would wait out.
        RuntimeException failure = serverRequestFailures.poll(WAIT_MILLIS, TimeUnit.MILLISECONDS);
        assertNotNull(failure, "a server request with no open stream must fail instead of waiting for a reply");
        assertTrue(String.valueOf(failure.getMessage()).contains("No open stream"), failure.toString());
    }

    @Test
    void unknownSessionsAre404AndAMissingSessionIs400() throws Exception {
        String streamable = initialize();
        String legacy = legacySessionId(openLegacyStream());

        // A session of the other transport is as unknown as one that never existed.
        for (String unknown : List.of("no-such-session", legacy)) {
            assertEquals(404, send(unknown, toolCall(8, "quick", "{}")).getResponse().getStatus());
            assertEquals(404, send(unknown, notification("notifications/initialized")).getResponse().getStatus());
            assertEquals(404, mvc.perform(MockMvcRequestBuilders.get("/mcp/sse")
                    .header(SESSION_HEADER, unknown)).andReturn().getResponse().getStatus());
        }
        assertEquals(404, postLegacy(streamable, toolCall(8, "quick", "{}")).getResponse().getStatus());
        assertEquals(404, postLegacy("no-such-session", toolCall(8, "quick", "{}")).getResponse().getStatus());

        assertEquals(400, send(null, toolCall(9, "quick", "{}")).getResponse().getStatus());
        assertEquals(400, send(" ", toolCall(9, "quick", "{}")).getResponse().getStatus());
    }

    @Test
    void reusingTheIdOfAnInFlightRequestIsRefused() throws Exception {
        String session = initialize();
        MvcResult inFlight = send(session, toolCall(10, "block", "{\"tag\":\"original\"}"));
        assertTrue(blockingCallsStarted.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS));

        assertEquals(409, send(session, toolCall(10, "quick", "{\"text\":\"imposter\"}")).getResponse().getStatus());

        // The string "10" is a different JSON-RPC id from the number 10.
        MvcResult stringId = send(session, toolCall("10", "quick", "{\"text\":\"string\"}"));
        stringId.getAsyncResult(WAIT_MILLIS);
        JsonNode stringResponse = only(messages(stringId));
        assertTrue(stringResponse.path("id").isTextual(), stringResponse.toString());
        assertEquals("quick:string", resultText(stringResponse));

        releaseBlockingCalls.countDown();
        inFlight.getAsyncResult(WAIT_MILLIS);
        assertEquals("original", resultText(only(messages(inFlight))));

        // Answered ids are free again.
        MvcResult reused = send(session, toolCall(10, "quick", "{\"text\":\"again\"}"));
        reused.getAsyncResult(WAIT_MILLIS);
        assertEquals("quick:again", resultText(only(messages(reused))));
    }

    @Test
    void sendingAResponseFreesItsIdBeforeTheHandlerCompletes() throws Exception {
        McpServerSession session = mock(McpServerSession.class);
        // Hold handler completion back: delivery, not completion, releases the response's id.
        when(session.handle(any(McpSchema.JSONRPCMessage.class))).thenReturn(Mono.never());
        when(session.closeGracefully()).thenReturn(Mono.empty());
        AtomicReference<McpServerTransport> outgoing = new AtomicReference<>();
        transport.setSessionFactory(sessionTransport -> {
            outgoing.set(sessionTransport);
            return session;
        });
        var connection = transport.createStreamableHttpConnection(initializeRequest(1));
        outgoing.get().sendMessage(new McpSchema.JSONRPCResponse("2.0", 1, Map.of(), null)).block();

        assertThrows(IllegalStateException.class, () -> connection.emitter().send("late"));
        assertNotNull(transport.handleStreamableHttpMessage(
                connection.sessionId(), toolCall(1, "quick", "{}")));
    }

    @Test
    void failedSdkShutdownStillEndsEverySessionStream() throws Exception {
        McpServerSession session = mock(McpServerSession.class);
        when(session.handle(any(McpSchema.JSONRPCMessage.class))).thenReturn(Mono.never());
        when(session.closeGracefully()).thenReturn(Mono.error(new IllegalStateException("shutdown failed")));
        transport.setSessionFactory(ignored -> session);
        var connection = transport.createStreamableHttpConnection(initializeRequest(1));
        SseEmitter common = transport.openStreamableHttpStream(connection.sessionId());

        transport.terminateSession(connection.sessionId());

        assertFalse(transport.hasSession(connection.sessionId()));
        assertThrows(IllegalStateException.class, () -> connection.emitter().send("late"));
        assertThrows(IllegalStateException.class, () -> common.send("late"));
    }

    @Test
    void initializeInsideToolArgumentsStaysInTheSession() throws Exception {
        String session = initialize();
        int sessions = transport.getSessionCount();

        MvcResult call = send(session,
                toolCall(11, "quick", "{\"text\":\"initialize\",\"method\":\"initialize\"}"));
        call.getAsyncResult(WAIT_MILLIS);

        assertNull(call.getResponse().getHeader(SESSION_HEADER));
        assertEquals(sessions, transport.getSessionCount());
        assertEquals("quick:initialize", resultText(only(messages(call))));
    }

    @Test
    void unparseableBatchAndShapelessMessagesAreRefusedAndTheSessionSurvives() throws Exception {
        String session = initialize();
        for (String body : List.of("{not json", "", "\"text\"", "[" + toolCall(12, "quick", "{}") + "]",
                "{\"jsonrpc\":\"2.0\"}")) {
            assertEquals(400, send(session, body).getResponse().getStatus(), body);
        }
        assertEquals(400, send(null, "{not json").getResponse().getStatus());

        MvcResult call = send(session, toolCall(13, "quick", "{\"text\":\"still here\"}"));
        call.getAsyncResult(WAIT_MILLIS);
        assertEquals("quick:still here", resultText(only(messages(call))));
    }

    @Test
    void anOversizedBodyIsRefusedAndTheSessionsSurvive() throws Exception {
        String session = initialize();
        String legacy = legacySessionId(openLegacyStream());
        String oversized = " ".repeat(McpSseController.MAX_REQUEST_BODY_BYTES + 1);

        assertEquals(413, send(session, oversized).getResponse().getStatus());
        assertEquals(413, postLegacy(legacy, oversized).getResponse().getStatus());

        assertTrue(transport.hasSession(legacy));
        MvcResult call = send(session, toolCall(21, "quick", "{\"text\":\"after\"}"));
        call.getAsyncResult(WAIT_MILLIS);
        assertEquals("quick:after", resultText(only(messages(call))));
    }

    @Test
    void aRequestThatEndsWithoutAResultIsAnsweredWithAnError() throws Exception {
        String session = initialize();
        MvcResult call = send(session, toolCall(14, "nothing", "{}"));
        call.getAsyncResult(WAIT_MILLIS);

        JsonNode response = only(messages(call));
        assertEquals(14, response.path("id").asInt());
        assertEquals(McpSchema.ErrorCodes.INTERNAL_ERROR, response.path("error").path("code").asInt());

        MvcResult again = send(session, toolCall(14, "quick", "{\"text\":\"after\"}"));
        again.getAsyncResult(WAIT_MILLIS);
        assertEquals("quick:after", resultText(only(messages(again))));
    }

    @Test
    void deletingASessionEndsItsStreams() throws Exception {
        String session = initialize();
        MvcResult serverStream = openServerStream(session);

        assertEquals(400, mvc.perform(MockMvcRequestBuilders.delete("/mcp/sse"))
                .andReturn().getResponse().getStatus());
        assertTrue(transport.hasSession(session));

        assertEquals(200, mvc.perform(MockMvcRequestBuilders.delete("/mcp/sse").header(SESSION_HEADER, session))
                .andReturn().getResponse().getStatus());
        serverStream.getAsyncResult(WAIT_MILLIS);

        assertFalse(transport.hasSession(session));
        assertEquals(404, send(session, toolCall(15, "quick", "{}")).getResponse().getStatus());
        assertEquals(404, mvc.perform(MockMvcRequestBuilders.delete("/mcp/sse").header(SESSION_HEADER, session))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void sseTransportSessionRoundTrip() throws Exception {
        MvcResult stream = openLegacyStream();
        String session = legacySessionId(stream);

        assertEquals(202, postLegacy(session, initializeRequest(1)).getResponse().getStatus());
        awaitMessage(stream, message -> message.path("id").asInt() == 1 && message.has("result"));
        assertEquals(202, postLegacy(session, notification("notifications/initialized")).getResponse().getStatus());
        assertEquals(202, postLegacy(session, toolCall(2, "quick", "{\"text\":\"sse\"}")).getResponse().getStatus());
        assertEquals("quick:sse", resultText(awaitMessage(stream, message -> message.path("id").asInt() == 2)));

        assertEquals(400, postLegacy(session, "{not json").getResponse().getStatus());
        assertEquals(400, postLegacy(session, "[" + toolCall(3, "quick", "{}") + "]").getResponse().getStatus());
        assertTrue(transport.hasSession(session), "bad input must not end the session");
    }

    @Test
    void anSseRequestBeforeInitializeIsAnsweredInsteadOfParked() throws Exception {
        MvcResult stream = openLegacyStream();
        String session = legacySessionId(stream);

        assertEquals(202, postLegacy(session, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\"}")
                .getResponse().getStatus());

        JsonNode error = awaitMessage(stream, message -> message.path("id").asInt() == 5);
        assertEquals(McpSchema.ErrorCodes.INVALID_REQUEST, error.path("error").path("code").asInt());
    }

    @Test
    void heartbeatsDropDeadServerStreamsAndReachLiveOnes() throws Exception {
        String session = initialize();
        SseEmitter live = transport.openStreamableHttpStream(session);
        SseEmitter dead = transport.openStreamableHttpStream(session);
        dead.complete();

        assertEquals(1, transport.sendHeartbeats());

        // The dead stream no longer counts against the per-session limit...
        for (int i = 1; i < SpringMvcSseServerTransport.MAX_COMMON_STREAMS; i++) {
            transport.openStreamableHttpStream(session);
        }
        assertEquals(SpringMvcSseServerTransport.MAX_COMMON_STREAMS, transport.sendHeartbeats());

        // ...and one stream past the limit ends the oldest.
        transport.openStreamableHttpStream(session);
        assertThrows(IllegalStateException.class, () -> live.send("late"));
        assertEquals(SpringMvcSseServerTransport.MAX_COMMON_STREAMS, transport.sendHeartbeats());

        // An in-flight request stream is kept alive as well, without a message added to it.
        MvcResult inFlight = send(session, toolCall(20, "block", "{\"tag\":\"kept\"}"));
        assertTrue(blockingCallsStarted.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        assertEquals(SpringMvcSseServerTransport.MAX_COMMON_STREAMS + 1, transport.sendHeartbeats());
        releaseBlockingCalls.countDown();
        inFlight.getAsyncResult(WAIT_MILLIS);
        assertTrue(inFlight.getResponse().getContentAsString().contains(":keepalive"));
        assertEquals("kept", resultText(only(messages(inFlight))));
    }

    @Test
    void aHeartbeatEndsAnSseSessionWhoseStreamIsGone() {
        transport.createConnection();
        SseEmitter gone = transport.createConnection();
        gone.complete();
        assertEquals(2, transport.getSessionCount());

        assertEquals(1, transport.sendHeartbeats());
        assertEquals(1, transport.getSessionCount(), "the SSE session without a stream is closed");
    }

    @Test
    void aServerStreamTheClientEndedFreesItsSlotAtOnce() throws Exception {
        String session = initialize();
        MvcResult oldest = openServerStream(session);
        MvcResult newest = oldest;
        for (int i = 1; i < SpringMvcSseServerTransport.MAX_COMMON_STREAMS; i++) {
            newest = openServerStream(session);
        }
        // The container ends the newest stream, as it does when the client goes away.
        newest.getRequest().getAsyncContext().complete();

        openServerStream(session);
        transport.sendHeartbeats();
        assertTrue(oldest.getResponse().getContentAsString().contains(":keepalive"),
                "a stream the client ended must not push out a live one");
    }

    @Test
    void aToolListChangeReachesHealthySessionsPastABrokenOne() throws Exception {
        String session = initialize();
        MvcResult serverStream = openServerStream(session);
        SseEmitter broken = transport.createConnection();
        broken.complete();
        assertEquals(2, transport.getSessionCount());

        server.addTool(tool("late", (exchange, arguments) -> text("late")));

        awaitMessage(serverStream, isMethod("notifications/tools/list_changed"));
        assertEquals(1, transport.getSessionCount(), "the unreachable SSE session is dropped");
        assertTrue(transport.hasSession(session));
    }

    @Test
    void aScopedCapabilityServesAStreamableHttpToolCall() throws Exception {
        ScopedMcpCapabilityService.Capability capability = scopedCapabilities.issue(
                new ScopedMcpCapabilityService.ScopedTool(
                        "agent_private_graph",
                        "bound graph",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("action", Map.of("type", "string")),
                                "additionalProperties", false),
                        arguments -> "scoped:" + arguments.get("action")),
                Duration.ofMinutes(5));
        String path = URI.create(capability.endpointUrl()).getPath();

        MvcResult init = send(path, null, initializeRequest(1));
        init.getAsyncResult(WAIT_MILLIS);
        String session = init.getResponse().getHeader(SESSION_HEADER);
        assertNotNull(session);
        assertTrue(only(messages(init)).has("result"));
        assertEquals(202, send(path, session, notification("notifications/initialized")).getResponse().getStatus());

        MvcResult call = send(path, session, toolCall(2, "agent_private_graph", "{\"action\":\"read\"}"));
        call.getAsyncResult(WAIT_MILLIS);
        assertEquals("scoped:read", resultText(only(messages(call))));

        // The bearer admits one client only.
        assertEquals(404, send(path, null, initializeRequest(3)).getResponse().getStatus());
    }

    @Test
    void statusReportsTheActiveSessions() throws Exception {
        initialize();
        MvcResult status = mvc.perform(MockMvcRequestBuilders.get("/mcp/status")).andReturn();

        assertEquals(200, status.getResponse().getStatus());
        JsonNode body = mapper.readTree(status.getResponse().getContentAsString());
        assertTrue(body.path("enabled").asBoolean(), body.toString());
        assertEquals(1, body.path("activeSessions").asInt());
    }

    /** Start a Streamable HTTP session and finish the handshake; returns the session id. */
    private String initialize() throws Exception {
        MvcResult init = send(null, initializeRequest(1));
        init.getAsyncResult(WAIT_MILLIS);
        String contentType = init.getResponse().getContentType();
        assertNotNull(contentType);
        assertTrue(contentType.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE), contentType);
        String sessionId = init.getResponse().getHeader(SESSION_HEADER);
        assertNotNull(sessionId, "initialize must name the new session");

        JsonNode response = only(messages(init));
        assertEquals(1, response.path("id").asInt());
        assertTrue(response.has("result"), response.toString());
        assertEquals(202, send(sessionId, notification("notifications/initialized")).getResponse().getStatus());
        return sessionId;
    }

    private MvcResult send(String sessionId, String body) throws Exception {
        return send("/mcp/sse", sessionId, body);
    }

    private MvcResult send(String path, String sessionId, String body) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(body);
        if (sessionId != null) {
            request.header(SESSION_HEADER, sessionId);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult openServerStream(String sessionId) throws Exception {
        MvcResult stream = mvc.perform(MockMvcRequestBuilders.get("/mcp/sse")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header(SESSION_HEADER, sessionId)).andReturn();
        assertEquals(200, stream.getResponse().getStatus());
        assertTrue(stream.getRequest().isAsyncStarted(), "a server stream stays open");
        assertTrue(stream.getResponse().isCommitted(), "a server stream sends its headers at once");
        return stream;
    }

    private MvcResult openLegacyStream() throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/mcp/sse").accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
    }

    private MvcResult postLegacy(String sessionId, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/mcp/message")
                .param("sessionId", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    private static String legacySessionId(MvcResult stream) throws Exception {
        String marker = "sessionId=";
        for (String line : stream.getResponse().getContentAsString().split("\n")) {
            if (line.startsWith("data:") && line.contains(marker)) {
                return line.substring(line.indexOf(marker) + marker.length()).trim();
            }
        }
        return fail("no endpoint event on the SSE stream: " + stream.getResponse().getContentAsString());
    }

    /**
     * The JSON-RPC messages of the events written to a stream so far, in order; comments are
     * skipped. MockMvc writes a response a byte at a time, so an event that another thread is
     * still writing is left out until the blank line that ends it arrives.
     */
    private List<JsonNode> messages(MvcResult stream) throws Exception {
        String content = stream.getResponse().getContentAsString();
        List<JsonNode> messages = new ArrayList<>();
        for (String line : content.substring(0, content.lastIndexOf("\n\n") + 1).split("\n")) {
            if (line.startsWith("data:{")) {
                messages.add(mapper.readTree(line.substring("data:".length())));
            }
        }
        return messages;
    }

    private JsonNode awaitMessage(MvcResult stream, Predicate<JsonNode> match) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        while (true) {
            for (JsonNode message : messages(stream)) {
                if (match.test(message)) {
                    return message;
                }
            }
            if (System.nanoTime() - deadline > 0) {
                return fail("no matching MCP message on the stream: " + stream.getResponse().getContentAsString());
            }
            Thread.sleep(10);
        }
    }

    private void awaitRelease() {
        try {
            if (!releaseBlockingCalls.await(WAIT_MILLIS * 2, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("The test never released the blocking tool");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode only(List<JsonNode> messages) {
        assertEquals(1, messages.size(), messages.toString());
        return messages.get(0);
    }

    private static Predicate<JsonNode> isMethod(String method) {
        return message -> method.equals(message.path("method").asText());
    }

    private static String resultText(JsonNode response) {
        return response.path("result").path("content").path(0).path("text").asText();
    }

    private static String initializeRequest(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-03-26\","
                + "\"capabilities\":{\"roots\":{\"listChanged\":true}},"
                + "\"clientInfo\":{\"name\":\"routing-test\",\"version\":\"1\"}}}";
    }

    private static String notification(String method) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\"}";
    }

    private String toolCall(Object id, String tool, String arguments) {
        JsonNode idNode = mapper.valueToTree(id);
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idNode + ",\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}}";
    }

    private static McpServerFeatures.SyncToolSpecification tool(
            String name,
            BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> call) {
        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(name, name + " tool", "{\"type\":\"object\",\"properties\":{}}"), call);
    }

    private static McpSchema.CallToolResult text(String value) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(value)), false);
    }
}
