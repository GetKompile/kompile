/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProcessMiningCliTool}.
 *
 * <p>Verifies: tool metadata, parameter schema, required-param guards, and action
 * dispatch. HTTP calls are not made (no live backend) — tests that need HTTP
 * exercise only the error-path when no server is running.
 */
class ProcessMiningCliToolTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String DUMMY_URL = "http://localhost:8080";

    private ProcessMiningCliTool tool;

    @BeforeEach
    void setUp() {
        tool = new ProcessMiningCliTool(DUMMY_URL, OM);
    }

    // ── id / description / compactHint ──────────────────────────────────────────

    @Test
    void id_isProcessMining() {
        assertEquals("process_mining", tool.id());
    }

    @Test
    void description_mentionsAllActions() {
        String desc = tool.description();
        assertNotNull(desc);
        assertTrue(desc.contains("discover"));
        assertTrue(desc.contains("entailment"));
        assertTrue(desc.contains("conformance"));
        assertTrue(desc.contains("bpmn"));
        assertTrue(desc.contains("suggestions"));
        assertTrue(desc.contains("config_get"));
        assertTrue(desc.contains("config_update"));
    }

    @Test
    void compactHint_isNonNull() {
        assertNotNull(tool.compactHint());
        assertTrue(tool.compactHint().contains("action="));
    }

    // ── parameterSchema ──────────────────────────────────────────────────────────

    @Test
    void parameterSchema_hasActionAsRequired() {
        var schema = tool.parameterSchema();
        assertNotNull(schema);
        assertEquals("object", schema.path("type").asText());
        assertTrue(schema.has("properties"));
        assertTrue(schema.path("properties").has("action"));
        // action is in required array
        boolean actionRequired = false;
        for (var node : schema.path("required")) {
            if ("action".equals(node.asText())) { actionRequired = true; break; }
        }
        assertTrue(actionRequired, "action should be in required array");
    }

    @Test
    void parameterSchema_hasFactSheetId() {
        assertTrue(tool.parameterSchema().path("properties").has("fact_sheet_id"));
    }

    @Test
    void parameterSchema_hasSuggestionId() {
        assertTrue(tool.parameterSchema().path("properties").has("suggestion_id"));
    }

    @Test
    void parameterSchema_hasConfigJson() {
        assertTrue(tool.parameterSchema().path("properties").has("config_json"));
    }

    // ── permissionKey / mcpAnnotations ──────────────────────────────────────────

    @Test
    void permissionKey_isProcessMining() {
        assertEquals("process_mining", tool.permissionKey());
    }

    @Test
    void mcpAnnotations_isReadOnly() {
        assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
    }

    // ── no-baseUrl guard ────────────────────────────────────────────────────────

    @Test
    void execute_noBaseUrl_returnsError() throws ToolExecutionException {
        ProcessMiningCliTool noUrl = new ProcessMiningCliTool(null, OM);
        ObjectNode params = OM.createObjectNode();
        params.put("action", "discover");
        params.put("fact_sheet_id", 1);

        ToolResult result = noUrl.execute(params, stubContext());
        assertTrue(result.isError(), "Should return error when baseUrl is null");
    }

    @Test
    void execute_emptyBaseUrl_returnsError() throws ToolExecutionException {
        ProcessMiningCliTool noUrl = new ProcessMiningCliTool("", OM);
        ObjectNode params = OM.createObjectNode();
        params.put("action", "discover");
        params.put("fact_sheet_id", 1);

        ToolResult result = noUrl.execute(params, stubContext());
        assertTrue(result.isError());
    }

    // ── missing action guard ─────────────────────────────────────────────────────

    @Test
    void execute_missingAction_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        ToolResult result = tool.execute(params, stubContext());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("action is required"));
    }

    @Test
    void execute_unknownAction_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "nonexistent_action");
        ToolResult result = tool.execute(params, stubContext());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Unknown action"));
    }

    // ── missing required fact_sheet_id guard (connection failure expected) ───────
    // These tests hit the real network (which fails fast) to exercise param validation.

    @Test
    void execute_discover_missingFactSheetId_getsConnectError() throws ToolExecutionException {
        // With a real baseUrl but no server, and no fact_sheet_id, action still dispatches.
        // It will either get a ConnectException (fast fail) or validate param and error.
        // Either way, result must be an error.
        ObjectNode params = OM.createObjectNode();
        params.put("action", "discover");
        // no fact_sheet_id → should return error about missing param OR connection refused
        ToolResult result = tool.execute(params, stubContext());
        assertTrue(result.isError(),
                "Expected error when fact_sheet_id is missing or server is not running");
    }

    @Test
    void execute_suggestion_missingSuggestionId_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "suggestion");
        // no suggestion_id
        ToolResult result = tool.execute(params, stubContext());
        assertTrue(result.isError());
        assertTrue(result.getOutput().toLowerCase().contains("suggestion_id"),
                "Expected error about missing suggestion_id, got: " + result.getOutput());
    }

    @Test
    void execute_configUpdate_missingConfigJson_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "config_update");
        // no config_json
        ToolResult result = tool.execute(params, stubContext());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("config_json"),
                "Expected error about missing config_json, got: " + result.getOutput());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private ToolContext stubContext() {
        PermissionService perms = new PermissionService();
        perms.setUserOverride("process_mining", PermissionService.PermissionLevel.ALLOW);
        return new ToolContext("test-session", null, perms, Paths.get("."), null);
    }
}
