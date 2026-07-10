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
 * Unit tests for {@link GraphSimulateTool}.
 *
 * <p>Verifies: tool metadata, parameter schema shape, required-param guards,
 * missing action guard, and no-url guard. HTTP calls are not made.
 */
class GraphSimulateToolTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String DUMMY_URL = "http://localhost:8080";

    private GraphSimulateTool tool;

    @BeforeEach
    void setUp() {
        tool = new GraphSimulateTool(DUMMY_URL, OM);
    }

    // ── id / description / compactHint ──────────────────────────────────────────

    @Test
    void id_isGraphSimulate() {
        assertEquals("graph_simulate", tool.id());
    }

    @Test
    void description_mentionsAllActions() {
        String desc = tool.description();
        assertNotNull(desc);
        assertTrue(desc.contains("scenarios"));
        assertTrue(desc.contains("create_run"));
        assertTrue(desc.contains("step"));
        assertTrue(desc.contains("play"));
        assertTrue(desc.contains("reason"));
        assertTrue(desc.contains("ground_truth"));
        assertTrue(desc.contains("promote"));
        assertTrue(desc.contains("delete"));
    }

    @Test
    void compactHint_isNonNullAndContainsKeyWords() {
        String hint = tool.compactHint();
        assertNotNull(hint);
        assertTrue(hint.contains("scenarios"));
        assertTrue(hint.contains("create_run"));
        assertTrue(hint.contains("promote"));
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
        boolean actionRequired = false;
        for (var node : schema.path("required")) {
            if ("action".equals(node.asText())) { actionRequired = true; break; }
        }
        assertTrue(actionRequired, "action must be in required array");
    }

    @Test
    void parameterSchema_hasKeyProps() {
        var props = tool.parameterSchema().path("properties");
        assertTrue(props.has("action"));
        assertTrue(props.has("scenario_id"));
        assertTrue(props.has("run_id"));
        assertTrue(props.has("name"));
        assertTrue(props.has("seed"));
        assertTrue(props.has("mode"));
        assertTrue(props.has("dry_run"));
    }

    // ── permissionKey / mcpAnnotations ──────────────────────────────────────────

    @Test
    void permissionKey_isGraphSimulate() {
        assertEquals("graph_simulate", tool.permissionKey());
    }

    @Test
    void mcpAnnotations_isWrite() {
        assertEquals(McpToolAnnotations.WRITE, tool.mcpAnnotations());
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
        params.put("action", "totally_unknown_action_xyz");
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Unknown action"));
    }

    @Test
    void execute_nullBaseUrl_returnsError() throws ToolExecutionException {
        GraphSimulateTool noUrl = new GraphSimulateTool(null, OM);
        ObjectNode params = OM.createObjectNode();
        params.put("action", "scenarios");
        ToolResult result = noUrl.execute(params, ctx());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("kompile-app"));
    }

    // ── missing required param guards ────────────────────────────────────────

    @Test
    void execute_createRun_missingScenarioId_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "create_run");
        // no scenario_id
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("scenario_id"));
    }

    @Test
    void execute_run_missingRunId_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "run");
        // no run_id
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("run_id"));
    }

    @Test
    void execute_step_missingRunId_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "step");
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("run_id"));
    }

    @Test
    void execute_promote_missingRunId_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "promote");
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
    }

    @Test
    void execute_delete_missingRunId_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "delete");
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
    }

    @Test
    void execute_groundTruth_missingRunId_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "ground_truth");
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
    }

    // ── actions that fail fast with connect-refused ───────────────────────────

    @Test
    void execute_scenarios_noServer_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "scenarios");
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError(), "Expected connect error when no server is running");
    }

    @Test
    void execute_runs_noServer_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "runs");
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private ToolContext ctx() {
        PermissionService perms = new PermissionService();
        perms.setUserOverride("graph_simulate", PermissionService.PermissionLevel.ALLOW);
        return new ToolContext("test-session", null, perms, Paths.get("."), null);
    }
}
