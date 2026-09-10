/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.graph.GraphServiceRouting;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KnowledgeGraphTool}. Since the tool communicates via HTTP
 * with a running kompile-app, tests are limited to metadata, validation, and
 * error-guard paths. No running server is required.
 */
class KnowledgeGraphToolTest {

    private KnowledgeGraphTool tool;
    private KnowledgeGraphTool noUrlTool;
    private ToolContext context;
    private ObjectMapper om;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        tool = new KnowledgeGraphTool("http://localhost:8080", om);
        noUrlTool = new KnowledgeGraphTool("", om);

        AgentConfig agent = AgentConfig.builder("coder")
                .enabledTools(Set.of("*"))
                .build();
        PermissionService perms = new PermissionService();
        perms.setUserOverride("knowledge_graph", PermissionService.PermissionLevel.ALLOW);
        ToolRegistry registry = new ToolRegistry(om);
        context = new ToolContext("test-session", agent, perms, tempDir, registry);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // METADATA
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testId() {
        assertEquals("knowledge_graph", tool.id());
    }

    @Test
    void testDescription() {
        String desc = tool.description();
        assertNotNull(desc);
        assertFalse(desc.isBlank());
        // Verify key action groups are mentioned
        assertTrue(desc.contains("overview"), "description should mention 'overview'");
        assertTrue(desc.contains("add_node"), "description should mention 'add_node'");
        assertTrue(desc.contains("traverse"), "description should mention 'traverse'");
        assertTrue(desc.contains("hierarchy"), "description should mention 'hierarchy'");
        assertTrue(desc.contains("extract"), "description should mention 'extract'");
        assertTrue(desc.contains("cypher"), "description should mention 'cypher'");
        assertTrue(desc.contains("list_graphs"), "description should mention 'list_graphs'");
        // Builder, proposals, config actions
        assertTrue(desc.contains("list_builders"), "description should mention 'list_builders'");
        assertTrue(desc.contains("start_job"), "description should mention 'start_job'");
        assertTrue(desc.contains("list_proposals"), "description should mention 'list_proposals'");
        assertTrue(desc.contains("accept_proposal"), "description should mention 'accept_proposal'");
        assertTrue(desc.contains("get_config"), "description should mention 'get_config'");
        assertTrue(desc.contains("apply_preset"), "description should mention 'apply_preset'");
    }

    @Test
    void testPermissionKey() {
        assertEquals("knowledge_graph", tool.permissionKey());
    }

