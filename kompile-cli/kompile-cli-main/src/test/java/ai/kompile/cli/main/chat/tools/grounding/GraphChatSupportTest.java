package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.GraphForecastTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GraphChatSupportTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void omittedSelectorIsAnExactEngineOnlyPassThrough() throws Exception {
        ObjectNode params = mapper.createObjectNode().put("target", "Alice");
        ToolResult original = ToolResult.success("engine", "evidence", Map.of("confidence", 0.83));
        assertSame(original, GraphChatSupport.execute("graph_reason", params, context(), mapper, p -> {
            assertSame(params, p);
            return original;
        }));
        assertFalse(Files.exists(root.resolve(".kompile")));
    }

    @Test
    void allExplanationToolsExposePureExactModelPreview() throws Exception {
        configure("http://127.0.0.1:1/v1");
        String before = Files.readString(root.resolve(".kompile/chat-config.json"));
        List<CliTool> tools = List.of(new GraphReasonTool((String) null, mapper),
                new AskGraphExplainTool((String) null, mapper), new AskGraphFusedTool(null, mapper),
                new AskGraphSynthesizeTool((String) null, mapper),
                new GraphReasoningQueryTool((String) null, mapper), new GraphForecastTool(null, mapper));
        for (CliTool tool : tools) {
            JsonNode schema = tool.parameterSchema().path("properties").path("chatModel");
            assertEquals("object", schema.path("type").asText(), tool.id());
            assertTrue(schema.path("properties").has("modelId"), tool.id());
            assertTrue(schema.path("properties").has("thinking"), tool.id());
            ObjectNode params = request().put("atom", "worksFor(Alice, Acme)")
                    .put("query", "Who works for Acme?").put("question", "Who works for Acme?")
                    .put("root_type", "Revenue").put("operation", "SEARCH");
            ((ObjectNode) params.get("chatModel")).put("dryRun", true).put("thinking", "xhigh");
            ToolResult result = tool.execute(params, context());
            assertFalse(result.isError(), tool.id() + ": " + result.getOutput());
            assertEquals("exact-reasoning-model", chat(result).path("model").asText());
            assertEquals("xhigh", chat(result).path("thinking").asText());
            assertEquals("UNKNOWN", chat(result).path("readiness").asText());
            assertEquals("NOT_CHECKED", chat(result).path("authentication").asText());
            assertFalse(result.getOutput().contains("fixture-secret"));
        }
        assertFalse(Files.exists(root.resolve("data")));
        assertEquals(before, Files.readString(root.resolve(".kompile/chat-config.json")));
    }

    @Test
    void nativeInterpretationPreservesManagedGraphIdentityAndEvidence() throws Exception {
        try (ChatServer chatServer = new ChatServer(200, "Alice works for Acme [fact-17].")) {
            configure(chatServer.url());
            RestTemplate http = new RestTemplate();
            MockRestServiceServer engine = MockRestServiceServer.createServer(http);
            engine.expect(requestTo("http://graph/api/explain"))
                    .andExpect(request -> assertEquals(mapper.readTree(
                            "{\"target\":\"worksFor(Alice, Acme)\",\"factSheetId\":725,\"depth\":3}"),
                            mapper.readTree(((MockClientHttpRequest) request).getBodyAsString())))
                    .andRespond(withSuccess("""
                            {"verdict":"SUPPORTED","confidence":0.83,
                             "naturalLanguageSummary":"Alice works for Acme.",
                             "evidence":["fact-17 source:note-4"],"activatedRules":["rule-2"],
                             "derivationTreeJson":"fact-17 -> rule-2"}
                            """, MediaType.APPLICATION_JSON));
            GraphReasonTool tool = new GraphReasonTool(new GroundingBackendClient("http://graph", http), mapper);
            ObjectNode params = request().put("factSheetId", 725).put("depth", 3);
            ToolResult result = tool.execute(params, context());
            assertFalse(result.isError(), result.getOutput());
            assertEquals("SUPPORTED", result.getMetadata().get("verdict"));
            assertEquals(0.83, result.getMetadata().get("confidence"));
            assertEquals("COMPLETED", chat(result).path("status").asText());
            assertFalse(chat(result).path("verified").asBoolean(true));
            assertFalse(chat(result).path("graphWrites").asBoolean(true));
            assertTrue(result.getOutput().contains("fact-17"));
            assertTrue(result.getOutput().contains("unverified"));
            JsonNode sent = chatServer.request.get();
            assertEquals("exact-reasoning-model", sent.path("model").asText());
            assertFalse(sent.has("tools"));
            assertFalse(sent.has("tool_choice"));
            assertTrue(sent.path("messages").toString().contains("note-4"));
            assertTrue(sent.path("messages").toString().contains("rule-2"));
            assertFalse(sent.toString().contains("fixture-secret"));
            assertTrue(params.has("chatModel"), "input must not be mutated");
            engine.verify();
        }
    }

    @Test
    void selectorIsRemovedButLocalKnowledgeIdentityIsNotTranslated() throws Exception {
        try (ChatServer server = new ChatServer(200, "Evidence is limited [n1].")) {
            configure(server.url());
            ObjectNode params = request().put("knowledgeBase", "crawl-returned-id");
            ToolResult result = GraphChatSupport.execute("graph_reason", params, context(), mapper, p -> {
                assertFalse(p.has("chatModel"));
                assertEquals("crawl-returned-id", p.path("knowledgeBase").asText());
                assertFalse(p.has("factSheetId"));
                return ToolResult.success("local", "Evidence n1", Map.of("backend", "project-local"));
            });
            assertFalse(result.isError(), result.getOutput());
            assertEquals("project-local", result.getMetadata().get("backend"));
        }
    }

    @Test
    void engineFailureDoesNotCallModelOrConcealError() throws Exception {
        try (ChatServer server = new ChatServer(200, "must not run")) {
            configure(server.url());
            ToolResult failure = ToolResult.error("Graph not found");
            assertSame(failure, GraphChatSupport.execute("graph_reason", request(), context(), mapper, p -> failure));
            assertEquals(0, server.calls.get());
        }
    }

    @Test
    void providerFailureIsSanitizedAndPreservesEvidenceInsteadOfFalseSuccess() throws Exception {
        try (ChatServer server = new ChatServer(400, "fixture-secret echoed remote error")) {
            configure(server.url());
            ToolResult result = GraphChatSupport.execute("graph_reason", request(), context(), mapper,
                    p -> ToolResult.success("engine", "Original fact-17", Map.of("confidence", 0.2)));
            assertTrue(result.isError());
            assertTrue(result.getOutput().startsWith("Original fact-17"));
            assertEquals(0.2, result.getMetadata().get("confidence"));
            assertEquals("FAILED", chat(result).path("status").asText());
            assertFalse(chat(result).has("modelAnswer"));
            assertFalse(result.getOutput().contains("fixture-secret"));
            assertFalse(result.getMetadata().toString().contains("fixture-secret"));
        }
    }

    @Test
    void invalidSelectorsAndNonReasoningOperationsFailBeforeEngineAccess() throws Exception {
        for (String selector : List.of("null", "\"codex\"", "{\"modelId\":\"\"}",
                "{\"timeoutSeconds\":0}", "{\"timeoutSeconds\":1.5}", "{\"dryRun\":\"true\"}",
                "{\"apiKey\":\"fixture-secret\"}")) {
            ObjectNode params = mapper.createObjectNode().put("target", "Alice");
            params.set("chatModel", mapper.readTree(selector));
            ToolResult result = GraphChatSupport.execute("graph_reason", params, context(), mapper,
                    p -> fail("Invalid selector must not access the graph"));
            assertTrue(result.isError(), selector);
            assertFalse(result.getOutput().contains("fixture-secret"));
        }
        for (String operation : List.of("CAPABILITIES", "SCHEMA", "ASSETS", "ARTIFACT")) {
            ToolResult result = GraphChatSupport.execute("graph_reasoning_query",
                    request().put("operation", operation), context(), mapper,
                    p -> fail("Inspection must not call chat"));
            assertTrue(result.isError(), operation);
            assertTrue(result.getOutput().contains("not supported"));
        }
        assertFalse(Files.exists(root.resolve(".kompile")));
    }

    @Test
    void boundedModelContextDoesNotTruncateReturnedEngineEvidence() throws Exception {
        try (ChatServer server = new ChatServer(200, "Only an excerpt is available.")) {
            configure(server.url());
            String evidence = "x".repeat(70_000) + " ENGINE-END";
            ToolResult result = GraphChatSupport.execute("graph_reason", request(), context(), mapper,
                    p -> ToolResult.success("engine", evidence));
            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().startsWith(evidence));
            assertTrue(chat(result).path("evidenceTruncated").asBoolean());
            String sent = server.request.get().path("messages").toString();
            assertTrue(sent.length() < 66_000);
            assertFalse(sent.contains("ENGINE-END"));
            assertTrue(sent.contains("excerptOnly"));
        }
    }

    @Test
    void forecastAliasUsesNativeModelAndDoesNotReachNumericEngine() throws Exception {
        try (ChatServer server = new ChatServer(200, "This projection is an ESTIMATE.")) {
            configure(server.url());
            ObjectNode params = request().put("preferred_llm_provider", "custom").put("root_type", "Revenue");
            ToolResult result = GraphChatSupport.execute("graph_forecast", params, context(), mapper, p -> {
                assertFalse(p.has("preferred_llm_provider"));
                assertEquals("Revenue", p.path("root_type").asText());
                return ToolResult.success("forecast", "ESTIMATE: 125", Map.of("projection", List.of(125)));
            });
            assertFalse(result.isError(), result.getOutput());
            assertEquals(List.of(125), result.getMetadata().get("projection"));
            assertTrue(result.getOutput().startsWith("ESTIMATE: 125"));
            params.put("preferred_llm_provider", "claude");
            assertTrue(GraphChatSupport.execute("graph_forecast", params, context(), mapper,
                    p -> fail("Conflicting providers must not run")).isError());
            assertEquals(1, server.calls.get());
        }
    }

    private ObjectNode request() {
        ObjectNode params = mapper.createObjectNode().put("target", "worksFor(Alice, Acme)");
        params.putObject("chatModel").put("provider", "custom").put("modelId", "exact-reasoning-model");
        return params;
    }

    private JsonNode chat(ToolResult result) {
        return mapper.valueToTree(result.getMetadata()).path("chatModel");
    }

    private void configure(String url) throws Exception {
        Path config = Files.createDirectories(root.resolve(".kompile")).resolve("chat-config.json");
        mapper.writeValue(config.toFile(), Map.of("provider", "custom", "model", "configured-default",
                "baseUrl", url, "apiKey", "fixture-secret"));
    }

    private ToolContext context() {
        return new ToolContext("graph-chat-test", AgentConfig.builder("tester").enabledTools(Set.of("*")).build(),
                new PermissionService(), root, new ToolRegistry(mapper));
    }

    private final class ChatServer implements AutoCloseable {
        final AtomicReference<JsonNode> request = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        final HttpServer server;

        ChatServer(int status, String text) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    request.set(mapper.readTree(exchange.getRequestBody()));
                    calls.incrementAndGet();
                    String body = status == 200
                            ? "data: {\"choices\":[{\"delta\":{\"content\":" + mapper.writeValueAsString(text)
                                + "},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                            : mapper.writeValueAsString(Map.of("error", Map.of("message", text)));
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", status == 200 ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        @Override public void close() { server.stop(0); }
    }
}
