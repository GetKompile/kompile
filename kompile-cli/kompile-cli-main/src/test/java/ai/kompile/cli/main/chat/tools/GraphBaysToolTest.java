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
 * Unit tests for {@link GraphBayesTool}.
 *
 * <p>Verifies: tool metadata, parameter schema, required-param guards, missing
 * action guard, and no-url guard. HTTP calls are not made (no live backend).
 */
class GraphBaysToolTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private GraphBayesTool tool;

    @BeforeEach
    void setUp() {
        tool = new GraphBayesTool("http://localhost:8080", OM);
    }

    // ── id / description / compactHint ──────────────────────────────────────────

    @Test
    void id_isGraphBayes() {
        assertEquals("graph_bayes", tool.id());
    }

    @Test
    void description_mentionsAllActions() {
        String desc = tool.description();
        assertNotNull(desc);
        assertTrue(desc.contains("query"));
        assertTrue(desc.contains("mpe"));
        assertTrue(desc.contains("sensitivity"));
        assertTrue(desc.contains("whatif"));
        assertTrue(desc.contains("stats"));
    }

    @Test
    void compactHint_isNonNullAndContainsActions() {
        String hint = tool.compactHint();
        assertNotNull(hint);
        assertTrue(hint.contains("action="));
        assertTrue(hint.contains("query"));
        assertTrue(hint.contains("mpe"));
        assertTrue(hint.contains("sensitivity"));
    }

    // ── parameterSchema ──────────────────────────────────────────────────────────

    @Test
    void parameterSchema_isObject() {
        var schema = tool.parameterSchema();
        assertNotNull(schema);
        assertEquals("object", schema.path("type").asText());
    }

    @Test
    void parameterSchema_hasActionAsRequired() {
        var schema = tool.parameterSchema();
        assertTrue(schema.has("properties"));
        assertTrue(schema.path("properties").has("action"));
        boolean actionRequired = false;
        for (var node : schema.path("required")) {
            if ("action".equals(node.asText())) { actionRequired = true; break; }
        }
        assertTrue(actionRequired, "action must be in required array");
    }

    @Test
    void parameterSchema_hasKeyProps() {
        var props = tool.parameterSchema().path("properties");
        assertTrue(props.has("node_id"));
        assertTrue(props.has("seed_node_ids"));
        assertTrue(props.has("fact_sheet_id"));
        assertTrue(props.has("evidence"));
        assertTrue(props.has("hypothetical_evidence"));
        assertTrue(props.has("max_depth"));
        assertTrue(props.has("max_nodes"));
    }

    // ── permissionKey / mcpAnnotations ──────────────────────────────────────────

    @Test
    void permissionKey_isGraphBayes() {
        assertEquals("graph_bayes", tool.permissionKey());
    }

    @Test
    void mcpAnnotations_isReadOnly() {
        assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
    }

    // ── guard paths that do NOT require a live server ──────────────────────────

    @Test
    void execute_missingAction_returnsError() throws ToolExecutionException {
        ToolResult result = tool.execute(OM.createObjectNode(), ctx());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("action is required"));
    }

    @Test
    void execute_unknownAction_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "nonexistent_action_xyz");
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Unknown action"));
    }

    @Test
    void execute_nullBaseUrl_returnsError() throws ToolExecutionException {
        GraphBayesTool noUrl = new GraphBayesTool(null, OM);
        ObjectNode params = OM.createObjectNode();
        params.put("action", "query");
        ToolResult result = noUrl.execute(params, ctx());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("kompile-app"));
    }

    @Test
    void execute_emptyBaseUrl_returnsError() throws ToolExecutionException {
        GraphBayesTool noUrl = new GraphBayesTool("", OM);
        ObjectNode params = OM.createObjectNode();
        params.put("action", "stats");
        ToolResult result = noUrl.execute(params, ctx());
        assertTrue(result.isError());
    }

    // ── action dispatch — connection-refused paths ─────────────────────────────
    // These dispatch correctly but fail fast because no server is running.

    @Test
    void execute_query_noServerReturnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "query");
        params.put("node_id", "test-node-1");
        ToolResult result = tool.execute(params, ctx());
        // Either connect error or HTTP error — either way must be an error
        assertTrue(result.isError(), "Expected error when no server is running");
    }

    @Test
    void execute_stats_noServerReturnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "stats");
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
    }

    @Test
    void execute_mpe_noServerReturnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "mpe");
        params.put("node_id", "test-node");
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
    }

    @Test
    void execute_sensitivity_noServerReturnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "sensitivity");
        params.put("node_id", "test-node");
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
    }

    @Test
    void execute_whatif_noServerReturnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "whatif");
        params.put("node_id", "test-node");
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private ToolContext ctx() {
        PermissionService perms = new PermissionService();
        perms.setUserOverride("graph_bayes", PermissionService.PermissionLevel.ALLOW);
        return new ToolContext("test-session", null, perms, Paths.get("."), null);
    }
}
