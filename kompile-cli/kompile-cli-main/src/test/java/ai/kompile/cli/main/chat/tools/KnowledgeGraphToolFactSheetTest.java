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
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.KnowledgeGraphTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the fact-sheet management actions added to {@link KnowledgeGraphTool}.
 *
 * <p>Uses a lightweight built-in {@code HttpServer} (from {@code com.sun.net.httpserver})
 * as a stub, avoiding any Spring context while still exercising the real HTTP layer.
 * Each test family registers a stub handler for the relevant {@code /api/fact-sheets}
 * endpoint and asserts that the tool produces the right output / error message.</p>
 */
@DisplayName("KnowledgeGraphTool — fact-sheet actions")
class KnowledgeGraphToolFactSheetTest {

    private ObjectMapper om;
    private ToolContext ctx;
    private HttpServer httpServer;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        om = new ObjectMapper();

        // Build a minimal ToolContext that allows the knowledge_graph permission
        AgentConfig agent = AgentConfig.builder("coder").enabledTools(Set.of("*")).build();
        PermissionService perms = new PermissionService();
        perms.setUserOverride("knowledge_graph", PermissionService.PermissionLevel.ALLOW);
        ToolRegistry registry = new ToolRegistry(om);
        ctx = new ToolContext("test-session", agent, perms, Paths.get("."), registry);

