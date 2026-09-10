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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for the {@code graph_import} MCP tool — the agent-facing surface that loads a
 * {@code .kgraph} into the live knowledge graph so the {@code ask_graph_*} / {@code graph_reason}
 * tools can reason over it. HTTP is intercepted with {@code MockRestServiceServer}; no server runs.
 */
class GraphImportToolTest {

    @TempDir
    Path tempDir;

    private ObjectMapper om;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("coder").enabledTools(Set.of("*")).build();
        PermissionService perms = new PermissionService();
        perms.setUserOverride("graph_import", PermissionService.PermissionLevel.ALLOW);
        ToolRegistry registry = new ToolRegistry(om);
        ctx = new ToolContext("test-session", agent, perms, tempDir, registry);
    }

    @Test
    void metadata() {
        GraphImportTool tool = new GraphImportTool((String) null, om);
        assertEquals("graph_import", tool.id());
        assertEquals("graph_import", tool.permissionKey());
        assertEquals(McpToolAnnotations.WRITE, tool.mcpAnnotations());
    }

    @Test
    void schemaHasPathRequired() {
        GraphImportTool tool = new GraphImportTool((String) null, om);
        JsonNode schema = tool.parameterSchema();
        assertTrue(schema.path("required").toString().contains("path"));
        assertNotNull(schema.path("properties").path("path"));
        assertNotNull(schema.path("properties").path("factSheetId"));
        assertNotNull(schema.path("properties").path("requireManaged"));
    }

    @Test
    void missingPath_returnsError() throws Exception {
        GraphImportTool tool = new GraphImportTool((String) null, om);
        ToolResult result = tool.execute(om.createObjectNode(), ctx);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("path"));
    }

    @Test
    void nullBaseUrl_fallsBackToProjectLocalBackend() throws Exception {
        GraphImportTool tool = new GraphImportTool((String) null, om);
        ObjectNode params = om.createObjectNode();
        params.put("path", "missing-local.kgraph");
        ToolResult result = tool.execute(params, ctx);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("No .kgraph file"));
    }

    @Test
    void missingFile_returnsError() throws Exception {
        GraphImportTool tool = new GraphImportTool("http://localhost", om);
        ObjectNode params = om.createObjectNode();
        params.put("path", tempDir.resolve("does-not-exist.kgraph").toString());
        ToolResult result = tool.execute(params, ctx);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("No .kgraph file"));
    }

    @Test
    void loadsAKgraphAndReportsReasoningReady() throws Exception {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
        GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
        GraphImportTool tool = new GraphImportTool(client, om);

        Path kg = tempDir.resolve("g.kgraph");
        Files.write(kg, new byte[] {1, 2, 3});

        mockServer.expect(requestTo("http://localhost/api/graph/unified/import"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"nodes\":5,\"edges\":3,\"embeddings\":5,\"atoms\":7,"
                                + "\"profile\":\"MANAGED\",\"durability\":\"COMPENSATING\"}",
                        MediaType.APPLICATION_JSON));

        ObjectNode params = om.createObjectNode();
        params.put("path", kg.toString());
        params.put("factSheetId", 5_000_000_000L);
        params.put("requireManaged", true);

        ToolResult result = tool.execute(params, ctx);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("reasoning-ready"),
                "projected>0 must surface reasoning-readiness: " + result.getOutput());
        assertEquals(7, result.getMetadata().get("atoms"));
        assertEquals(5, result.getMetadata().get("nodes"));
        assertEquals(5_000_000_000L, result.getMetadata().get("factSheetId"));
        assertEquals("MANAGED", result.getMetadata().get("profile"));
        assertTrue(result.getOutput().contains("MANAGED (COMPENSATING)"));
        mockServer.verify();
    }
}
