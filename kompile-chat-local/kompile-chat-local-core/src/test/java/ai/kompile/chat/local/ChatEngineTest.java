package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChatEngineTest {

    @TempDir
    Path tmpDir;

    // ── 1. Single tool round ─────────────────────────────────────────────────

    @Test
    void testSingleToolRound() throws Exception {
        // Build a real (tiny) graph and save it
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity("n1", "PERSON", "Alice");
        g.addEntity("n2", "ORG", "AcmeCorp");
        g.addRelation("e1", "n1", "n2", "WORKS_AT", 0.9);

        Path kgraph = tmpDir.resolve("test.kgraph");
        try (var session = ai.kompile.graph.reasoning.local.LocalReasoningSession.of(g)) {
            session.save(kgraph);
        }

        try (GraphToolBridge bridge = GraphToolBridge.open(kgraph)) {
            // Model: first call returns a tool call, second returns the answer
            ScriptedChatModel scripted = ScriptedChatModel.ofResponses(
                    toolCall("graph_reasoning_query", Map.of("operation", "OVERVIEW")),
                    ChatResponse.content("The graph has 2 entities and 1 relation.")
            );
            InferenceRouter router = new InferenceRouter(scripted, null);
            ChatEngine engine = new ChatEngine(router, bridge, 4);

            ChatEngine.TurnResult result = engine.chat(List.of(), "What's in the graph?", GenOptions.defaults());

            assertNotNull(result.answer());
            assertFalse(result.answer().isBlank());
            assertEquals(1, result.rounds().size(), "expected exactly 1 tool round");
            assertEquals("graph_reasoning_query", result.rounds().get(0).tool());
            assertNotNull(result.rounds().get(0).resultJson());
            assertFalse(result.exchanges().isEmpty());
            assertTrue(result.exchanges().get(0).requestJson().contains("\"tool_choice\":\"auto\""));
        }
    }

    // ── 2. Multi-round ───────────────────────────────────────────────────────

    @Test
    void testMultiRound() throws Exception {
        Path kgraph = buildAndSaveTinyGraph();
        try (GraphToolBridge bridge = GraphToolBridge.open(kgraph)) {
            ScriptedChatModel scripted = ScriptedChatModel.ofResponses(
                    toolCall("graph_reasoning_query", Map.of("operation", "OVERVIEW")),
                    toolCall("graph_reasoning_query", Map.of(
                            "operation", "SEARCH", "queryText", "Alice")),
                    ChatResponse.content("Alice works at AcmeCorp.")
            );
            InferenceRouter router = new InferenceRouter(scripted, null);
            ChatEngine engine = new ChatEngine(router, bridge, 4);

            ChatEngine.TurnResult result = engine.chat(List.of(), "Tell me about Alice", GenOptions.defaults());

            assertEquals(2, result.rounds().size(), "expected 2 tool rounds");
            assertEquals("Alice works at AcmeCorp.", result.answer());
        }
    }

    // ── 3. Malformed JSON corrective retry ───────────────────────────────────

    @Test
    void testMalformedJsonRetry() throws Exception {
        Path kgraph = buildAndSaveTinyGraph();
        try (GraphToolBridge bridge = GraphToolBridge.open(kgraph)) {
            // First response: malformed JSON that contains "tool" but is invalid
            // Second: valid tool call (retry)
            // Third: final answer
            ScriptedChatModel scripted = ScriptedChatModel.ofResponses(
                    protocolFailure("incomplete model-owned tool-call envelope"),
                    toolCall("graph_reasoning_query", Map.of("operation", "OVERVIEW")),
                    ChatResponse.content("Answer after retry.")
            );
            InferenceRouter router = new InferenceRouter(scripted, null);
            ChatEngine engine = new ChatEngine(router, bridge, 4);

            ChatEngine.TurnResult result = engine.chat(List.of(), "What tools exist?", GenOptions.defaults());

            assertEquals(1, result.rounds().size(), "retry should produce 1 tool round");
            assertEquals("Answer after retry.", result.answer());
        }
    }

    // ── 4. Max rounds cutoff ─────────────────────────────────────────────────

    @Test
    void testMaxRoundsCutoff() throws Exception {
        Path kgraph = buildAndSaveTinyGraph();
        try (GraphToolBridge bridge = GraphToolBridge.open(kgraph)) {
            // Always returns a tool call, plus a final synthesis answer
            ScriptedChatModel scripted = ScriptedChatModel.ofResponses(
                    toolCall("graph_reasoning_query", Map.of("operation", "OVERVIEW")),
                    toolCall("graph_reasoning_query", Map.of("operation", "OVERVIEW")),
                    ChatResponse.content("Synthesis answer.")
            );
            InferenceRouter router = new InferenceRouter(scripted, null);
            ChatEngine engine = new ChatEngine(router, bridge, 2);   // maxToolRounds=2

            ChatEngine.TurnResult result = engine.chat(List.of(), "Keep calling tools", GenOptions.defaults());

            assertEquals(2, result.rounds().size(), "should stop at maxToolRounds=2");
            assertEquals("Synthesis answer.", result.answer());
        }
    }

    @Test
    void testBlankAssistantAnswerIsRejected() throws Exception {
        Path kgraph = buildAndSaveTinyGraph();
        try (GraphToolBridge bridge = GraphToolBridge.open(kgraph)) {
            ChatEngine engine = new ChatEngine(
                    new InferenceRouter(ScriptedChatModel.of("   "), null), bridge, 4);

            ChatException failure = assertThrows(
                    ChatException.class,
                    () -> engine.chat(List.of(), "Say something", GenOptions.defaults()));

            assertEquals("The model returned no assistant text.", failure.getMessage());
        }
    }

    @Test
    void testRelevantRoutingKeepsOrdinaryChatOutOfToolTemplate() throws Exception {
        GraphToolBackend bridge = new GraphToolBackend() {
            @Override
            public String catalogJson() {
                throw new AssertionError("ordinary chat must not load the graph catalog");
            }

            @Override
            public String execute(String toolName, String argsJson) {
                throw new AssertionError("ordinary chat must not execute graph tools");
            }

            @Override
            public void close() {
            }
        };
        ChatEngine engine = new ChatEngine(
                new InferenceRouter(ScriptedChatModel.of("Hello back."), null),
                bridge,
                4,
                ChatEngine.ToolRouting.RELEVANT);

        ChatEngine.TurnResult result = engine.chat(List.of(), "Hello", GenOptions.defaults());

        assertEquals("Hello back.", result.answer());
        assertTrue(result.rounds().isEmpty());
        assertEquals(1, result.exchanges().size());
        assertTrue(result.exchanges().get(0).requestJson().contains("\"tools\":[]"));
        assertTrue(result.exchanges().get(0).requestJson().contains("\"tool_choice\":\"none\""));
    }

    @Test
    void testRelevantRoutingEnablesToolsForExplicitGraphQuestion() throws Exception {
        Path kgraph = buildAndSaveTinyGraph();
        try (GraphToolBridge bridge = GraphToolBridge.open(kgraph)) {
            ScriptedChatModel scripted = ScriptedChatModel.ofResponses(
                    toolCall("graph_reasoning_query", Map.of("operation", "OVERVIEW")),
                    ChatResponse.content("The graph contains Alice and AcmeCorp."));
            ChatEngine engine = new ChatEngine(
                    new InferenceRouter(scripted, null),
                    bridge,
                    4,
                    ChatEngine.ToolRouting.RELEVANT);

            ChatEngine.TurnResult result = engine.chat(
                    List.of(), "What is in the graph?", GenOptions.defaults());

            assertEquals(1, result.rounds().size());
            assertTrue(result.exchanges().get(0).requestJson().contains("\"tool_choice\":\"auto\""));
            assertFalse(result.exchanges().get(0).requestJson().contains("\"tools\":[]"));
        }
    }

    // ── 5. Router fallback ───────────────────────────────────────────────────

    @Test
    void testRouterFallback() throws Exception {
        ScriptedChatModel local = ScriptedChatModel.of("local answer").unavailable();
        ScriptedChatModel remote = ScriptedChatModel.of("remote answer");

        InferenceRouter router = new InferenceRouter(local, remote);

        assertEquals("REMOTE", router.activeRoute());
        assertFalse(router.isLocalActive());
        assertTrue(router.isRemoteActive());

        // Direct generate (bypassing ChatEngine to test router in isolation)
        String got = router.generate(List.of(Message.user("hello")), GenOptions.defaults());
        assertEquals("remote answer", got);
    }

    // ── 6. RemoteChatModel stub — 200 OK ────────────────────────────────────

    @Test
    void testRemoteChatModelStub() throws Exception {
        String responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\"," +
                "\"content\":\"Hello from stub\"}}]}";

        HttpServer server = startHttpServer(200, responseBody);
        try {
            int port = server.getAddress().getPort();
            RemoteChatModel model = new RemoteChatModel(
                    "http://localhost:" + port, "test-model", null, 10);

            String result = model.generate(
                    List.of(Message.user("Hi")), GenOptions.defaults());

            assertEquals("Hello from stub", result);
        } finally {
            server.stop(0);
        }
    }

    // ── 7. RemoteChatModel stub — 500 error ──────────────────────────────────

    @Test
    void testRemoteError500() throws Exception {
        HttpServer server = startHttpServer(500, "Internal Server Error");
        try {
            int port = server.getAddress().getPort();
            RemoteChatModel model = new RemoteChatModel(
                    "http://localhost:" + port, "test-model", null, 10);

            ChatException ex = assertThrows(ChatException.class,
                    () -> model.generate(List.of(Message.user("Hi")), GenOptions.defaults()));
            assertTrue(ex.getMessage().contains("500"),
                    "Expected 500 in error message, got: " + ex.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testRemoteUsesNativeToolsAndReturnsStructuredCall() throws Exception {
        String responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"graph_reasoning_query\","
                + "\"arguments\":\"{\\\"operation\\\":\\\"OVERVIEW\\\"}\"}}]}}]}";
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = startHttpServer(200, responseBody, requestBody);
        try {
            int port = server.getAddress().getPort();
            RemoteChatModel model = new RemoteChatModel(
                    "http://localhost:" + port, "test-model", null, 10);
            String tools = "[{\"name\":\"graph_reasoning_query\","
                    + "\"description\":\"Query graph\","
                    + "\"parameters\":{\"type\":\"object\"}}]";

            ChatResponse result = model.generate(
                    ChatRequest.of(List.of(Message.user("Overview")), tools),
                    GenOptions.defaults());

            assertTrue(result.isProtocolValid());
            assertEquals("graph_reasoning_query", result.toolCalls().get(0).name());
            Map<String, Object> sent = ai.kompile.graph.reasoning.unified.MiniJson
                    .parseObject(requestBody.get());
            assertTrue(sent.containsKey("tools"));
            assertEquals("auto", sent.get("tool_choice"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testStructuredResultTransportPreservesCalls() {
        String structured = "{\"rawText\":\"native-output\",\"content\":\"\","
                + "\"reasoningContent\":\"\",\"toolCalls\":[{\"id\":\"c1\","
                + "\"name\":\"bar\",\"arguments\":{\"k\":\"v\"}}],"
                + "\"protocolErrors\":[]}";
        ChatResponse result = ChatResponse.fromStructuredJson(structured);
        assertTrue(result.isProtocolValid());
        assertEquals("bar", result.toolCalls().get(0).name());
        assertEquals("v", result.toolCalls().get(0).arguments().get("k"));
    }

    @Test
    void testRawModelJsonIsNotAcceptedAsStructuredTransport() {
        assertThrows(ChatException.class, () ->
                ChatResponse.fromStructuredJson(
                        "{\"tool\":\"bar\",\"args\":{}}"));
    }

    @Test
    void testSecondProtocolFailureFailsCompleteTurn() throws Exception {
        Path kgraph = buildAndSaveTinyGraph();
        try (GraphToolBridge bridge = GraphToolBridge.open(kgraph)) {
            ScriptedChatModel scripted = ScriptedChatModel.ofResponses(
                    protocolFailure("first invalid call"),
                    protocolFailure("retry invalid call"));
            ChatEngine engine = new ChatEngine(
                    new InferenceRouter(scripted, null), bridge, 4);

            ChatException failure = assertThrows(ChatException.class,
                    () -> engine.chat(List.of(), "Use the graph", GenOptions.defaults()));
            assertTrue(failure.getMessage().contains("after retry"));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Path buildAndSaveTinyGraph() throws Exception {
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity("n1", "PERSON", "Alice");
        g.addEntity("n2", "ORG", "AcmeCorp");
        g.addRelation("e1", "n1", "n2", "WORKS_AT", 0.9);
        Path kgraph = tmpDir.resolve("tiny.kgraph");
        try (var session = ai.kompile.graph.reasoning.local.LocalReasoningSession.of(g)) {
            session.save(kgraph);
        }
        return kgraph;
    }

    private static ChatResponse toolCall(String name, Map<String, Object> arguments) {
        return ChatResponse.toolCalls("", List.of(
                new ChatToolCall("call-1", name, arguments)));
    }

    private static ChatResponse protocolFailure(String error) {
        return new ChatResponse("", "", "", List.of(), List.of(error));
    }

    /** Start a simple HTTP server on a random port. Caller must call server.stop(0). */
    private HttpServer startHttpServer(int statusCode, String body) throws Exception {
        return startHttpServer(statusCode, body, null);
    }

    private HttpServer startHttpServer(
            int statusCode, String body, AtomicReference<String> requestBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            if (requestBody != null) {
                requestBody.set(new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, resp.length);
            try (var os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        return server;
    }
}
