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
 * Unit tests for {@link GraphEmbeddingsTool}.
 *
 * <p>Verifies: tool metadata, parameter schema shape, required-param guards,
 * missing action guard, and no-url guard. HTTP calls are not made (no live
 * backend); actions that require param validation return param errors without
 * hitting the network.
 */
class GraphEmbeddingsToolTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String DUMMY_URL = "http://localhost:8080";

    private GraphEmbeddingsTool tool;

    @BeforeEach
    void setUp() {
        tool = new GraphEmbeddingsTool(DUMMY_URL, OM);
    }

    // ── id / description / compactHint ──────────────────────────────────────────

    @Test
    void id_isGraphEmbeddings() {
        assertEquals("graph_embeddings", tool.id());
    }

    @Test
    void description_mentionsAllActions() {
        String desc = tool.description();
        assertNotNull(desc);
        assertTrue(desc.contains("train"));
        assertTrue(desc.contains("predict_tails"));
        assertTrue(desc.contains("predict_heads"));
        assertTrue(desc.contains("predict_relations"));
        assertTrue(desc.contains("similar"));
        assertTrue(desc.contains("score"));
        assertTrue(desc.contains("job_status"));
        assertTrue(desc.contains("cancel"));
        assertTrue(desc.contains("algorithms"));
    }

    @Test
    void compactHint_isNonNullAndContainsCoreActions() {
        String hint = tool.compactHint();
        assertNotNull(hint);
        assertTrue(hint.contains("train"));
        assertTrue(hint.contains("predict_tails"));
        assertTrue(hint.contains("similar"));
        assertTrue(hint.contains("score"));
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
        assertTrue(props.has("fact_sheet_id"));
        assertTrue(props.has("algorithm"));
        assertTrue(props.has("job_id"));
        assertTrue(props.has("head"));
        assertTrue(props.has("relation"));
        assertTrue(props.has("tail"));
        assertTrue(props.has("entity_name"));
        assertTrue(props.has("top_k"));
    }

    // ── permissionKey / mcpAnnotations ──────────────────────────────────────────

    @Test
    void permissionKey_isGraphEmbeddings() {
        assertEquals("graph_embeddings", tool.permissionKey());
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
        params.put("action", "totally_unknown_xyz");
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Unknown action"));
    }

    @Test
    void execute_nullBaseUrl_usesProjectLocalBackend() throws ToolExecutionException {
        GraphEmbeddingsTool noUrl = new GraphEmbeddingsTool(null, OM);
        ObjectNode params = OM.createObjectNode();
        params.put("action", "train");
        params.put("fact_sheet_id", 1);
        ToolResult result = noUrl.execute(params, ctx());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("graph_embeddings local error"));
        assertTrue(result.getOutput().contains("project-local"));
    }

    // ── missing required param guards (no network needed) ────────────────────

    @Test
    void execute_jobStatus_missingJobId_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "job_status");
        // no job_id
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
        assertTrue(result.getOutput().toLowerCase().contains("job_id"));
    }

    @Test
    void execute_cancel_missingJobId_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "cancel");
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
        assertTrue(result.getOutput().toLowerCase().contains("job_id"));
    }

    @Test
    void execute_score_missingParams_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "score");
        params.put("fact_sheet_id", 1);
        // Missing head, relation, tail
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
    }

    @Test
    void execute_predictTails_missingRelation_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "predict_tails");
        params.put("fact_sheet_id", 1);
        params.put("head", "Alice");
        // Missing relation
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
    }

    @Test
    void execute_similar_missingEntityName_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "similar");
        params.put("fact_sheet_id", 1);
        // no entity_name
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
        assertTrue(result.getOutput().toLowerCase().contains("entity_name"));
    }

    // ── actions that fail fast with connect-refused (no guard possible) ───────

    @Test
    void execute_train_noServer_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "train");
        params.put("fact_sheet_id", 42);
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError(), "Expected connect error");
    }

    @Test
    void execute_jobs_noServer_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "jobs");
        params.put("fact_sheet_id", 42);
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
    }

    @Test
    void execute_algorithms_noServer_returnsError() throws ToolExecutionException {
        ObjectNode params = OM.createObjectNode();
        params.put("action", "algorithms");
        ToolResult result = tool.execute(params, ctx());
        assertTrue(result.isError());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private ToolContext ctx() {
        PermissionService perms = new PermissionService();
        perms.setUserOverride("graph_embeddings", PermissionService.PermissionLevel.ALLOW);
        return new ToolContext("test-session", null, perms, Paths.get("."), null);
    }
}
