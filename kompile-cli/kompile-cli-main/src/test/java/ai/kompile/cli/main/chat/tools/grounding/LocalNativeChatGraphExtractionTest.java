/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.KnowledgeGraphTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackendType;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LocalNativeChatGraphExtractionTest {
    private static final String GRAPH = """
            {"$schema":"kompile-graph-extraction/v1","entities":[
              {"id":"acme","name":"Acme","type":"ORGANIZATION","description":"Buyer","confidence":0.95},
              {"id":"initech","name":"Initech","type":"ORGANIZATION","description":"Target","confidence":0.92}
            ],"relations":[
              {"source":"acme","target":"initech","type":"ACQUIRED","description":"Acquisition","confidence":0.9}
            ]}
            """;
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private ToolContext context;

    @BeforeEach
    void context() {
        PermissionService permissions = new PermissionService();
        for (String tool : List.of("crawl_documents", "crawl_source", "crawl_discover", "knowledge_graph", "external_directory")) {
            permissions.setUserOverride(tool, PermissionService.PermissionLevel.ALLOW);
        }
        context = new ToolContext("native-graph-test", AgentConfig.builder("tester").enabledTools(Set.of("*")).build(),
                permissions, root, new ToolRegistry(mapper));
    }

    @Test
    void explicitPrefixIsNativeButBareProviderAliasesRemainCli() throws Exception {
        LocalProjectGraphBackend backend = new LocalProjectGraphBackend(mapper);
        for (String provider : List.of("codex", "claude")) {
            ProcessingRouteConfig nativeRoute = backend.configuredProcessingRoute(mapper.createObjectNode(),
                    GraphExtractionConfig.builder().llmProvider("chat:" + provider).modelName("exact").build());
            assertEquals(ProcessingBackendType.CHAT_MODEL, nativeRoute.getBackends().get(0).getType());
            assertEquals(provider, nativeRoute.getBackends().get(0).getProvider());
            assertEquals("exact", nativeRoute.getBackends().get(0).getModelName());
            assertFalse(nativeRoute.isFallbackEnabled());
            assertEquals(provider + "-cli", LocalProjectGraphBackend.cliAgentForProvider(provider));
            assertNull(LocalProjectGraphBackend.cliAgentForProvider("chat:" + provider + "-cli"));
            ProcessingRouteConfig legacy = backend.configuredProcessingRoute(mapper.createObjectNode(),
                    GraphExtractionConfig.builder().llmProvider(provider).modelName("legacy-model").build());
            assertEquals(ProcessingBackendType.CLI_AGENT, legacy.getBackends().get(0).getType());
            assertEquals("legacy-model", legacy.getBackends().get(0).getModelName());
        }
    }

    @Test
    void falseCapabilityAndCredentialDeclarationsFailBeforeAnyCrawlWrites() throws Exception {
        LocalProjectCrawlBackend crawl = new LocalProjectCrawlBackend(mapper);
        for (String capability : List.of("vlm", "embedding", "tools", "required_choice")) {
            ObjectNode request = nativeRoute();
            ((ObjectNode) request.path("processingRoute").path("backends").get(0))
                    .putArray("capabilities").add("llm").add(capability);
            ToolResult result = crawl.crawlDocuments(request, context); // async default must not queue
            assertTrue(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("text/llm only"), result.getOutput());
            assertTrue(snapshot().isEmpty(), "Preflight must precede project/job/source writes");
        }
        for (String unsupported : List.of("requiredToolChoice", "tools", "toolChoice")) {
            ObjectNode request = nativeRoute();
            request.putObject("graphExtraction").put(unsupported, true);
            ToolResult result = crawl.crawlDocuments(request, context);
            assertTrue(result.isError(), result.getOutput());
            assertTrue(snapshot().isEmpty());
        }
        for (String limit : List.of("maxConcurrent", "requestsPerMinute")) {
            ObjectNode request = nativeRoute();
            ((ObjectNode) request.path("processingRoute").path("backends").get(0)).put(limit, 1);
            assertTrue(crawl.crawlDocuments(request, context).isError());
            assertTrue(snapshot().isEmpty());
        }
        for (String credential : List.of("apiKey", "endpointUrl", "endpoint", "headers", "api_key")) {
            ObjectNode request = nativeRoute();
            ((ObjectNode) request.path("processingRoute").path("backends").get(0)).put(credential, "private-marker");
            ToolResult result = crawl.crawlDocuments(request, context);
            assertTrue(result.isError(), result.getOutput());
            assertFalse(result.getOutput().contains("private-marker"));
            assertTrue(snapshot().isEmpty());
        }
    }

    @Test
    void nativeRouteStaysInHostWithManagedUrlAndNestedConfig() throws Exception {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer manager = MockRestServiceServer.bindTo(rest).build();
        GroundingBackendClient client = new GroundingBackendClient("http://managed.invalid", rest);
        ObjectNode request = mapper.createObjectNode();
        ObjectNode config = nativeRoute();
        ((ObjectNode) config.path("processingRoute").path("backends").get(0)).put("apiKey", "rejected-secret");
        request.set("config", config);
        ToolResult result = new CrawlDocumentsTool(client, mapper).execute(request, context);
        assertTrue(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("CHAT_MODEL"), result.getOutput());
        assertFalse(result.getOutput().contains("rejected-secret"));
        assertTrue(snapshot().isEmpty());
        manager.verify(); // No request, including availability probes, may reach the manager.
    }

    @Test
    void sourceShorthandForwardsProviderAndModelInPurePreview() throws Exception {
        saveChat("http://127.0.0.1:1/v1");
        Map<String, String> before = snapshot();
        ObjectNode request = mapper.createObjectNode().put("text", "Acme acquired Initech.")
                .put("provider", "chat:custom").put("model", "request-model").put("dryRun", true);
        ToolResult result = new CrawlSourceTool("http://127.0.0.1:1", mapper).execute(request, context);
        assertFalse(result.isError(), result.getOutput());
        JsonNode effective = mapper.valueToTree(result.getMetadata().get("effectiveRequest"));
        assertEquals("chat:custom", effective.path("graphExtraction").path("llmProvider").asText());
        assertEquals("request-model", effective.path("graphExtraction").path("modelName").asText());
        assertFalse(mapper.valueToTree(result.getMetadata().get("graphExtractionResolution")).isEmpty());
        assertEquals(before, snapshot(), "Selection preview must not modify chat settings or crawl state");
    }

    @Test
    void localExtractUsesProductionGraphParserAndPersistsOnlyWhenRequested() throws Exception {
        saveChat("http://127.0.0.1:1/v1");
        AtomicInteger calls = new AtomicInteger();
        LocalProjectGraphBackend backend = new LocalProjectGraphBackend(mapper,
                new ProjectLocalLearningSubprocessExecutor(mapper),
                (provider, model, prompt, system, timeout) -> {
                    assertEquals("custom", provider);
                    assertEquals("request-model", model);
                    assertTrue(prompt.contains("Acme acquired Initech"));
                    calls.incrementAndGet();
                    return GRAPH;
                });
        ObjectNode request = mapper.createObjectNode().put("action", "extract")
                .put("text", "Acme acquired Initech.").put("model_provider", "chat:custom")
                .put("model_name", "request-model");
        request.set("graphExtraction", graphConfig());
        Map<String, String> before = snapshot();
        ToolResult preview = backend.knowledgeGraph(request, context);
        assertFalse(preview.isError(), preview.getOutput());
        assertEquals(2, ((Number) preview.getMetadata().get("entityCount")).intValue());
        assertEquals(1, ((Number) preview.getMetadata().get("relationCount")).intValue());
        assertEquals("native-chat", preview.getMetadata().get("runtime"));
        assertEquals(false, preview.getMetadata().get("persisted"));
        assertTrue(calls.get() > 0);
        assertEquals(before, snapshot(), "persist=false must not bootstrap or mutate archives/config/transcripts");

        request.put("persist", true).put("knowledgeBase", "native-facts");
        ToolResult persisted = backend.knowledgeGraph(request, context);
        assertFalse(persisted.isError(), persisted.getOutput());
        UnifiedGraph graph = UnifiedGraph.load(root.resolve("data/crawls/native-facts/graph.kgraph"));
        assertTrue(graph.entities().stream().anyMatch(entity -> "Acme".equals(entity.label())));
        assertTrue(graph.relations().stream().anyMatch(relation -> "ACQUIRED".equals(relation.type())));
    }

    @Test
    void dryRunRedactsPipelineResolutionAndResolvedPipelineWithoutMutatingRequest() throws Exception {
        Files.writeString(root.resolve("notes.md"), "Acme acquired Initech.");
        ObjectNode request = mapper.createObjectNode().put("dryRun", true);
        request.putArray("documents").addObject().put("path", "notes.md").put("pipelineId", "text-pipeline");
        request.putArray("pipelines").addObject().put("pipelineId", "text-pipeline")
                .put("pipelineType", "STANDARD_TEXT").putObject("options").put("apiKey", "pipeline-secret-marker");
        request.putObject("processingRoute").putArray("backends").addObject()
                .put("id", "api").put("type", "API_AGENT").put("apiKey", "route-secret-marker")
                .put("endpointUrl", "http://127.0.0.1:1/v1").putArray("capabilities").add("llm");
        ToolResult result = new LocalProjectCrawlBackend(mapper).crawlDocuments(request, context);
        assertFalse(result.isError(), result.getOutput());
        String output = result.getOutput() + mapper.writeValueAsString(result.getMetadata());
        assertTrue(output.contains("pipelineResolution"));
        assertTrue(output.contains("resolvedPipeline"));
        assertFalse(output.contains("pipeline-secret-marker"), output);
        assertFalse(output.contains("route-secret-marker"), output);
        assertTrue(request.toString().contains("pipeline-secret-marker"));
        assertFalse(Files.exists(root.resolve("kompile.project.json")));
    }

    @Test
    void pinnedNativeRouteInheritsGraphModelWithoutStartingUnusedServingBackup() throws Exception {
        saveChat("http://127.0.0.1:1/v1");
        Map<String, String> before = snapshot();
        LocalProjectGraphBackend backend = new LocalProjectGraphBackend(mapper,
                new ProjectLocalLearningSubprocessExecutor(mapper),
                (provider, model, prompt, system, timeout) -> {
                    assertEquals("custom", provider);
                    assertEquals("inherited-model", model);
                    return GRAPH;
                });
        ObjectNode request = nativeRoute().put("action", "extract")
                .put("text", "Acme acquired Initech.").put("model_name", "inherited-model");
        request.set("graphExtraction", graphConfig());
        ObjectNode primary = (ObjectNode) request.path("processingRoute").path("backends").get(0);
        primary.remove("modelName");
        primary.put("priority", 1);
        ((com.fasterxml.jackson.databind.node.ArrayNode) request.path("processingRoute").path("backends"))
                .addObject().put("id", "unused-serving").put("type", "LOCAL_MODEL")
                .put("agentName", "serving").put("priority", 2).put("modelName", "not-installed")
                .putArray("capabilities").add("llm");
        ToolResult result = backend.knowledgeGraph(request, context);
        assertFalse(result.isError(), result.getOutput());
        assertEquals("native-chat", result.getMetadata().get("runtime"));
        assertEquals(before, snapshot());
    }

    @Test
    void nativeLoopbackCrawlProducesSemanticGraphAndForwardsTheExactModel() throws Exception {
        List<JsonNode> calls = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.add(mapper.readTree(exchange.getRequestBody()));
            String response = "data: {\"choices\":[{\"delta\":{\"content\":" + mapper.writeValueAsString(GRAPH)
                    + "},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            saveChat("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            Files.writeString(root.resolve("notes.md"), "Acme acquired Initech.");
            ObjectNode request = mapper.createObjectNode().put("async", false).put("deriveOntology", false).put("strictSteps", true);
            request.putArray("documents").addObject().put("path", "notes.md");
            request.putObject("knowledgeBase").put("name", "native-crawl");
            request.putArray("steps").add("LOADING").add("MARKDOWN_EXTRACTION").add("CHUNKING")
                    .add("LEXICAL_INDEX").add("GRAPH_EXTRACTION");
            request.putObject("embeddingTraining").put("enabled", false);
            request.putObject("reasoningLearning").put("enabled", false);
            ObjectNode config = graphConfig().put("llmProvider", "chat:custom").put("modelName", "request-model");
            request.set("graphExtraction", config);
            ToolResult result = new CrawlDocumentsTool("http://127.0.0.1:1", mapper).execute(request, context);
            assertFalse(result.isError(), result.getOutput());
            assertEquals(2, ((Number) result.getMetadata().get("semanticEntityCount")).intValue(), result.getOutput());
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(call -> "request-model".equals(call.path("model").asText())));
            assertTrue(calls.stream().allMatch(call -> !call.has("tools") || call.path("tools").isEmpty()));
            String kb = (String) result.getMetadata().get("knowledgeBase");
            UnifiedGraph graph = UnifiedGraph.load(root.resolve("data/crawls").resolve(kb).resolve("graph.kgraph"));
            assertEquals("native-chat", graph.meta().get("semanticExtractionRuntime"));
            assertTrue(graph.relations().stream().anyMatch(relation -> "ACQUIRED".equals(relation.type())));
            String artifacts = Files.readString(root.resolve("data/crawls").resolve(kb).resolve("crawl-result.json"));
            assertFalse(artifacts.contains("saved-secret-marker"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void modelCatalogAlwaysUsesHostNativeChatEvenWithManagedGraphConfigured() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"data\":[{\"id\":\"exact-native-model\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            saveChat("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            Map<String, String> before = snapshot();
            KnowledgeGraphTool tool = new KnowledgeGraphTool("http://127.0.0.1:1", mapper);
            ObjectNode request = mapper.createObjectNode().put("action", "list_models").put("model_provider", "chat:custom");
            ToolResult result = tool.execute(request, context);
            assertFalse(result.isError(), result.getOutput());
            JsonNode catalog = mapper.readTree(result.getOutput());
            assertEquals("SUCCESS", catalog.path("catalogStatus").asText(), result.getOutput());
            assertEquals("exact-native-model", catalog.path("models").get(0).path("id").asText());
            assertTrue(catalog.path("manualModelSelectionAllowed").asBoolean());
            assertEquals("UNKNOWN", catalog.path("models").get(0).path("modelSupport").asText());
            assertFalse(result.getOutput().contains("saved-secret-marker"));
            assertTrue(tool.execute(request.deepCopy().put("model_provider", 42), context).isError());
            assertTrue(tool.execute(request.deepCopy().put("apiKey", "inline-secret"), context).isError());
            assertEquals(1, requests.get());
            assertEquals(before, snapshot(), "Model discovery must not create a graph, crawl, or change the selected model");
        } finally { server.stop(0); }
    }

    @Test
    void localProvidersProbeAndDiscoveryAreMetadataOnly() throws Exception {
        saveChat("http://127.0.0.1:1/v1");
        Map<String, String> before = snapshot();
        KnowledgeGraphTool tool = new KnowledgeGraphTool((String) null, mapper);
        ToolResult providers = tool.execute(mapper.createObjectNode().put("action", "list_providers"), context);
        assertFalse(providers.isError(), providers.getOutput());
        ToolResult probe = tool.execute(mapper.createObjectNode().put("action", "capability_probe")
                .put("model_provider", "custom").put("model_name", "request-model").put("live", false), context);
        assertFalse(probe.isError(), probe.getOutput());
        ToolResult discovery = new CrawlDiscoveryTool((String) null, mapper)
                .execute(mapper.createObjectNode().put("section", "pipelines"), context);
        assertFalse(discovery.isError(), discovery.getOutput());
        assertTrue(discovery.getOutput().contains("CHAT_MODEL"));
        assertTrue(discovery.getOutput().contains("chat[:provider]"));
        assertFalse((providers.getOutput() + probe.getOutput() + discovery.getOutput()).contains("saved-secret-marker"));
        assertEquals(before, snapshot());
    }

    private ObjectNode nativeRoute() {
        ObjectNode request = mapper.createObjectNode();
        request.putObject("processingRoute").put("fallbackEnabled", false).putArray("backends").addObject()
                .put("id", "native").put("type", "CHAT_MODEL").put("provider", "custom")
                .put("modelName", "request-model").putArray("capabilities").add("llm");
        return request;
    }

    private ObjectNode graphConfig() {
        ObjectNode config = mapper.createObjectNode().put("schemaMode", "STRICT").put("extractionMode", "SINGLE_PASS")
                .put("entityResolution", false).put("minConfidence", 0.0);
        config.putArray("entityTypes").add("ORGANIZATION");
        config.putArray("relationshipTypes").add("ACQUIRED");
        ObjectNode schema = config.putObject("standardizedSchema");
        schema.putArray("nodeTypes").addObject().put("label", "ORGANIZATION").put("description", "An organization.");
        schema.putArray("relationshipTypes").addObject().put("type", "ACQUIRED").put("description", "An acquisition.");
        schema.putArray("patterns").add("(ORGANIZATION)-[:ACQUIRED]->(ORGANIZATION)");
        return config;
    }

    private void saveChat(String endpoint) throws Exception {
        Path path = Files.createDirectories(root.resolve(".kompile")).resolve("chat-config.json");
        mapper.writeValue(path.toFile(), Map.of("provider", "custom", "apiKey", "saved-secret-marker",
                "model", "saved-model", "baseUrl", endpoint));
    }

    private Map<String, String> snapshot() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                files.put(root.relativize(file).toString(), java.util.HexFormat.of().formatHex(Files.readAllBytes(file)));
            }
        }
        return files;
    }
}