    @Test
    void testMcpAnnotationsIsNullForMixedReadWrite() {
        // Tool has both read and write operations, so no blanket annotation
        assertNull(tool.mcpAnnotations());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PARAMETER SCHEMA
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testParameterSchemaStructure() {
        JsonNode schema = tool.parameterSchema();
        assertNotNull(schema);
        assertEquals("object", schema.path("type").asText());
        assertTrue(schema.has("properties"), "schema must have properties");
        assertTrue(schema.has("required"), "schema must have required array");
    }

    @Test
    void testParameterSchemaRequiresAction() {
        JsonNode schema = tool.parameterSchema();
        JsonNode required = schema.path("required");
        assertTrue(required.isArray());
        boolean hasAction = false;
        for (JsonNode r : required) {
            if ("action".equals(r.asText())) {
                hasAction = true;
                break;
            }
        }
        assertTrue(hasAction, "'action' must be in the required array");
    }

    @Test
    void testParameterSchemaHasKeyProperties() {
        JsonNode props = tool.parameterSchema().path("properties");
        assertTrue(props.has("action"), "missing 'action' property");
        assertTrue(props.has("node_id"), "missing 'node_id' property");
        assertTrue(props.has("edge_id"), "missing 'edge_id' property");
        assertTrue(props.has("graph_id"), "missing 'graph_id' property");
        assertTrue(props.has("query"), "missing 'query' property");
        assertTrue(props.has("title"), "missing 'title' property");
        assertTrue(props.has("from_node_id"), "missing 'from_node_id' property");
        assertTrue(props.has("to_node_id"), "missing 'to_node_id' property");
        assertTrue(props.has("edge_type"), "missing 'edge_type' property");
        assertTrue(props.has("depth"), "missing 'depth' property");
        assertTrue(props.has("limit"), "missing 'limit' property");
        assertTrue(props.has("weight"), "missing 'weight' property");
        assertTrue(props.has("fact_sheet_id"), "missing 'fact_sheet_id' property");
        assertTrue(props.has("cypher_query"), "missing 'cypher_query' property");
        assertTrue(props.has("algorithm_name"), "missing 'algorithm_name' property");
        assertTrue(props.has("report_type"), "missing 'report_type' property");
        assertTrue(props.has("graph_name"), "missing 'graph_name' property");
        assertTrue(props.has("text"), "missing 'text' property");
        assertTrue(props.has("persist"), "missing 'persist' property");
    }

    @Test
    void testParameterSchemaPropertyTypes() {
        JsonNode props = tool.parameterSchema().path("properties");
        assertEquals("string", props.path("action").path("type").asText());
        assertEquals("string", props.path("node_id").path("type").asText());
        assertEquals("integer", props.path("depth").path("type").asText());
        assertEquals("integer", props.path("limit").path("type").asText());
        assertEquals("number", props.path("weight").path("type").asText());
        assertEquals("boolean", props.path("persist").path("type").asText());
        assertEquals("boolean", props.path("weighted").path("type").asText());
        assertEquals("boolean", props.path("read_only").path("type").asText());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NO-URL GUARD
    // ═══════════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
            "overview", "stats", "search_entity", "search_nodes", "find_by_topic",
            "list_nodes", "get_node", "add_node", "delete_node",
            "list_edges", "add_edge", "delete_edge",
            "traverse", "shortest_path", "algorithm", "communities",
            "hierarchy", "ancestors", "source_chunks",
            "list_graphs", "create_graph", "delete_graph",
            "extract", "build_graph", "report", "cypher",
            "list_builders", "start_job", "list_jobs", "job_status", "cancel_job", "job_logs",
            "list_proposals", "accept_proposal", "reject_proposal", "manual_proposal",
            "get_config", "set_config", "list_providers", "list_presets", "apply_preset",
            // NEW actions
            "owl_reasoning", "ontology_conformance", "bind_ontology", "unbind_ontology",
            "opinions", "facts_by_tier", "graph_health", "list_rules", "reactive_rules",
            "node_provenance", "list_pipelines", "reasoning_layers"
    })
    void testNoUrlRoutesToProjectLocalBackend(String action) throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", action);
        // Add minimal required params to get past validation
        params.put("node_id", "test-id");
        params.put("edge_id", "test-id");
        params.put("graph_id", "test-id");
        params.put("entity_name", "test");
        params.put("query", "test");
        params.put("topic", "test");
        params.put("document_id", "test");
        params.put("source_id", "test");
        params.put("from_node_id", "test");
        params.put("to_node_id", "test");
        params.put("title", "test");
        params.put("external_id", "test");
        params.put("algorithm_name", "pagerank");
        params.put("cypher_query", "MATCH (n) RETURN n");
        params.put("text", "test text");
        params.put("fact_sheet_id", 1);
        params.put("graph_name", "test");
        params.put("report_type", "summary");
        // Builder/proposals/config params
        params.put("job_id", "test-job");
        params.put("proposal_id", "test-proposal");
        params.put("subject_name", "Test Subject");
        params.put("subject_type", "ENTITY");
        params.put("predicate_name", "relates_to");
        params.put("object_name", "Test Object");
        params.put("object_type", "ENTITY");
        params.put("preset_id", "test-preset");
        params.put("enabled", true);
        params.put("schema_mode", "STRICT");
        // NEW action params
        params.put("ontology_schema_id", "test-ontology");