        // Start a stub HTTP server on a random port
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        port = httpServer.getAddress().getPort();
        httpServer.start();
    }

    @AfterEach
    void tearDown() {
        httpServer.stop(0);
    }

    /** Register a stub that responds with the given JSON body and status code. */
    private void stub(String path, int statusCode, String responseBody) {
        httpServer.createContext(path, exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private KnowledgeGraphTool tool() {
        return new KnowledgeGraphTool("http://localhost:" + port, om);
    }

    private ObjectNode params(String action) {
        ObjectNode p = om.createObjectNode();
        p.put("action", action);
        return p;
    }

    // ─── tool metadata ────────────────────────────────────────────────────────

    @Test
    @DisplayName("tool description mentions list_fact_sheets")
    void description_mentionsListFactSheets() {
        assertTrue(tool().description().contains("list_fact_sheets"),
                "description must mention list_fact_sheets so LLMs discover it");
    }

    @Test
    @DisplayName("compactHint is non-null and mentions list_fact_sheets")
    void compactHint_mentionsListFactSheets() {
        String hint = tool().compactHint();
        assertNotNull(hint, "compactHint must not be null");
        assertTrue(hint.contains("list_fact_sheets"),
                "compactHint must mention list_fact_sheets as the discovery action");
    }

    @Test
    @DisplayName("parameter schema contains 'name' for create_fact_sheet")
    void schema_hasNameParam() {
        var schema = tool().parameterSchema();
        assertFalse(schema.path("properties").path("name").isMissingNode(),
                "schema must have a 'name' property for create_fact_sheet");
    }

    // ─── list_fact_sheets ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("list_fact_sheets")
    class ListFactSheets {

        @Test
        @DisplayName("returns sheets with id, name, active status")
        void returnsSheets() throws Exception {
            stub("/api/fact-sheets", 200, """
                    [
                      {"id": 1, "name": "Default", "isActive": true, "factCount": 5},
                      {"id": 2, "name": "Project Alpha", "isActive": false, "factCount": 12}
                    ]
                    """);

            ToolResult result = tool().execute(params("list_fact_sheets"), ctx);

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("Default"), "output must list sheet name");
            assertTrue(result.getOutput().contains("id=1"), "output must include id");
            assertTrue(result.getOutput().contains("[ACTIVE]"), "active sheet must be flagged");
            assertTrue(result.getOutput().contains("Project Alpha"), "second sheet must appear");
        }

        @Test
        @DisplayName("empty list returns gracefully")
        void emptyList() throws Exception {
            stub("/api/fact-sheets", 200, "[]");

            ToolResult result = tool().execute(params("list_fact_sheets"), ctx);

            assertFalse(result.isError(), result.getOutput());
        }
    }

    // ─── get_fact_sheet ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("get_fact_sheet")
    class GetFactSheet {

        @Test
        @DisplayName("returns sheet details for given id")
        void returnsDetails() throws Exception {
            stub("/api/fact-sheets/7", 200, """
                    {"id": 7, "name": "Research", "isActive": false, "factCount": 3,
                     "indexedCount": 2, "description": "Research docs"}
                    """);

            ObjectNode p = params("get_fact_sheet");
            p.put("fact_sheet_id", 7);

            ToolResult result = tool().execute(p, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("7"), "id must appear in output");
            assertTrue(result.getOutput().contains("Research"), "name must appear in output");
        }

        @Test
        @DisplayName("missing fact_sheet_id returns error")
        void missingId_returnsError() throws Exception {
            ToolResult result = tool().execute(params("get_fact_sheet"), ctx);

            assertTrue(result.isError(), "Expected error when fact_sheet_id is missing");
            assertTrue(result.getOutput().toLowerCase().contains("fact_sheet_id"),
                    "error should mention fact_sheet_id");
        }
    }

    // ─── get_active_fact_sheet ────────────────────────────────────────────────

    @Nested
    @DisplayName("get_active_fact_sheet")
    class GetActiveFactSheet {

        @Test
        @DisplayName("returns the active sheet")
        void returnsActiveSheet() throws Exception {
            stub("/api/fact-sheets/active", 200, """
                    {"id": 1, "name": "Default", "isActive": true, "factCount": 10,
                     "indexedCount": 8}
                    """);

            ToolResult result = tool().execute(params("get_active_fact_sheet"), ctx);

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("Default"), "sheet name must appear");
            assertTrue(result.getOutput().contains("1"), "id must appear");
        }
    }

    // ─── create_fact_sheet ────────────────────────────────────────────────────

    @Nested
    @DisplayName("create_fact_sheet")
    class CreateFactSheet {

        @Test
        @DisplayName("creates a sheet with given name")
        void createsSheet() throws Exception {
            stub("/api/fact-sheets", 201, """
                    {"id": 99, "name": "My New Sheet", "isActive": false, "factCount": 0}
                    """);

            ObjectNode p = params("create_fact_sheet");
            p.put("name", "My New Sheet");

            ToolResult result = tool().execute(p, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("My New Sheet"),
                    "output must include the created sheet name");
            assertTrue(result.getOutput().contains("99"),
                    "output must include the created sheet id");
        }

        @Test
        @DisplayName("also accepts 'title' as name alias")
        void acceptsTitleAlias() throws Exception {
            stub("/api/fact-sheets", 201, """
                    {"id": 100, "name": "Via Title", "isActive": false, "factCount": 0}
                    """);

            ObjectNode p = params("create_fact_sheet");
            p.put("title", "Via Title");

            ToolResult result = tool().execute(p, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("Via Title"));
        }

        @Test
        @DisplayName("missing name/title returns error")
        void missingName_returnsError() throws Exception {
            ToolResult result = tool().execute(params("create_fact_sheet"), ctx);

            assertTrue(result.isError(), "Expected error when name is missing");
            assertTrue(result.getOutput().toLowerCase().contains("name"),
                    "error should mention 'name'");
        }
    }

    // ─── activate_fact_sheet ──────────────────────────────────────────────────

    @Nested
    @DisplayName("activate_fact_sheet")
    class ActivateFactSheet {

        @Test
        @DisplayName("activates a sheet by id")
        void activatesSheet() throws Exception {
            stub("/api/fact-sheets/5/activate", 200, """
                    {"id": 5, "name": "Project Beta", "isActive": true, "factCount": 7}
                    """);

            ObjectNode p = params("activate_fact_sheet");
            p.put("fact_sheet_id", 5);

            ToolResult result = tool().execute(p, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("Project Beta"),
                    "activated sheet name must appear");
            assertTrue(result.getOutput().contains("5"),
                    "activated sheet id must appear");
        }

        @Test
        @DisplayName("missing fact_sheet_id returns error")
        void missingId_returnsError() throws Exception {
            ToolResult result = tool().execute(params("activate_fact_sheet"), ctx);

            assertTrue(result.isError(), "Expected error when fact_sheet_id is missing");
            assertTrue(result.getOutput().toLowerCase().contains("fact_sheet_id"),
                    "error should mention fact_sheet_id");
        }
    }

    // ─── unknown action falls through ─────────────────────────────────────────

    @Test
    @DisplayName("unknown action returns error mentioning known fact-sheet actions")
    void unknownAction_returnsError() throws Exception {
        ObjectNode p = params("does_not_exist");
        ToolResult result = tool().execute(p, ctx);

        assertTrue(result.isError(), "Unknown action should return an error");
        assertTrue(result.getOutput().contains("list_fact_sheets"),
                "error message should list fact-sheet actions as valid options");
    }
}
