/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Paths;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CrawlDiscoveryToolTest {
    private ObjectMapper mapper;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("crawler").enabledTools(Set.of("*")).build();
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("crawl_discover", PermissionService.PermissionLevel.ALLOW);
        context = new ToolContext("crawl-discovery-test", agent, permissions, Paths.get("."),
                new ToolRegistry(mapper));
    }

    @Test
    void metadataAndSchemaAreReadOnlyAndBounded() {
        CrawlDiscoveryTool tool = new CrawlDiscoveryTool((String) null, mapper);

        assertEquals("crawl_discover", tool.id());
        assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
        assertTrue(tool.compactHint().length() <= 200);
        assertTrue(tool.parameterSchema().path("properties").path("section")
                .path("enum").toString().contains("pipelines"));
        assertTrue(tool.parameterSchema().path("properties").path("section")
                .path("enum").toString().contains("code_projects"));
    }

    @Test
    void discoversLiveSourceTypes() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        CrawlDiscoveryTool tool = new CrawlDiscoveryTool(
                new GroundingBackendClient("http://crawl", restTemplate), mapper);
        server.expect(requestTo("http://crawl/api/unified-crawl/source-types"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"type\":\"FILE\",\"available\":true}]",
                        MediaType.APPLICATION_JSON));

        ObjectNode params = mapper.createObjectNode().put("section", "sources");
        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("FILE"));
        assertEquals(1, result.getMetadata().get("endpointsSucceeded"));
        server.verify();
    }

    @Test
    void pipelineDiscoveryCombinesStaticKindsWithLiveComponents() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        CrawlDiscoveryTool tool = new CrawlDiscoveryTool(
                new GroundingBackendClient("http://crawl", restTemplate), mapper);

        expect(server, "/api/unified-crawl/steps",
                "[{\"id\":\"GRAPH_EXTRACTION\",\"dependencies\":[\"CHUNKING\"]}]");
        expect(server, "/api/documents/loaders", "[{\"name\":\"pdf\"}]");
        expect(server, "/api/documents/chunkers", "[{\"name\":\"table-aware\"}]");
        expect(server, "/api/unified-crawl/processing-route", "{\"pdfRoutingMode\":\"AUTO\"}");
        expect(server, "/api/unified-crawl/pdf-routing-modes", "[{\"value\":\"AUTO\"}]");
        expect(server, "/api/unified-crawl/processing-backend-types",
                "[{\"value\":\"CLI_AGENT\"}]");

        ToolResult result = tool.execute(
                mapper.createObjectNode().put("section", "pipelines"), context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("STANDARD_TEXT"));
        assertTrue(result.getOutput().contains("TABLE_AWARE"));
        assertTrue(result.getOutput().contains("GRAPH_EXTRACTION"));
        assertTrue(result.getOutput().contains("table-aware"));
        assertTrue(result.getOutput().contains("crawl_documents"));
        assertEquals(6, result.getMetadata().get("endpointsSucceeded"));
        server.verify();
    }

    @Test
    void discoversRegisteredKompileCodeProjectsAsCrawlSources() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        CrawlDiscoveryTool tool = new CrawlDiscoveryTool(
                new GroundingBackendClient("http://crawl", restTemplate), mapper);
        expect(server, "/api/projects/current/code-projects", """
                [{
                  "id":"kompile",
                  "codeProjectId":"kompile",
                  "name":"Kompile",
                  "rootPath":"/workspace/kompile",
                  "lifecycle":"ACTIVE",
                  "autoIndex":true
                }]
                """);

        ToolResult result = tool.execute(
                mapper.createObjectNode().put("section", "code_projects"), context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("\"codeProjects\""));
        assertTrue(result.getOutput().contains("/workspace/kompile"));
        assertEquals(1, result.getMetadata().get("endpointsSucceeded"));
        server.verify();
    }

    @Test
    void invalidSectionFailsBeforeBackendLookup() throws Exception {
        CrawlDiscoveryTool tool = new CrawlDiscoveryTool((String) null, mapper);

        ToolResult result = tool.execute(
                mapper.createObjectNode().put("section", "mystery"), context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Valid sections"));
    }

    private void expect(MockRestServiceServer server, String path, String response) {
        server.expect(requestTo("http://crawl" + path))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
}
