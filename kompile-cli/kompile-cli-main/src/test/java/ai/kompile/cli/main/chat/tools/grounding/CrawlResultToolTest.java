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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CrawlResultToolTest {
    @TempDir
    Path tempDir;

    @Test
    void returnsManagedResultWithExecutableGraphHandoff() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        CrawlResultTool tool = new CrawlResultTool(
                new GroundingBackendClient("http://crawl", restTemplate), mapper);

        server.expect(requestTo("http://crawl/api/unified-crawl/jobs/crawl-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "jobId":"crawl-123",
                          "status":"COMPLETED",
                          "factSheetId":42,
                          "entitiesExtracted":12,
                          "relationshipsExtracted":7
                        }
                        """, MediaType.APPLICATION_JSON));

        PermissionService permissions = new PermissionService();
        AgentConfig agent = AgentConfig.builder("reader").enabledTools(Set.of("crawl_result")).build();
        ToolContext context = new ToolContext("result-test", agent, permissions, tempDir,
                new ToolRegistry(mapper));
        ObjectNode params = mapper.createObjectNode().put("jobId", "crawl-123");

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), result.getOutput());
        assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
        assertEquals("crawl-123", result.getMetadata().get("jobId"));
        Map<String, Object> handle = castMap(result.getMetadata().get("crawlResult"));
        assertEquals("kompile-crawl-result/v1", handle.get("schema"));
        assertEquals(true, handle.get("terminal"));
        assertTrue(((List<?>) result.getMetadata().get("nextActions")).stream()
                .map(CrawlResultToolTest::castMap)
                .anyMatch(action -> "graph_reasoning_query".equals(action.get("tool"))
                        && Map.of("factSheetId", 42L, "operation", "OVERVIEW")
                        .equals(action.get("arguments"))));
        server.verify();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