        ToolResult result = noUrlTool.execute(params, context);
        assertFalse(result.getOutput().contains("requires a running kompile-app"),
                "action '" + action + "' must not require the remote app in local chat");
        assertFalse(result.getOutput().contains("localhost:8095"),
                "action '" + action + "' must not route to the default graph-service port");
    }

    @Test
    void graphInventoryBootstrapsTheCurrentFolderWithoutAFactSheetId() throws Exception {
        ObjectNode listGraphs = om.createObjectNode().put("action", "list_graphs");

        ToolResult result = noUrlTool.execute(listGraphs, context);

        assertFalse(result.isError(), result.getOutput());
        String knowledgeBase = tempDir.getFileName().toString().toLowerCase() + "-knowledge";
        assertTrue(result.getOutput().contains(knowledgeBase), result.getOutput());
        assertTrue(Files.isRegularFile(tempDir.resolve("data/crawls")
                .resolve(knowledgeBase).resolve("graph.kgraph")));
    }

    @Test
    void factSheetInventoryUsesCrawlSummariesAndIsolatesUnreadableLegacyGraphs() throws Exception {
        Path healthy = tempDir.resolve("data/crawls/dogfood-ocr-pdf");
        Files.createDirectories(healthy);
        Files.writeString(healthy.resolve("crawl-result.json"), """
                {
                  "profileId": "dogfood-ocr-pdf",
                  "name": "MCP OCR Dogfood",
                  "status": "COMPLETED",
                  "sources": ["wailingcaverns.pdf"],
                  "documentCount": 1,
                  "chunkCount": 1,
                  "graphEntityCount": 4,
                  "graphRelationCount": 3
                }
                """, StandardCharsets.UTF_8);
        Files.write(healthy.resolve("graph.kgraph"), new byte[]{(byte) 0xc3});
        Path orphan = tempDir.resolve("data/crawls/unreadable-legacy");
        Files.createDirectories(orphan);
        Files.write(orphan.resolve("graph.kgraph"), new byte[]{(byte) 0xc3});

        ToolResult result = noUrlTool.execute(
                om.createObjectNode().put("action", "list_fact_sheets"), context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("MCP OCR Dogfood"), result.getOutput());
        assertTrue(result.getOutput().contains("\"inventorySource\" : \"crawl-summary\""),
                result.getOutput());
        assertTrue(result.getOutput().contains("\"skippedUnreadableGraphs\" : 1"),
                result.getOutput());
        assertTrue(result.getOutput().contains("unreadable-legacy"), result.getOutput());
    }

    @Test
    void projectLocalGraphSupportsDiscoveryStatusAndQueries() throws Exception {
        Path graphPath = tempDir.resolve("data/crawls/kb-7/graph.kgraph");
        Files.createDirectories(graphPath.getParent());
        UnifiedGraph graph = new UnifiedGraph()
                .graphId("local:test:kb-7")
                .factSheetId(7L)
                .meta("backend", "project-local")
                .meta("knowledgeBaseId", "kb-7")
                .meta("knowledgeBaseName", "Permission Test");
        graph.addEntity("alice", "PERSON", "Alice");
        graph.addEntity("repo", "PROJECT", "Kompile");
        graph.addRelation("alice-knows-repo", "alice", "repo", "KNOWS", 1.0);
        graph.save(graphPath);

        ObjectNode overview = om.createObjectNode().put("action", "overview")
                .put("knowledgeBase", "kb-7");
        ToolResult overviewResult = noUrlTool.execute(overview, context);
        assertFalse(overviewResult.isError(), overviewResult.getOutput());
        assertTrue(overviewResult.getOutput().contains("project-local"));
        assertTrue(overviewResult.getOutput().contains("Alice")
                        || overviewResult.getOutput().contains("entities"));

        ObjectNode factSheets = om.createObjectNode().put("action", "list_fact_sheets");
        ToolResult factSheetResult = noUrlTool.execute(factSheets, context);
        assertFalse(factSheetResult.isError(), factSheetResult.getOutput());
        assertTrue(factSheetResult.getOutput().contains("Permission Test"));

        ObjectNode predicates = om.createObjectNode().put("action", "list_predicates")
                .put("knowledgeBase", "kb-7");
        ToolResult predicateResult = noUrlTool.execute(predicates, context);
        assertFalse(predicateResult.isError(), predicateResult.getOutput());
        assertTrue(predicateResult.getOutput().contains("KNOWS"));

        ObjectNode search = om.createObjectNode().put("action", "search_nodes")
                .put("knowledgeBase", "kb-7").put("query", "Alice");
        ToolResult searchResult = noUrlTool.execute(search, context);
        assertFalse(searchResult.isError(), searchResult.getOutput());
        assertTrue(searchResult.getOutput().contains("Alice"));
    }

    @Test
    void localRegistryUsesGraphArchiveInsteadOfDefaultGraphServicePort() {
        GraphServiceRouting.Resolution defaultRoute = new GraphServiceRouting.Resolution(
                GraphServiceRouting.DEFAULT_URL, GraphServiceRouting.Source.DEFAULT);
        GraphServiceRouting.Resolution configuredRoute = new GraphServiceRouting.Resolution(
                "http://graph.example:9195", GraphServiceRouting.Source.ENVIRONMENT);
        GraphServiceRouting.Resolution staleManagedRoute = new GraphServiceRouting.Resolution(
                "http://localhost:8095", GraphServiceRouting.Source.MANAGED_INSTANCE);

        assertNull(ToolRegistryFactory.resolveGraphBaseUrl("", defaultRoute));
        assertNull(ToolRegistryFactory.resolveGraphBaseUrl(null, defaultRoute));
        assertNull(ToolRegistryFactory.resolveGraphBaseUrl("", staleManagedRoute));
        assertEquals("http://graph.example:9195",
                ToolRegistryFactory.resolveGraphBaseUrl("", configuredRoute));
        assertEquals(GraphServiceRouting.DEFAULT_URL,
                ToolRegistryFactory.resolveGraphBaseUrl("http://localhost:8081", defaultRoute));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MISSING ACTION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testMissingActionReturnsError() throws Exception {
        ObjectNode params = om.createObjectNode();
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("action is required"));
    }

    @Test
    void testUnknownActionReturnsError() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "quantum_entangle");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Unknown action"));
        assertTrue(result.getOutput().contains("quantum_entangle"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUIRED FIELD VALIDATION (connection refused → error, not exception)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testSearchEntityRequiresEntityName() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "search_entity");
        // entity_name intentionally missing
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("entity_name"));
    }

    @Test
    void testRelatedDocsRequiresDocumentId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "related_docs");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("document_id"));
    }

    @Test
    void testSourceContextRequiresSourceId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "source_context");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("source_id"));
    }

    @Test
    void testFindConnectedRequiresNodeId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "find_connected");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("node_id"));
    }

    @Test
    void testSearchNodesRequiresQuery() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "search_nodes");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("query"));
    }

    @Test
    void testEntitiesInDocRequiresDocumentId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "entities_in_doc");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("document_id"));
    }

    @Test
    void testFindByTopicRequiresTopic() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "find_by_topic");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("topic"));
    }

    @Test
    void testGetNodeRequiresNodeId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "get_node");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("node_id"));
    }

    @Test
    void testDeleteNodeRequiresNodeId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "delete_node");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("node_id"));
    }

    @Test
    void testAddNodeRequiresTitle() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "add_node");
        params.put("external_id", "ext-1");
        // title intentionally missing
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("title"));
    }

    @Test
    void testAddNodeRequiresExternalId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "add_node");
        params.put("title", "My Node");
        // external_id intentionally missing
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("external_id"));
    }

    @Test
    void testAddEdgeRequiresFromNodeId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "add_edge");
        params.put("to_node_id", "target");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("from_node_id"));
    }

    @Test
    void testAddEdgeRequiresToNodeId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "add_edge");
        params.put("from_node_id", "source");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("to_node_id"));
    }

    @Test
    void testDeleteEdgeRequiresEdgeId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "delete_edge");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("edge_id"));
    }

    @Test
    void testTraverseRequiresNodeId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "traverse");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("node_id"));
    }

    @Test
    void testShortestPathRequiresFromNodeId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "shortest_path");
        params.put("to_node_id", "target");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("from_node_id"));
    }

    @Test
    void testShortestPathRequiresToNodeId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "shortest_path");
        params.put("from_node_id", "source");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("to_node_id"));
    }

    @Test
    void testAlgorithmRequiresAlgorithmName() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "algorithm");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("algorithm_name"));
    }

    @Test
    void testAlgorithmUnknownNameReturnsError() throws Exception {
        // Use a port that won't be listening — but the unknown algorithm
        // check happens before any HTTP call
        KnowledgeGraphTool localTool = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "algorithm");
        params.put("algorithm_name", "bogus_algo");
        ToolResult result = localTool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Unknown algorithm"));
        assertTrue(result.getOutput().contains("bogus_algo"));
    }

    @Test
    void testAncestorsRequiresNodeId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "ancestors");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("node_id"));
    }

    @Test
    void testSourceChunksRequiresNodeId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "source_chunks");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("node_id"));
    }

    @Test
    void testDeleteGraphRequiresGraphId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "delete_graph");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("graph_id"));
    }

    @Test
    void testCreateGraphRequiresName() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "create_graph");
        // No graph_name or title
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("graph_name") || result.getOutput().contains("title"));
    }

    @Test
    void testExtractRequiresText() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "extract");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("text"));
    }

    @Test
    void testBuildGraphRequiresFactSheetId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "build_graph");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("fact_sheet_id"));
    }

    @Test
    void managedExtractUsesHostNativeModelSelectionInsteadOfRemoteMultiAgentEndpoint() throws Exception {
        java.util.concurrent.atomic.AtomicReference<JsonNode> captured = new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(om.readTree(exchange.getRequestBody()));
            String payload = "{\"entities\":[{\"id\":\"acme\",\"name\":\"Acme\",\"type\":\"ORGANIZATION\"}],\"relations\":[]}";
            ObjectNode event = om.createObjectNode();
            event.putArray("choices").addObject().putObject("delta").put("content", payload);
            byte[] bytes = ("data: " + event + "\n\ndata: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            Path config = Files.createDirectories(tempDir.resolve(".kompile"))
                    .resolve("chat-config.json");
            om.writeValue(config.toFile(), Map.of("provider", "openai", "apiKey", "test-key",
                    "model", "saved-model", "baseUrl", "http://127.0.0.1:"
                            + server.getAddress().getPort() + "/v1"));
            KnowledgeGraphTool managed = new KnowledgeGraphTool("http://127.0.0.1:1", om);
            ToolResult result = managed.execute(om.createObjectNode().put("action", "extract")
                    .put("text", "Acme exists.").put("model_provider", "chat:openai")
                    .put("model_name", "request-model").put("thinking", "xhigh")
                    .put("entity_types", "ORGANIZATION"), context);
            assertFalse(result.isError(), result.getOutput());
            assertEquals("request-model", captured.get().path("model").asText());
            assertEquals("xhigh", ai.kompile.cli.main.project.NativeChatModels
                    .resolve(tempDir, "openai", "request-model", "xhigh").thinking());
            assertTrue(result.getOutput().contains("Acme"), result.getOutput());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteFiltersArePreservedAcrossNodeEdgeAndDocumentSearchActions() throws Exception {
        java.util.List<String> requests = new java.util.ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI()
                    + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            KnowledgeGraphTool managed = new KnowledgeGraphTool(
                    "http://127.0.0.1:" + server.getAddress().getPort(), om);
            managed.execute(om.createObjectNode().put("action", "search_nodes")
                    .put("query", "Acme").put("node_type", "ORGANIZATION")
                    .put("max_results", 7), context);
            managed.execute(om.createObjectNode().put("action", "list_nodes")
                    .put("node_type", "ENTITY").put("limit", 11), context);
            managed.execute(om.createObjectNode().put("action", "list_edges")
                    .put("node_id", "node-1").put("edge_type", "KNOWS").put("limit", 13), context);
            managed.execute(om.createObjectNode().put("action", "related_docs")
                    .put("document_id", "doc-1").put("relationship_type", "entity")
                    .put("max_results", 5), context);
            managed.execute(om.createObjectNode().put("action", "source_context")
                    .put("source_id", "src-1").put("include_children", true), context);
            assertTrue(requests.stream().anyMatch(request -> request.contains("nodeType\":\"ORGANIZATION")), requests.toString());
            assertTrue(requests.stream().anyMatch(request -> request.contains("/nodes?limit=11&type=ENTITY")), requests.toString());
            assertTrue(requests.stream().anyMatch(request -> request.contains("/edges?limit=13&nodeId=node-1&type=KNOWS")), requests.toString());
            assertTrue(requests.stream().anyMatch(request -> request.contains("relationshipType\":\"entity")), requests.toString());
            assertTrue(requests.stream().anyMatch(request -> request.contains("includeChildren\":true")), requests.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testCypherRequiresQuery() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "cypher");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("cypher_query"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONNECTION ERROR HANDLING (no server at port → graceful error, not crash)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "overview");
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("explicitly configured remote") ||
                        result.getOutput().contains("Knowledge graph error"),
                "connection error should be handled gracefully");
    }

    @Test
    void testStatsConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "stats");
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    @Test
    void testListNodesConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "list_nodes");
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    @Test
    void testListGraphsConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "list_graphs");
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    @Test
    void testCommunitiesConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "communities");
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    @Test
    void testHierarchyConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "hierarchy");
        // hierarchy without node_id lists roots, still needs connection
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    @Test
    void testReportConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "report");
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CREATE_GRAPH ACCEPTS TITLE AS FALLBACK FOR GRAPH_NAME
    // ═══════════════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════════
    // BUILDER / PROPOSALS / CONFIG — SCHEMA PROPERTIES
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testSchemaHasBuilderProperties() {
        JsonNode props = tool.parameterSchema().path("properties");
        assertTrue(props.has("job_id"), "missing 'job_id' property");
        assertTrue(props.has("builder_type"), "missing 'builder_type' property");
        assertTrue(props.has("model_provider"), "missing 'model_provider' property");
        assertTrue(props.has("model_name"), "missing 'model_name' property");
        assertTrue(props.has("temperature"), "missing 'temperature' property");
        assertTrue(props.has("auto_accept"), "missing 'auto_accept' property");
        assertTrue(props.has("custom_prompt"), "missing 'custom_prompt' property");
    }

    @Test
    void testSchemaHasProposalProperties() {
        JsonNode props = tool.parameterSchema().path("properties");
        assertTrue(props.has("proposal_id"), "missing 'proposal_id' property");
        assertTrue(props.has("proposal_status"), "missing 'proposal_status' property");
        assertTrue(props.has("subject_name"), "missing 'subject_name' property");
        assertTrue(props.has("subject_type"), "missing 'subject_type' property");
        assertTrue(props.has("predicate_name"), "missing 'predicate_name' property");
        assertTrue(props.has("object_name"), "missing 'object_name' property");
        assertTrue(props.has("object_type"), "missing 'object_type' property");
        assertTrue(props.has("rejection_reason"), "missing 'rejection_reason' property");
    }

    @Test
    void testSchemaHasConfigProperties() {
        JsonNode props = tool.parameterSchema().path("properties");
        assertTrue(props.has("schema_mode"), "missing 'schema_mode' property");
        assertTrue(props.has("preset_id"), "missing 'preset_id' property");
        assertFalse(props.has("enabled"), "graph extraction is mandatory and must not expose 'enabled'");
    }

    @Test
    void testSchemaHasNewGraphCapabilityProperties() {
        JsonNode props = tool.parameterSchema().path("properties");
        assertTrue(props.has("ontology_schema_id"), "missing 'ontology_schema_id' property");
        assertTrue(props.has("ontology_version"), "missing 'ontology_version' property");
        assertTrue(props.has("method"), "missing 'method' property");
        assertTrue(props.has("resolution"), "missing 'resolution' property");
        assertTrue(props.has("tier"), "missing 'tier' property");
        assertTrue(props.has("basis_type"), "missing 'basis_type' property");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BUILDER / PROPOSALS — REQUIRED FIELD VALIDATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testStartJobRequiresFactSheetId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "start_job");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("fact_sheet_id"));
    }

    @Test
    void testJobStatusRequiresJobId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "job_status");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("job_id"));
    }

    @Test
    void testCancelJobRequiresJobId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "cancel_job");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("job_id"));
    }

    @Test
    void testJobLogsRequiresJobId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "job_logs");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("job_id"));
    }

    @Test
    void testAcceptProposalRequiresProposalId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "accept_proposal");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("proposal_id"));
    }

    @Test
    void testRejectProposalRequiresProposalId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "reject_proposal");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("proposal_id"));
    }

    @Test
    void testManualProposalRequiresSubjectName() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "manual_proposal");
        params.put("fact_sheet_id", 1);
        params.put("subject_type", "PERSON");
        params.put("predicate_name", "knows");
        params.put("object_name", "Bob");
        params.put("object_type", "PERSON");
        // subject_name intentionally missing
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("subject_name"));
    }

    @Test
    void testManualProposalRequiresFactSheetId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "manual_proposal");
        params.put("subject_name", "Alice");
        params.put("subject_type", "PERSON");
        params.put("predicate_name", "knows");
        params.put("object_name", "Bob");
        params.put("object_type", "PERSON");
        // fact_sheet_id intentionally missing (default 0)
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("fact_sheet_id"));
    }

    @Test
    void testApplyPresetRequiresPresetId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "apply_preset");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("preset_id"));
    }

    @Test
    void testSetConfigRequiresAtLeastOneField() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "set_config");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("No config fields"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BUILDER / CONFIG — CONNECTION REFUSED GRACEFUL
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testListBuildersConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "list_builders");
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    @Test
    void testGetConfigConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "get_config");
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    @Test
    void testListProvidersConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "list_providers");
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    @Test
    void testListPresetsConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "list_presets");
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    @Test
    void testListProposalsConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "list_proposals");
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    @Test
    void testListJobsConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "list_jobs");
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CREATE_GRAPH ACCEPTS TITLE AS FALLBACK FOR GRAPH_NAME
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testCreateGraphAcceptsTitleFallback() throws Exception {
        // Uses unreachable server to verify param validation passes
        // but connection is refused (proves title is accepted as name)
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "create_graph");
        params.put("title", "My Graph via Title");
        ToolResult result = unreachable.execute(params, context);
        // Should get connection error, NOT validation error
        assertTrue(result.isError());
        assertFalse(result.getOutput().contains("graph_name") && result.getOutput().contains("title"),
                "should not complain about missing name when title is provided");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NEW ACTIONS — REQUIRED FIELD VALIDATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testOwlReasoningRequiresFactSheetId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "owl_reasoning");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("fact_sheet_id"));
    }

    @Test
    void testOntologyConformanceRequiresFactSheetId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "ontology_conformance");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("fact_sheet_id"));
    }

    @Test
    void testBindOntologyRequiresFactSheetId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "bind_ontology");
        params.put("ontology_schema_id", "my-ontology");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("fact_sheet_id"));
    }

    @Test
    void testBindOntologyRequiresOntologySchemaId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "bind_ontology");
        params.put("fact_sheet_id", 1);
        // ontology_schema_id intentionally missing
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("ontology_schema_id"));
    }

    @Test
    void testUnbindOntologyRequiresFactSheetId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "unbind_ontology");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("fact_sheet_id"));
    }

    @Test
    void testOpinionsRequiresFactSheetId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "opinions");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("fact_sheet_id"));
    }

    @Test
    void testFactsByTierRequiresFactSheetId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "facts_by_tier");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("fact_sheet_id"));
    }

    @Test
    void testGraphHealthRequiresFactSheetId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "graph_health");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("fact_sheet_id"));
    }

    @Test
    void testListRulesRequiresFactSheetId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "list_rules");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("fact_sheet_id"));
    }

    @Test
    void testNodeProvenanceRequiresNodeId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "node_provenance");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("node_id"));
    }

    @Test
    void testReasoningLayersRequiresFactSheetId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "reasoning_layers");
        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("fact_sheet_id"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NEW ACTIONS — CONNECTION REFUSED GRACEFUL
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testOwlReasoningConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "owl_reasoning");
        params.put("fact_sheet_id", 1);
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    @Test
    void testGraphHealthConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "graph_health");
        params.put("fact_sheet_id", 1);
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    @Test
    void testReactiveRulesConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "reactive_rules");
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    @Test
    void testListPipelinesConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "list_pipelines");
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    @Test
    void testReasoningLayersConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "reasoning_layers");
        params.put("fact_sheet_id", 1);
        ToolResult result = unreachable.execute(params, context);
        assertTrue(result.isError());
    }

    @Test
    void testReasoningLayersReturnsStructuredMetadata() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/graph/42/reasoning-layers", exchange -> {
            String body = """
                    {
                      "factSheetId": 42,
                      "nodes": [
                        {
                          "nodeId": "node-1",
                          "ontology": { "violations": ["missing type"] },
                          "mebn": { "posterior": 0.73 },
                          "neuralScores": { "scores": { "gnn": 0.44 } }
                        }
                      ],
                      "edges": [
                        {
                          "edgeId": "edge-1",
                          "psl": { "groundingId": "g-1" },
                          "neuralScores": { "scores": { "kge": 0.59 } }
                        }
                      ],
                      "statistics": {
                        "nodeCount": 1,
                        "edgeCount": 1,
                        "ontologyCount": 1,
                        "pslCount": 1,
                        "mebnCount": 1,
                        "provenanceCount": 0,
                        "opinionCount": 0,
                        "neuralScoreCount": 2
                      }
                    }
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            KnowledgeGraphTool localTool = new KnowledgeGraphTool(
                    "http://localhost:" + server.getAddress().getPort(), om);
            ObjectNode params = om.createObjectNode();
            params.put("action", "reasoning_layers");
            params.put("fact_sheet_id", 42);

            ToolResult result = localTool.execute(params, context);

            assertFalse(result.isError(), result.getOutput());
            assertEquals("reasoning_layers: 42", result.getTitle());
            assertTrue(result.getOutput().contains("Ontology overlays: 1"));
            assertTrue(result.getOutput().contains("MEBN posteriors"));
            assertTrue(result.getOutput().contains("Neural edge scores"));
            assertEquals(42L, result.getMetadata().get("factSheetId"));
            assertEquals(1, result.getMetadata().get("nodeCount"));
            assertEquals(1, result.getMetadata().get("edgeCount"));

            assertInstanceOf(Map.class, result.getMetadata().get("statistics"));
            assertInstanceOf(Map.class, result.getMetadata().get("reasoningLayers"));
            @SuppressWarnings("unchecked")
            Map<String, Object> reasoningLayers =
                    (Map<String, Object>) result.getMetadata().get("reasoningLayers");
            assertEquals(42, reasoningLayers.get("factSheetId"));
            assertTrue(reasoningLayers.containsKey("nodes"));
            assertTrue(reasoningLayers.containsKey("edges"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testDescriptionMentionsNewActions() {
        String desc = tool.description();
        assertTrue(desc.contains("owl_reasoning"), "description should mention 'owl_reasoning'");
        assertTrue(desc.contains("ontology_conformance"), "description should mention 'ontology_conformance'");
        assertTrue(desc.contains("opinions"), "description should mention 'opinions'");
        assertTrue(desc.contains("facts_by_tier"), "description should mention 'facts_by_tier'");
        assertTrue(desc.contains("graph_health"), "description should mention 'graph_health'");
        assertTrue(desc.contains("list_rules"), "description should mention 'list_rules'");
        assertTrue(desc.contains("reactive_rules"), "description should mention 'reactive_rules'");
        assertTrue(desc.contains("node_provenance"), "description should mention 'node_provenance'");
        assertTrue(desc.contains("list_pipelines"), "description should mention 'list_pipelines'");
        assertTrue(desc.contains("reasoning_layers"), "description should mention 'reasoning_layers'");
    }
}
