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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Paths;
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
        context = new ToolContext("test-session", agent, perms, Paths.get("."), registry);
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
            "get_config", "set_config", "toggle_extraction", "list_providers", "list_presets", "apply_preset"
    })
    void testNoUrlReturnsError(String action) throws Exception {
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

        ToolResult result = noUrlTool.execute(params, context);
        assertTrue(result.isError(), "action '" + action + "' should return error when no URL configured");
        assertTrue(result.getOutput().contains("requires a running kompile-app"),
                "error for '" + action + "' should mention requiring kompile-app");
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
        assertTrue(result.getOutput().contains("Cannot connect") ||
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
        assertTrue(props.has("enabled"), "missing 'enabled' property");
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
    void testToggleExtractionConnectionRefusedGraceful() throws Exception {
        KnowledgeGraphTool unreachable = new KnowledgeGraphTool("http://localhost:19999", om);
        ObjectNode params = om.createObjectNode();
        params.put("action", "toggle_extraction");
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
}
