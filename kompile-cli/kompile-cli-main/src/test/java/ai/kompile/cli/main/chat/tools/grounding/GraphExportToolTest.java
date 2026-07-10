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
import java.nio.file.Paths;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for the {@code graph_export} MCP tool — the agent-facing surface that downloads
 * the live knowledge graph as a {@code .kgraph} file. HTTP is intercepted with
 * {@code MockRestServiceServer}; no server runs.
 */
class GraphExportToolTest {

    @TempDir
    Path tempDir;

    private ObjectMapper om;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("coder").enabledTools(Set.of("*")).build();
        PermissionService perms = new PermissionService();
        perms.setUserOverride("graph_export", PermissionService.PermissionLevel.ALLOW);
        ToolRegistry registry = new ToolRegistry(om);
        ctx = new ToolContext("test-session", agent, perms, Paths.get("."), registry);
    }

    @Test
    void metadata() {
        GraphExportTool tool = new GraphExportTool((String) null, om);
        assertEquals("graph_export", tool.id());
        assertEquals("graph_export", tool.permissionKey());
        assertEquals(McpToolAnnotations.WRITE, tool.mcpAnnotations());
    }

    @Test
    void schemaHasPathRequired() {
        GraphExportTool tool = new GraphExportTool((String) null, om);
        JsonNode schema = tool.parameterSchema();
        assertTrue(schema.path("required").toString().contains("path"));
        assertNotNull(schema.path("properties").path("path"));
        assertNotNull(schema.path("properties").path("factSheetId"));
    }

    @Test
    void missingPath_returnsError() throws Exception {
        GraphExportTool tool = new GraphExportTool((String) null, om);
        ToolResult result = tool.execute(om.createObjectNode(), ctx);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("path"));
    }

    @Test
    void backendUnavailable_returnsError() throws Exception {
        // null baseUrl → GroundingBackendClient.isAvailable() == false
        GraphExportTool tool = new GraphExportTool((String) null, om);
        ObjectNode params = om.createObjectNode();
        params.put("path", tempDir.resolve("out.kgraph").toString());
        ToolResult result = tool.execute(params, ctx);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("kompile-app"));
    }

    @Test
    void exportsGraphAndWritesFile() throws Exception {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
        GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
        GraphExportTool tool = new GraphExportTool(client, om);

        byte[] kgraphBytes = new byte[]{1, 2, 3};

        mockServer.expect(requestTo("http://localhost/api/graph/unified/export"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(kgraphBytes, MediaType.APPLICATION_OCTET_STREAM));

        Path outFile = tempDir.resolve("exported.kgraph");
        ObjectNode params = om.createObjectNode();
        params.put("path", outFile.toString());

        ToolResult result = tool.execute(params, ctx);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("3 bytes"), "Expected byte count in output: " + result.getOutput());
        assertTrue(Files.exists(outFile), "Output file must exist");
        assertArrayEquals(kgraphBytes, Files.readAllBytes(outFile));
        assertEquals(outFile.toString(), result.getMetadata().get("path"));
        assertEquals(3, result.getMetadata().get("bytes"));
        mockServer.verify();
    }
}
