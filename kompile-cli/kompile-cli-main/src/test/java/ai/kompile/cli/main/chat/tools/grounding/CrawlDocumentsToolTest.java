/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.config.ChatConfig;
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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CrawlDocumentsToolTest {
    @TempDir
    Path tempDir;

    private ObjectMapper mapper;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("crawler").enabledTools(Set.of("*")).build();
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("crawl_documents", PermissionService.PermissionLevel.ALLOW);
        context = new ToolContext("crawl-test", agent, permissions, tempDir,
                new ToolRegistry(mapper));
    }

    @Test
    void metadataAndSchemaExposeAgentFacingContract() {
        CrawlDocumentsTool tool = new CrawlDocumentsTool((String) null, mapper);

        assertEquals("crawl_documents", tool.id());
        assertEquals(McpToolAnnotations.WRITE, tool.mcpAnnotations());
        assertTrue(tool.compactHint().length() <= 200);

        JsonNode schema = tool.parameterSchema();
        assertTrue(schema.path("anyOf").isMissingNode());
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
        assertTrue(schema.path("properties").path("dryRun").path("description").asText()
                .contains("effectiveRequest"));
        assertTrue(schema.path("properties").path("pipelines").path("items").path("properties")
                .path("registeredPipelineId").path("description").asText()
                .contains("Inherited options are effective defaults"));
        assertTrue(schema.path("properties").path("pipelines").path("items").path("properties")
                                .path("options").path("description").asText()
                .contains("maxNewTokens"));
        JsonNode processorType = schema.path("properties").path("pipelines").path("items")
                .path("properties").path("processor").path("properties").path("type").path("enum");
        assertTrue(processorType.toString().contains("CHAT_MODEL"), processorType.toString());
        assertEquals("string", schema.path("properties").path("pipelines").path("items")
                .path("properties").path("processor").path("properties").path("pageRange")
                .path("type").asText());
        assertTrue(schema.path("pipelineTypeGuide").path("CHAT_MODEL").asText()
                .contains("configured direct chat provider"));
        assertTrue(schema.path("properties").path("processingRoute").path("description").asText()
                .contains("semantic graph-extraction"));
    }

    @Test
    void chatPdfDryRunReportsTheActualSelectedPageRange() throws Exception {
        new ChatConfig("custom", null, "dry-run-model", "http://127.0.0.1:1/v1")
                .saveProject(tempDir);
        Path pdfPath = tempDir.resolve("dry-run-pages.pdf");
        try (PDDocument pdf = new PDDocument()) {
            for (int i = 0; i < 10; i++) pdf.addPage(new PDPage());
            pdf.save(pdfPath.toFile());
        }

        ObjectNode request = mapper.createObjectNode().put("dryRun", true).put("async", false);
        request.putObject("knowledgeBase").put("name", "chat-page-preview");
        request.putArray("documents").addObject()
                .put("path", pdfPath.toString()).put("pipelineId", "chat-pdf");
        request.putArray("pipelines").addObject()
                .put("pipelineId", "chat-pdf").put("pipelineType", "VLM")
                .put("loaderName", "auto").put("chunkerName", "no-op")
                .putObject("processor").put("type", "CHAT_MODEL")
                .put("pageRange", "7-9").put("maxPages", 10);

        ToolResult result = new CrawlDocumentsTool((String) null, mapper).execute(request, context);

        assertFalse(result.isError(), result.getOutput());
        @SuppressWarnings("unchecked")
        Map<String, Object> preview = (Map<String, Object>) result.getMetadata().get("preview");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resolved = (List<Map<String, Object>>) preview.get("resolvedDocuments");
        @SuppressWarnings("unchecked")
        Map<String, Object> modelResolution = (Map<String, Object>) resolved.get(0).get("modelResolution");
        assertEquals("7-9", modelResolution.get("selectedPageRange"));
        assertEquals(10, modelResolution.get("totalPages"));
        assertEquals(3, modelResolution.get("selectedPageCount"));
    }

    @Test
    void warnsWhenInheritedVlmConfigurationIsImplicit() {
        ObjectNode request = mapper.createObjectNode();
        request.putArray("pipelines").addObject()
                .put("pipelineId", "vlm-ocr-pdf")
                .put("pipelineType", "VLM")
                .put("registeredPipelineId", "vlm-ocr-pdf");

        List<String> warnings = CrawlDocumentsTool.pipelineConfigurationWarnings(request);

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("bind modelId/modelBindings explicitly"));
        assertTrue(warnings.get(0).contains("set options.outputFormat explicitly"));

        ObjectNode explicit = (ObjectNode) request.withArray("pipelines").get(0);
        explicit.put("modelId", "smoldocling-256m");
        explicit.putObject("options").put("outputFormat", "MARKDOWN");
        assertTrue(CrawlDocumentsTool.pipelineConfigurationWarnings(request).isEmpty());
    }

    @Test
    void missingDocumentsAndCodeProjectsBootstrapsTheFolder() throws Exception {
        CrawlDocumentsTool tool = new CrawlDocumentsTool((String) null, mapper);

        ToolResult result = tool.execute(mapper.createObjectNode().put("async", false), context);

        assertFalse(result.isError(), result.getOutput());
        assertEquals("project-local", result.getMetadata().get("backend"));
        assertFalse(result.getMetadata().containsKey("factSheetId"));
    }

    @Test
    void obsidianVaultStaysProjectLocalWhenManagerIsConfigured() throws Exception {
        Path vault = Files.createDirectories(tempDir.resolve("vault"));
        Files.writeString(vault.resolve("note.md"), "obsidian-local-robin-marker", StandardCharsets.UTF_8);
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        CrawlDocumentsTool tool = new CrawlDocumentsTool(
                new GroundingBackendClient("http://crawl", restTemplate), mapper);
        ObjectNode request = mapper.createObjectNode().put("async", false);
        request.putObject("runtimeConfig").put("runReasoningLearning", false);
        request.putObject("knowledgeBase").put("name", "obsidian-local");
        request.putArray("documents").addObject()
                .put("path", vault.toString()).put("sourceType", "OBSIDIAN");

        ToolResult result = tool.execute(request, context);

        assertFalse(result.isError(), result.getOutput());
        assertEquals("project-local", result.getMetadata().get("backend"));
        assertTrue(Files.readString(tempDir.resolve("data/crawls/obsidian-local/chunks.jsonl"))
                .contains("obsidian-local-robin-marker"));
        server.verify();
    }

    @Test
    void chatModelPipelineStaysProjectLocalWhenManagerIsConfigured() throws Exception {
        Path document = tempDir.resolve("remote.txt");
        Files.writeString(document, "remote model input", StandardCharsets.UTF_8);
        new ChatConfig("kompile-local", null, "local-only", "http://127.0.0.1:1")
                .saveProject(tempDir);
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        CrawlDocumentsTool tool = new CrawlDocumentsTool(
                new GroundingBackendClient("http://crawl", restTemplate), mapper);
        ObjectNode request = mapper.createObjectNode().put("async", false);
        request.putObject("knowledgeBase").put("name", "chat-model-local-routing");
        request.putArray("documents").addObject()
                .put("path", document.toString())
                .put("pipelineId", "chat-model-document");
        request.put("defaultPipelineId", "chat-model-document");

        ToolResult result = tool.execute(request, context);

        assertTrue(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("Use UNIFIED_PIPELINE"), result.getOutput());
        server.verify();
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
        JsonNode requestedConfiguration = (JsonNode) result.getMetadata().get("requestedConfiguration");
        assertEquals("reports", requestedConfiguration.path("defaultPipelineId").asText());
        assertTrue(result.getMetadata().containsKey("configurationWarnings"));
        assertTrue(result.getMetadata().get("configurationWarnings") instanceof List);
        assertTrue(result.getMetadata().get("crawlResult").toString()
                .contains("kompile-crawl-result/v1"));
        assertTrue(result.getMetadata().get("nextActions").toString()
                .contains("graph_reasoning_query"));
        server.verify();
    }

    @Test
    void managedExternalSourceKeepsMultiDocumentDefaultAndRedactsResultMetadata() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        CrawlDocumentsTool tool = new CrawlDocumentsTool(
                new GroundingBackendClient("http://crawl", restTemplate), mapper);
        server.expect(requestTo("http://crawl/api/unified-crawl/start"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    JsonNode source = body.path("sources").get(0);
                    assertEquals("NOTION", source.path("sourceType").asText());
                    assertEquals(0, source.path("maxDocuments").asInt());
                    assertEquals("runtime-secret", source.path("properties").path("apiToken").asText());
                })
                .andRespond(withSuccess("""
                        {"jobId":"notion-1","status":"QUEUED","factSheetId":7,"sourceCount":1}
                        """, MediaType.APPLICATION_JSON));
        ObjectNode request = mapper.createObjectNode();
        request.putObject("knowledgeBase").put("id", 7);
        request.putArray("documents").addObject()
                .put("path", "0123456789abcdef0123456789abcdef")
                .put("sourceType", "NOTION")
                .putObject("properties").put("apiToken", "runtime-secret");

        ToolResult result = tool.execute(request, context);

        assertFalse(result.isError(), result.getOutput());
        JsonNode metadata = (JsonNode) result.getMetadata().get("requestedConfiguration");
        assertEquals("***REDACTED***",
                metadata.path("sources").get(0).path("properties").path("apiToken").asText());
        assertFalse(result.getMetadata().toString().contains("runtime-secret"));
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
                            "factSheetId":7,
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
                    assertTrue(source.path("properties").path("projectManaged").asBoolean());
                    assertEquals("CODE", source.path("properties").path("pipelineType").asText());
                    assertEquals("code_graph", source.path("properties").path("structuralIndex").asText());
                    assertEquals("Kompile", source.path("properties").path("codeProjectName").asText());
                    assertFalse(source.path("properties").has("factSheetId"),
                            "explicit crawl target must replace the stale project binding");
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
        assertEquals("managed-by-crawl", result.getMetadata().get("codeProjectFactSheetBindings"));
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
