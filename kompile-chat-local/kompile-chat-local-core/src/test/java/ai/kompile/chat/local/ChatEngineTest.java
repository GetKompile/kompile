package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

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
            ScriptedChatModel scripted = ScriptedChatModel.of(
                    "{\"tool\":\"graph_overview\",\"args\":{}}",
                    "The graph has 2 entities and 1 relation."
            );
            InferenceRouter router = new InferenceRouter(scripted, null);
            ChatEngine engine = new ChatEngine(router, bridge, 4);

            ChatEngine.TurnResult result = engine.chat(List.of(), "What's in the graph?", GenOptions.defaults());

            assertNotNull(result.answer());
            assertFalse(result.answer().isBlank());
            assertEquals(1, result.rounds().size(), "expected exactly 1 tool round");
            assertEquals("graph_overview", result.rounds().get(0).tool());
            assertNotNull(result.rounds().get(0).resultJson());
        }
    }

    // ── 2. Multi-round ───────────────────────────────────────────────────────

    @Test
    void testMultiRound() throws Exception {
        Path kgraph = buildAndSaveTinyGraph();
        try (GraphToolBridge bridge = GraphToolBridge.open(kgraph)) {
            ScriptedChatModel scripted = ScriptedChatModel.of(
                    "{\"tool\":\"graph_overview\",\"args\":{}}",
                    "{\"tool\":\"graph_search\",\"args\":{\"query\":\"Alice\"}}",
                    "Alice works at AcmeCorp."
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
            ScriptedChatModel scripted = ScriptedChatModel.of(
                    "{\"tool\":\"graph_overview\" MALFORMED",        // triggers retry
                    "{\"tool\":\"graph_overview\",\"args\":{}}",     // retry succeeds
                    "Answer after retry."                             // synthesis
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
            ScriptedChatModel scripted = ScriptedChatModel.of(
                    "{\"tool\":\"graph_overview\",\"args\":{}}",  // round 1
                    "{\"tool\":\"graph_overview\",\"args\":{}}",  // round 2 (max)
                    "Synthesis answer."                           // asked after max rounds
            );
            InferenceRouter router = new InferenceRouter(scripted, null);
            ChatEngine engine = new ChatEngine(router, bridge, 2);   // maxToolRounds=2

            ChatEngine.TurnResult result = engine.chat(List.of(), "Keep calling tools", GenOptions.defaults());

            assertEquals(2, result.rounds().size(), "should stop at maxToolRounds=2");
            assertEquals("Synthesis answer.", result.answer());
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

    // ── 8. ToolCallParser — fenced block ────────────────────────────────────

    @Test
    void testToolCallParserFencedBlock() {
        String input = "Some preamble\n```json\n{\"tool\":\"foo\",\"args\":{\"k\":\"v\"}}\n```\nMore text";
        var result = ToolCallParser.parse(input);
        assertTrue(result.isPresent(), "should parse fenced block");
        assertEquals("foo", result.get().tool());
        assertEquals("v", result.get().args().get("k"));
    }

    // ── 9. ToolCallParser — bare JSON ───────────────────────────────────────

    @Test
    void testToolCallParserBareJson() {
        var result = ToolCallParser.parse("{\"tool\":\"bar\",\"args\":{}}");
        assertTrue(result.isPresent(), "should parse bare JSON");
        assertEquals("bar", result.get().tool());
        assertTrue(result.get().args().isEmpty());
    }

    // ── 10. ToolCallParser — no match ───────────────────────────────────────

    @Test
    void testToolCallParserNoMatch() {
        var result = ToolCallParser.parse("Just a plain answer without any JSON.");
        assertFalse(result.isPresent(), "should not find a tool call in plain text");
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

    /** Start a simple HTTP server on a random port. Caller must call server.stop(0). */
    private HttpServer startHttpServer(int statusCode, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
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
