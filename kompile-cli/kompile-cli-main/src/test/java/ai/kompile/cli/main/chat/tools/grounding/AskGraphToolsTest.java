/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the 5 {@code ask_graph_*} MCP tools.
 *
 * <p>Tests focus on:
 * <ul>
 *   <li>Tool metadata (id, permissionKey, annotations)</li>
 *   <li>Parameter schema shape (required fields, types)</li>
 *   <li>Missing-required-param guard (no backend needed)</li>
 *   <li>Backend-unavailable error path (no actual HTTP)</li>
 *   <li>ask_graph_subscribe always returns not-implemented</li>
 * </ul>
 */
@DisplayName("ask_graph_* MCP Tools")
class AskGraphToolsTest {

    @TempDir
    Path tempDir;

    private ObjectMapper om;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("coder")
                .enabledTools(Set.of("*"))
                .build();
        PermissionService perms = new PermissionService();
        // Allow all ask_graph_* permissions
        perms.setUserOverride("ask_graph_verify",    PermissionService.PermissionLevel.ALLOW);
        perms.setUserOverride("ask_graph_query",     PermissionService.PermissionLevel.ALLOW);
        perms.setUserOverride("ask_graph_explain",   PermissionService.PermissionLevel.ALLOW);
        perms.setUserOverride("ask_graph_assert",    PermissionService.PermissionLevel.ALLOW);
        perms.setUserOverride("ask_graph_subscribe", PermissionService.PermissionLevel.ALLOW);
        ToolRegistry registry = new ToolRegistry(om);
        ctx = new ToolContext("test-session", agent, perms, Paths.get("."), registry);
    }

    // ── ask_graph_verify ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ask_graph_verify")
    class VerifyTool {

        private AskGraphVerifyTool tool;

        @BeforeEach
        void setUp() {
            // Pass null baseUrl — backend will not be available (no running app)
            tool = new AskGraphVerifyTool(null, om);
        }

        @Test
        @DisplayName("id and permissionKey are correct")
        void metadata() {
            assertEquals("ask_graph_verify", tool.id());
            assertEquals("ask_graph_verify", tool.permissionKey());
            assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
        }

        @Test
        @DisplayName("parameterSchema has 'atom' as required")
        void schemaHasAtomRequired() {
            var schema = tool.parameterSchema();
            assertTrue(schema.path("required").toString().contains("atom"));
            assertNotNull(schema.path("properties").path("atom"));
        }

        @Test
        @DisplayName("missing atom param returns error")
        void missingAtom_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode(); // no atom field
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError(), "Expected error when atom is missing");
            assertTrue(result.getOutput().contains("atom"));
        }

        @Test
        @DisplayName("backend unavailable returns descriptive error")
        void backendUnavailable_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("atom", "isEmployedBy(Alice, Acme)");
            // Backend is not available (no running kompile-app, null baseUrl)
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError(), "Expected error when backend not available");
        }
    }

    // ── ask_graph_query ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ask_graph_query")
    class QueryTool {

        private AskGraphQueryTool tool;

        @BeforeEach
        void setUp() {
            tool = new AskGraphQueryTool(null, om);
        }

        @Test
        @DisplayName("id and permissionKey are correct")
        void metadata() {
            assertEquals("ask_graph_query", tool.id());
            assertEquals("ask_graph_query", tool.permissionKey());
            assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
        }

        @Test
        @DisplayName("parameterSchema has 'conjuncts' as required")
        void schemaHasConjunctsRequired() {
            var schema = tool.parameterSchema();
            assertTrue(schema.path("required").toString().contains("conjuncts"));
        }

        @Test
        @DisplayName("missing conjuncts returns error")
        void missingConjuncts_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError());
            assertTrue(result.getOutput().toLowerCase().contains("conjuncts"));
        }

        @Test
        @DisplayName("empty conjuncts array returns error")
        void emptyConjuncts_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.putArray("conjuncts"); // empty array
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError());
        }
    }

    // ── ask_graph_explain ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ask_graph_explain")
    class ExplainTool {

        private AskGraphExplainTool tool;

        @BeforeEach
        void setUp() {
            tool = new AskGraphExplainTool(null, om);
        }

        @Test
        @DisplayName("id and permissionKey are correct")
        void metadata() {
            assertEquals("ask_graph_explain", tool.id());
            assertEquals("ask_graph_explain", tool.permissionKey());
            assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
        }

        @Test
        @DisplayName("missing atom returns error")
        void missingAtom_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("atom"));
        }
    }

    // ── ask_graph_assert ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ask_graph_assert")
    class AssertTool {

        private AskGraphAssertTool tool;

        @BeforeEach
        void setUp() {
            tool = new AskGraphAssertTool(null, om);
        }

        @Test
        @DisplayName("id and permissionKey are correct")
        void metadata() {
            assertEquals("ask_graph_assert", tool.id());
            assertEquals("ask_graph_assert", tool.permissionKey());
            assertEquals(McpToolAnnotations.WRITE, tool.mcpAnnotations());
        }

        @Test
        @DisplayName("parameterSchema has 'atom' and 'value' as required")
        void schemaRequiredFields() {
            var schema = tool.parameterSchema();
            String required = schema.path("required").toString();
            assertTrue(required.contains("atom"));
            assertTrue(required.contains("value"));
        }

        @Test
        @DisplayName("missing atom returns error")
        void missingAtom_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("value", 1.0);
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("atom"));
        }

        @Test
        @DisplayName("missing value returns error")
        void missingValue_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("atom", "foo(X)");
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("value"));
        }

        @Test
        @DisplayName("value out of range returns error")
        void valueOutOfRange_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("atom", "foo(X)");
            params.put("value", 2.0);
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("[0,1]"));
        }
    }

    // ── ask_graph_subscribe (Phase 2 stub) ────────────────────────────────────────

    @Nested
    @DisplayName("ask_graph_subscribe")
    class SubscribeTool {

        private AskGraphSubscribeTool tool;

        @BeforeEach
        void setUp() {
            tool = new AskGraphSubscribeTool(om);
        }

        @Test
        @DisplayName("id and permissionKey are correct")
        void metadata() {
            assertEquals("ask_graph_subscribe", tool.id());
            assertEquals("ask_graph_subscribe", tool.permissionKey());
        }

        @Test
        @DisplayName("always returns not-implemented error regardless of params")
        void alwaysNotImplemented() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.putArray("predicates").add("isEmployedBy");
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError(), "subscribe should return error (not-implemented)");
            assertTrue(result.getOutput().contains("Phase 2"),
                    "error message should mention Phase 2");
        }

        @Test
        @DisplayName("works with no params too")
        void noParams_stillNotImplemented() throws Exception {
            ObjectNode params = om.createObjectNode();
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError());
        }
    }
}
