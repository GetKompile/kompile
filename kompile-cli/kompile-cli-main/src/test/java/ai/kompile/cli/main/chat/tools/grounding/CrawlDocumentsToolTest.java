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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
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

class CrawlDocumentsToolTest {
    private ObjectMapper mapper;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("crawler").enabledTools(Set.of("*")).build();
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("crawl_documents", PermissionService.PermissionLevel.ALLOW);
        context = new ToolContext("crawl-test", agent, permissions, Paths.get("."),
                new ToolRegistry(mapper));
    }

    @Test
    void metadataAndSchemaExposeAgentFacingContract() {
        CrawlDocumentsTool tool = new CrawlDocumentsTool((String) null, mapper);

        assertEquals("crawl_documents", tool.id());
        assertEquals(McpToolAnnotations.WRITE, tool.mcpAnnotations());
        assertTrue(tool.compactHint().length() <= 200);

        JsonNode schema = tool.parameterSchema();
        assertTrue(schema.path("anyOf").toString().contains("documents"));
        assertTrue(schema.path("anyOf").toString().contains("codeProjects"));
        assertTrue(schema.path("properties").has("documents"));
        assertTrue(schema.path("properties").has("codeProjects"));
        assertTrue(schema.path("properties").has("knowledgeBase"));
        assertTrue(schema.path("properties").has("pipelines"));
        assertTrue(schema.path("properties").has("routeRules"));
        assertTrue(schema.path("properties").has("runtimeConfig"));
        assertTrue(schema.path("properties").has("embeddingTraining"));
        assertTrue(schema.path("properties").has("config"));
        assertTrue(schema.path("properties").path("documents").path("items")
                .path("properties").has("pipelineId"));
    }

    @Test
    void missingDocumentsAndCodeProjectsFailsBeforeBackendLookup() throws Exception {
        CrawlDocumentsTool tool = new CrawlDocumentsTool((String) null, mapper);

        ToolResult result = tool.execute(mapper.createObjectNode(), context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("document"));
        assertTrue(result.getOutput().contains("codeProjects"));
    }

    @Test
    void startsSelectedDocumentsWithKnowledgeBaseAndPipelineConfiguration() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        CrawlDocumentsTool tool = new CrawlDocumentsTool(
                new GroundingBackendClient("http://crawl", restTemplate), mapper);

        String expected = """
                {
                  "name": "quarterly reports",
                  "factSheetName": "finance-kb",
                  "sources": [
                    {
                      "pathOrUrl": "/docs/q1.pdf",
                      "sourceType": "FILE",
                      "label": "Q1",
                      "maxDepth": 0,
                      "maxDocuments": 1,
                      "loaderName": "pdf"
                    },
                    {
                      "pathOrUrl": "https://example.test/q2.html",
                      "sourceType": "URL",
                      "label": "https://example.test/q2.html",
                      "maxDepth": 0,
                      "maxDocuments": 1
                    }
                  ],
                  "enabledSteps": ["GRAPH_EXTRACTION", "VECTOR_INDEXING"],
                  "strictSteps": true,
                  "defaultPipelineId": "reports",
                  "pipelines": [{"pipelineId": "reports", "pipelineType": "TABLE_AWARE"}],
                  "runtimeConfig": {"sourceLoadParallelism": 4}
                }
                """;

        server.expect(requestTo("http://crawl/api/unified-crawl/start"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertEquals(
                        mapper.readTree(expected),
                        mapper.readTree(((MockClientHttpRequest) request).getBodyAsString())))
                .andRespond(withSuccess("""
                        {
                          "jobId":"crawl-123",
                          "status":"QUEUED",
                          "factSheetId":42,
                          "sourceCount":2,
                          "scheduled":true,
                          "message":"Unified crawl queued"
                        }
                        """, MediaType.APPLICATION_JSON));

        ObjectNode params = mapper.createObjectNode();
        params.put("name", "quarterly reports");
        params.putObject("knowledgeBase").put("name", "finance-kb");
        params.putArray("steps").add("GRAPH_EXTRACTION").add("VECTOR_INDEXING");
        params.put("strictSteps", true);
        params.put("defaultPipelineId", "reports");
        params.putArray("pipelines").addObject()
                .put("pipelineId", "reports")
                .put("pipelineType", "TABLE_AWARE");
        params.putObject("runtimeConfig").put("sourceLoadParallelism", 4);
        params.putArray("documents").addObject()
                .put("path", "/docs/q1.pdf")
                .put("label", "Q1")
                .put("loaderName", "pdf");
        params.withArray("documents").addObject()
                .put("url", "https://example.test/q2.html");

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("crawl-123"));
        assertEquals("crawl-123", result.getMetadata().get("jobId"));
        assertEquals(2, result.getMetadata().get("sourceCount"));
        assertEquals(42L, result.getMetadata().get("factSheetId"));
        server.verify();
    }

    @Test
    void resolvesDiscoveredCodeProjectsIntoIncrementalCodeGraphSources() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        CrawlDocumentsTool tool = new CrawlDocumentsTool(
                new GroundingBackendClient("http://crawl", restTemplate), mapper);

        server.expect(requestTo("http://crawl/api/projects/current/code-projects"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {
                            "id":"kompile",
                            "codeProjectId":"kompile",
                            "name":"Kompile",
                            "rootPath":"/workspace/kompile",
                            "includePatterns":"**/*.java,**/*.md",
                            "excludePatterns":"**/generated/**",
                            "lifecycle":"ACTIVE",
                            "autoIndex":true
                          },
                          {
                            "id":"archived",
                            "rootPath":"/workspace/archived",
                            "lifecycle":"ARCHIVED"
                          }
                        ]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://crawl/api/unified-crawl/start"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    JsonNode source = body.path("sources").path(0);
                    assertEquals("/workspace/kompile", source.path("pathOrUrl").asText());
                    assertEquals("DIRECTORY", source.path("sourceType").asText());
                    assertEquals(64, source.path("maxDepth").asInt());
                    assertTrue(source.path("includePatterns").toString().contains("**/*.java"));
                    assertTrue(source.path("excludePatterns").toString().contains("**/target/**"));
                    assertTrue(source.path("excludePatterns").toString().contains("**/generated/**"));
                    assertTrue(source.path("properties").path("kompileCodeProject").asBoolean());
                    assertEquals("kompile", source.path("properties").path("codeProjectId").asText());
                    assertTrue(body.path("runtimeConfig").path("incrementalByContentHash").asBoolean());
                    assertFalse(body.path("runtimeConfig").path("forceFullRecrawl").asBoolean());
                    assertTrue(body.path("runtimeConfig").path("trainEmbeddingsAfterEnrichment").asBoolean());
                    assertEquals("ROTATE", body.path("runtimeConfig").path("embeddingAlgorithm").asText());
                    assertEquals(128, body.path("runtimeConfig").path("embeddingDim").asInt());
                    assertEquals(12, body.path("runtimeConfig").path("embeddingEpochs").asInt());
                    assertEquals(4, body.path("runtimeConfig").path("embeddingWarmStartEpochs").asInt());
                    assertTrue(body.path("pipelines").toString().contains("kompile-code-project"));
                    assertTrue(body.path("routeRules").toString().contains("\".java\""));
                })
                .andRespond(withSuccess("""
                        {
                          "jobId":"code-crawl-1",
                          "status":"QUEUED",
                          "factSheetId":9,
                          "sourceCount":1,
                          "scheduled":true
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://crawl/api/projects/current/code-projects/kompile/fact-sheet"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertEquals(9, body.path("factSheetId").asLong());
                })
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        ObjectNode params = mapper.createObjectNode();
        params.putArray("codeProjects").add("*");
        params.putObject("knowledgeBase").put("id", 9);
        params.putObject("embeddingTraining")
                .put("enabled", true)
                .put("algorithm", "rotate")
                .put("embeddingDim", 128)
                .put("epochs", 12)
                .put("warmStartEpochs", 4);

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("incremental CODE pipeline"));
        assertEquals(1, result.getMetadata().get("codeProjectCount"));
        assertEquals(1, result.getMetadata().get("sourceCount"));
        assertTrue(result.getMetadata().get("nextTools").toString().contains("graph_reasoning_query"));
        assertTrue(result.getMetadata().get("nextTools").toString().contains("graph_embeddings"));
        assertTrue(result.getMetadata().get("nextTools").toString().contains("code_graph"));
        server.verify();
    }

    @Test
    void knowledgeBaseRejectsConflictingIdAndName() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        CrawlDocumentsTool tool = new CrawlDocumentsTool(
                new GroundingBackendClient("http://crawl", restTemplate), mapper);
        ObjectNode params = mapper.createObjectNode();
        params.putArray("documents").addObject().put("path", "/docs/a.pdf");
        params.putObject("knowledgeBase").put("id", 7).put("name", "conflict");

        ToolResult result = tool.execute(params, context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("exactly one"));
    }
}
