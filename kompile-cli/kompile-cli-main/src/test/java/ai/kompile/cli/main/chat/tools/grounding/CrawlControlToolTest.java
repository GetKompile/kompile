package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

class CrawlControlToolTest {
    @TempDir
    Path projectRoot;

    @Test
    void strictLocalJobIdRoutesToDurableTranscriptBeforeConfiguredManager() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String jobId = LocalCrawlJobRegistry.newJobId();
        LocalCrawlJobStore.initialize(projectRoot, jobId, "notes", mapper.createObjectNode());

        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        CrawlControlTool tool = new CrawlControlTool(
                new GroundingBackendClient("http://crawl", restTemplate), mapper);
        ToolContext context = context(mapper, "crawl_control");
        ObjectNode params = mapper.createObjectNode()
                .put("operation", "transcript")
                .put("jobId", jobId);

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains(jobId), result.getOutput());
        server.verify();
    }

    @Test
    void localStartPreservesTypedExternalSourceProperties() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path export = Files.createDirectories(projectRoot.resolve("confluence"));
        Files.writeString(export.resolve("local.html"),
                "<html><body>crawl-control-local-tern-marker</body></html>", StandardCharsets.UTF_8);
        CrawlControlTool tool = new CrawlControlTool((String) null, mapper);
        ObjectNode params = mapper.createObjectNode().put("operation", "start");
        ObjectNode body = params.putObject("body").put("async", false).put("factSheetId", 12);
        body.putArray("sources").addObject()
                .put("sourceType", "CONFLUENCE")
                .put("pathOrUrl", export.toString())
                .put("maxDocuments", 5)
                .putObject("properties").put("apiToken", "must-not-persist");

        ToolResult result = tool.execute(params, context(mapper, "crawl_control"));

        assertFalse(result.isError(), result.getOutput());
        assertTrue(Files.readString(projectRoot.resolve("data/crawls/kb-12/chunks.jsonl"))
                .contains("crawl-control-local-tern-marker"));
        String persisted = Files.readString(projectRoot.resolve("data/crawls/kb-12/mcp-request.json"));
        assertFalse(persisted.contains("must-not-persist"), persisted);
        assertTrue(persisted.contains("externalSourceType"), persisted);
    }

    @Test
    void adapterPreservesNativeContractsAndSourceOverridesWithoutMutatingInput() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode params = (ObjectNode) mapper.readTree("""
                {"operation":"start","async":false,"factSheetId":999,"body":{
                  "factSheetId":725,"config":{"runtimeConfig":{"graphExtractionParallelism":2},
                    "graphExtraction":{"llmProvider":"chat:codex","modelName":"exact-extractor"}},
                  "runtimeConfig":{"graphExtractionParallelism":3},
                  "pipelineRegistry":{"models":[{"id":"reader","source":"chat","provider":"claude","modelId":"exact-reader"}]},
                  "processingRoute":{"backends":[{"id":"native","type":"CHAT_MODEL","agentName":"codex","modelName":"exact-extractor"}]},
                  "modelRuntime":{"timeoutMinutes":2},"steps":["LOADING","MARKDOWN_EXTRACTION"],
                  "sources":[{"sourceType":"file","pathOrUrl":"/input/doc.txt","pipelineId":"remote",
                    "processor":{"type":"CHAT_MODEL"},"modelBindings":{"default":"reader"},
                    "properties":{"custom":"preserved"},"chunkerOptions":{"outputFormat":"markdown"}}]}}
                """);
        JsonNode before = params.deepCopy();
        ObjectNode converted = new LocalProjectCrawlBackend(mapper).controlRequest(params);
        assertEquals(before, params);
        assertEquals(725, converted.path("knowledgeBase").path("id").asInt());
        assertFalse(converted.has("config"));
        assertFalse(converted.has("sources"));
        assertFalse(converted.has("factSheetId"));
        assertFalse(converted.path("async").asBoolean(true));
        assertEquals(3, converted.path("runtimeConfig").path("graphExtractionParallelism").asInt());
        assertEquals("exact-extractor", converted.path("graphExtraction").path("modelName").asText());
        for (String field : Set.of("pipelineRegistry", "processingRoute", "modelRuntime", "steps")) {
            assertEquals(params.path("body").path(field), converted.path(field), field);
        }
        JsonNode source = converted.path("documents").get(0);
        assertEquals("FILE", source.path("sourceType").asText());
        assertEquals("/input/doc.txt", source.path("path").asText());
        assertFalse(source.has("pathOrUrl"));
        for (String field : Set.of("processor", "modelBindings", "properties", "chunkerOptions", "pipelineId")) {
            assertEquals(params.path("body").path("sources").get(0).path(field), source.path(field), field);
        }
        assertTrue(CrawlDocumentsTool.requiresLocalExecution(params.path("body")));
        assertTrue(CrawlDocumentsTool.requiresLocalExecution(converted));
    }

    @Test
    void explicitKnowledgeBaseAndDocumentsWinOverLegacyAliases() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode params = (ObjectNode) mapper.readTree("""
                {"operation":"start","body":{"knowledgeBase":{"name":"returned-crawl-id"},
                  "factSheetId":999,"documents":[{"path":"/chosen.txt"}],
                  "sources":[{"pathOrUrl":"/ignored.txt"}]}}
                """);
        ObjectNode converted = new LocalProjectCrawlBackend(mapper).controlRequest(params);
        assertEquals("returned-crawl-id", converted.path("knowledgeBase").path("name").asText());
        assertFalse(converted.path("knowledgeBase").has("id"));
        assertEquals("/chosen.txt", converted.path("documents").get(0).path("path").asText());
        assertFalse(converted.has("sources"));
    }

    @Test
    void preflightForcesPurePreviewEvenWhenBodyRequestsARealAsyncCrawl() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        configureChat(mapper, "http://127.0.0.1:1/v1");
        String originalConfig = Files.readString(projectRoot.resolve(".kompile/chat-config.json"));
        ObjectNode params = remoteVisionRequest(mapper);
        params.put("operation", " PREFLIGHT ");
        ((ObjectNode) params.get("body")).put("dryRun", false).put("async", true).put("waitForCompletion", true);
        RestTemplate http = new RestTemplate();
        MockRestServiceServer manager = MockRestServiceServer.createServer(http);
        ToolResult result = new CrawlControlTool(new GroundingBackendClient("http://crawl", http), mapper)
                .execute(params, context(mapper, "crawl_control"));
        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("CHAT_MODEL"), result.getOutput());
        assertTrue(result.getOutput().contains("exact-crawl-vision"), result.getOutput());
        assertFalse(result.getOutput().contains("fixture-secret"));
        assertFalse(Files.exists(projectRoot.resolve("data/crawls/remote-control")));
        assertEquals("DRY_RUN", result.getMetadata().get("status"));
        assertFalse(mapper.valueToTree(result.getMetadata().get("preview"))
                .path("persistentWrites").asBoolean(true));
        assertEquals(originalConfig, Files.readString(projectRoot.resolve(".kompile/chat-config.json")));
        ObjectNode preview = new LocalProjectCrawlBackend(mapper).controlRequest(params);
        assertTrue(preview.path("dryRun").asBoolean());
        assertFalse(preview.path("async").asBoolean(true));
        assertFalse(preview.path("waitForCompletion").asBoolean(true));
        manager.verify();
    }

    @Test
    void nativeVisionBindingRunsOnHostEvenWithConfiguredCrawlManager() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                captured.set(mapper.readTree(exchange.getRequestBody()));
                byte[] response = ("data: {\"choices\":[{\"delta\":{\"content\":\"native-control-heron-marker\"},"
                        + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            configureChat(mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            RestTemplate http = new RestTemplate();
            MockRestServiceServer manager = MockRestServiceServer.createServer(http);
            ToolResult result = new CrawlControlTool(new GroundingBackendClient("http://crawl", http), mapper)
                    .execute(remoteVisionRequest(mapper), context(mapper, "crawl_control"));
            assertFalse(result.isError(), result.getOutput());
            assertEquals("COMPLETED", result.getMetadata().get("status"));
            assertNotNull(captured.get());
            assertEquals("exact-crawl-vision", captured.get().path("model").asText());
            assertTrue(captured.get().path("messages").toString().contains("image_url"));
            Path crawl = projectRoot.resolve("data/crawls/remote-control");
            assertTrue(Files.readString(crawl.resolve("chunks.jsonl")).contains("native-control-heron-marker"));
            assertFalse(Files.readString(crawl.resolve("mcp-request.json")).contains("fixture-secret"));
            manager.verify();
        } finally { server.stop(0); }
    }

    @Test
    void ambiguousManagedStartFailureDoesNotReplayLocally() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path input = projectRoot.resolve("input.txt");
        Files.writeString(input, "must not be ingested after ambiguous managed start");
        RestTemplate http = new RestTemplate();
        MockRestServiceServer manager = MockRestServiceServer.createServer(http);
        manager.expect(requestTo("http://crawl/api/unified-crawl/start"))
                .andRespond(request -> { throw new java.io.IOException("simulated connection loss"); });
        ObjectNode params = mapper.createObjectNode().put("operation", "start");
        ObjectNode body = params.putObject("body").put("async", false);
        body.putArray("sources").addObject().put("pathOrUrl", input.toString()).put("sourceType", "FILE");
        ToolResult result = new CrawlControlTool(new GroundingBackendClient("http://crawl", http), mapper)
                .execute(params, context(mapper, "crawl_control"));
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("No local fallback"), result.getOutput());
        assertFalse(Files.exists(projectRoot.resolve("data")));
        assertFalse(Files.exists(projectRoot.resolve(".kompile")));
        manager.verify();
    }

    @Test
    void malformedSourceCollectionsDoNotBecomeDefaultFolderCrawls() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String body : new String[] {"{\"sources\":{}}", "{\"sources\":[null]}",
                "{\"documents\":\"file.txt\"}", "{\"config\":[]}"}) {
            ObjectNode params = mapper.createObjectNode().put("operation", "start");
            params.set("body", mapper.readTree(body));
            ToolResult result = new CrawlControlTool((String) null, mapper)
                    .execute(params, context(mapper, "crawl_control"));
            assertTrue(result.isError(), body);
        }
        assertFalse(Files.exists(projectRoot.resolve("data")));
    }

    private void configureChat(ObjectMapper mapper, String url) throws Exception {
        Path config = Files.createDirectories(projectRoot.resolve(".kompile")).resolve("chat-config.json");
        mapper.writeValue(config.toFile(), Map.of("provider", "custom", "model", "configured-default",
                "baseUrl", url, "apiKey", "fixture-secret"));
    }

    private ObjectNode remoteVisionRequest(ObjectMapper mapper) throws Exception {
        Path imagePath = projectRoot.resolve("remote.png");
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        try { assertTrue(ImageIO.write(image, "png", imagePath.toFile())); }
        finally { image.flush(); }
        ObjectNode params = mapper.createObjectNode().put("operation", "start");
        ObjectNode body = params.putObject("body").put("async", false);
        body.putObject("knowledgeBase").put("name", "remote-control");
        body.putArray("sources").addObject().put("sourceType", "FILE")
                .put("pathOrUrl", imagePath.toString()).put("pipelineId", "vision");
        ObjectNode pipeline = body.putArray("pipelines").addObject()
                .put("pipelineId", "vision").put("pipelineType", "VLM");
        pipeline.putObject("modelBindings").put("default", "exact-crawl-vision");
        pipeline.putObject("processor").put("type", "CHAT_MODEL").put("provider", "custom");
        return params;
    }

    private ToolContext context(ObjectMapper mapper, String tool) {
        PermissionService permissions = new PermissionService();
        AgentConfig agent = AgentConfig.builder("tester").enabledTools(Set.of(tool)).build();
        return new ToolContext("local-routing-test", agent, permissions, projectRoot,
                new ToolRegistry(mapper));
    }
}
