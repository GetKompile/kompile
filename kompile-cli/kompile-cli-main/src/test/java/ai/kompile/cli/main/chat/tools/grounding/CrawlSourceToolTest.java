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

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for the {@code crawl_source} MCP tool — runs a single source through the
 * real unified-crawl pipeline. HTTP is intercepted with {@code MockRestServiceServer};
 * no server runs.
 */
class CrawlSourceToolTest {

    private ObjectMapper om;
    private ToolContext ctx;
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("coder").enabledTools(Set.of("*")).build();
        PermissionService perms = new PermissionService();
        perms.setUserOverride("crawl_source", PermissionService.PermissionLevel.ALLOW);
        perms.setUserOverride("external_directory", PermissionService.PermissionLevel.ALLOW);
        ToolRegistry registry = new ToolRegistry(om);
        ctx = new ToolContext("test-session", agent, perms, tempDir, registry);
    }

    // ── Metadata ─────────────────────────────────────────────────────────

    @Test
    void metadata() {
        CrawlSourceTool tool = new CrawlSourceTool((String) null, om);
        assertEquals("crawl_source", tool.id());
        assertEquals("crawl_source", tool.permissionKey());
        assertEquals(McpToolAnnotations.WRITE, tool.mcpAnnotations());
    }

    @Test
    void compactHintIsNonNullAndUnder200Chars() {
        CrawlSourceTool tool = new CrawlSourceTool((String) null, om);
        String hint = tool.compactHint();
        assertNotNull(hint, "compactHint() must not be null");
        assertTrue(hint.length() <= 200,
                "compactHint() must be ≤200 chars but was " + hint.length() + ": " + hint);
    }

    // ── Schema ────────────────────────────────────────────────────────────

    @Test
    void schemaHasAllExpectedProperties() {
        CrawlSourceTool tool = new CrawlSourceTool((String) null, om);
        JsonNode schema = tool.parameterSchema();
        JsonNode props = schema.path("properties");
        assertNotNull(props.path("path"),        "schema must have 'path'");
        assertNotNull(props.path("url"),         "schema must have 'url'");
        assertNotNull(props.path("text"),        "schema must have 'text'");
        assertNotNull(props.path("title"),       "schema must have 'title'");
        assertNotNull(props.path("dryRun"),      "schema must have 'dryRun'");
        assertNotNull(props.path("steps"),       "schema must have 'steps'");
        assertNotNull(props.path("factSheetId"), "schema must have 'factSheetId'");
        assertNotNull(props.path("model"),       "schema must have 'model'");
        assertNotNull(props.path("timeoutSeconds"), "schema must have 'timeoutSeconds'");
    }

    @Test
    void schemaDescribesProjectLocalStepsFromTheRuntimeCatalog() {
        CrawlSourceTool tool = new CrawlSourceTool((String) null, om);
        String description = tool.parameterSchema().path("properties")
                .path("steps").path("description").asText();

        assertTrue(description.contains("LEXICAL_INDEX"), description);
        assertTrue(description.contains("LEARNING"), description);
        assertTrue(description.contains("crawl_discover"), description);
        assertTrue(tool.description().contains("MARKDOWN_EXTRACTION"), tool.description());
    }

    // ── Input validation ──────────────────────────────────────────────────

    @Test
    void noSource_returnsError_mentioningOneOf() throws Exception {
        CrawlSourceTool tool = new CrawlSourceTool((String) null, om);
        ToolResult result = tool.execute(om.createObjectNode(), ctx);
        assertTrue(result.isError());
        assertTrue(result.getOutput().toLowerCase().contains("one of"),
                "Error should mention 'one of' but was: " + result.getOutput());
    }

    @Test
    void multipleSourcesGiven_returnsError() throws Exception {
        CrawlSourceTool tool = new CrawlSourceTool((String) null, om);
        ObjectNode params = om.createObjectNode();
        params.put("path", "/tmp/file.txt");
        params.put("text", "some inline text");
        ToolResult result = tool.execute(params, ctx);
        assertTrue(result.isError());
        assertTrue(result.getOutput().toLowerCase().contains("only one of"),
                "Error should mention 'only one of' but was: " + result.getOutput());
    }

    @Test
    void nullBaseUrl_usesLocalBackendAndReportsMissingSource() throws Exception {
        CrawlSourceTool tool = new CrawlSourceTool((String) null, om);
        ObjectNode params = om.createObjectNode();
        params.put("async", false);
        params.put("path", "missing-file.txt");
        ToolResult result = tool.execute(params, ctx);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Local crawl source does not exist"),
                "Error should identify the local source problem but was: " + result.getOutput());
    }

    @Test
    void nullBaseUrl_fetchesUrlDirectlyInProcess() throws Exception {
        byte[] body = "Offline stdio URL crawl content".getBytes(StandardCharsets.UTF_8);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notes.txt", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            CrawlSourceTool tool = new CrawlSourceTool((String) null, om);
            ObjectNode params = om.createObjectNode();
            params.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/notes.txt");
            params.put("title", "offline notes");
            params.put("dryRun", true);

            ToolResult result = tool.execute(params, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertEquals(1, requests.get());
            assertEquals("project-local", result.getMetadata().get("backend"));
            assertFalse(result.getOutput().contains("distributed crawl manager"), result.getOutput());
        } finally {
            server.stop(0);
        }
    }

    // ── Dry-run success ───────────────────────────────────────────────────

    @Test
    void dryRunSuccess_returnsExpectedOutput() throws Exception {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
        GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
        CrawlSourceTool tool = new CrawlSourceTool(client, om);

        mockServer.expect(requestTo("http://localhost/api/unified-crawl/single-source"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(dryRunResponseJson(), MediaType.APPLICATION_JSON));

        ObjectNode params = om.createObjectNode();
        params.put("path", "/tmp/report.pdf");
        params.put("dryRun", true);

        ToolResult result = tool.execute(params, ctx);

        assertFalse(result.isError(), "Expected success but got error: " + result.getOutput());
        assertTrue(result.getOutput().contains("Dry-run"),
                "Output must mention dry-run: " + result.getOutput());
        assertEquals(3, result.getMetadata().get("entityCount"),
                "Metadata entityCount must be 3");
        mockServer.verify();
    }

    // ── Persist success with steps ────────────────────────────────────────

    @Test
    void persistSuccess_withSteps_returnsCompleteOutput() throws Exception {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
        GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
        CrawlSourceTool tool = new CrawlSourceTool(client, om);

        mockServer.expect(requestTo("http://localhost/api/unified-crawl/single-source"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(persistResponseJson(), MediaType.APPLICATION_JSON));

        ObjectNode params = om.createObjectNode();
        params.put("url", "https://example.com/doc.html");
        params.put("dryRun", false);
        params.putArray("steps").add("GRAPH_EXTRACTION");

        ToolResult result = tool.execute(params, ctx);

        assertFalse(result.isError(), "Expected success but got error: " + result.getOutput());
        assertTrue(result.getOutput().contains("Crawl complete"),
                "Output must confirm completion: " + result.getOutput());
        // Step summary must appear
        assertTrue(result.getOutput().contains("GRAPH_EXTRACTION"),
                "Output must include step name: " + result.getOutput());
        mockServer.verify();
    }

    @Test
    void managedSingleSourceForwardsBothProviderAndModel() throws Exception {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        CrawlSourceTool tool = new CrawlSourceTool(new GroundingBackendClient("http://localhost", rt), om);
        server.expect(requestTo("http://localhost/api/unified-crawl/single-source"))
                .andExpect(request -> {
                    JsonNode body = om.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                            .getBodyAsString());
                    assertEquals("codex-cli", body.path("llmProvider").asText());
                    assertEquals("request-model", body.path("modelName").asText());
                })
                .andRespond(withSuccess(persistResponseJson(), MediaType.APPLICATION_JSON));
        ToolResult result = tool.execute(om.createObjectNode().put("text", "Acme acquired Initech.")
                .put("provider", "codex-cli").put("model", "request-model"), ctx);
        assertFalse(result.isError(), result.getOutput());
        assertTrue(tool.parameterSchema().path("properties").path("provider").path("description").asText()
                .contains("chat:<provider>"));
        server.verify();
    }

    // ── Timed-out (not yet completed, jobId returned) ─────────────────────

    @Test
    void timedOut_notCompleted_outputContainsPollLink() throws Exception {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
        GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
        CrawlSourceTool tool = new CrawlSourceTool(client, om);

        mockServer.expect(requestTo("http://localhost/api/unified-crawl/single-source"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(timedOutResponseJson("job-abc-123"), MediaType.APPLICATION_JSON));

        ObjectNode params = om.createObjectNode();
        params.put("text", "Some document text to crawl");
        params.put("timeoutSeconds", 10);

        ToolResult result = tool.execute(params, ctx);

        assertFalse(result.isError(), "Expected success but got error: " + result.getOutput());
        assertTrue(result.getOutput().contains("job-abc-123"),
                "Output must contain jobId: " + result.getOutput());
        assertTrue(result.getOutput().contains("poll"),
                "Output must contain 'poll': " + result.getOutput());
        mockServer.verify();
    }

    // ── HTTP 503 ──────────────────────────────────────────────────────────

    @Test
    void http503_returnsErrorMentioningBusy() throws Exception {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
        GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
        CrawlSourceTool tool = new CrawlSourceTool(client, om);

        mockServer.expect(requestTo("http://localhost/api/unified-crawl/single-source"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Queue full\"}"));

        ObjectNode params = om.createObjectNode();
        params.put("path", "/tmp/test.txt");

        ToolResult result = tool.execute(params, ctx);

        assertTrue(result.isError(), "Expected error for HTTP 503");
        String out = result.getOutput();
        assertTrue(out.contains("busy") || out.contains("unavailable") || out.contains("503"),
                "Error must mention busy/unavailable/503: " + out);
        mockServer.verify();
    }

    // ── HTTP 400 with error detail ────────────────────────────────────────

    @Test
    void http400_withErrorBody_returnsExtractedMessage() throws Exception {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
        GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
        CrawlSourceTool tool = new CrawlSourceTool(client, om);

        mockServer.expect(requestTo("http://localhost/api/unified-crawl/single-source"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Unknown step X. Valid: GRAPH_EXTRACTION, VECTOR_INDEXING\"}"));

        ObjectNode params = om.createObjectNode();
        params.put("path", "/tmp/test.txt");

        ToolResult result = tool.execute(params, ctx);

        assertTrue(result.isError(), "Expected error for HTTP 400");
        assertTrue(result.getOutput().contains("Valid"),
                "Error must contain 'Valid' from server message: " + result.getOutput());
        mockServer.verify();
    }

    // ── GroundingBackendClient injected-timeout test ───────────────────────

    @Test
    void injectedClient_postWithTimeout_routesThroughMockServer() throws Exception {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
        GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);

        mockServer.expect(requestTo("http://localhost/api/unified-crawl/single-source"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"dryRun\":true,\"completed\":true,\"persisted\":false,"
                        + "\"status\":\"DRY_RUN\",\"entityCount\":1,\"relationCount\":0,"
                        + "\"chunksCreated\":1,\"documentsLoaded\":1,\"errorCount\":0}",
                        MediaType.APPLICATION_JSON));

        // Call the Duration-based overload directly — verifies the injected flag routes through mock
        GroundingBackendClient.GroundingResponse resp =
                client.post("/api/unified-crawl/single-source",
                        "{\"pathOrUrl\":\"/tmp/x.txt\",\"dryRun\":true,\"waitTimeoutSeconds\":60}",
                        Duration.ofSeconds(60));

        assertEquals(200, resp.statusCode(), "Injected-client call must return 200 from mock");
        assertTrue(resp.body().contains("DRY_RUN"), "Body must contain DRY_RUN: " + resp.body());
        mockServer.verify();
    }

    @Test
    void productionClientAvoidsReflectiveJacksonModuleDiscovery() {
        GroundingBackendClient client = new GroundingBackendClient("http://localhost");

        var converters = client.getRestTemplate().getMessageConverters();
        assertTrue(converters.stream().anyMatch(
                converter -> converter instanceof org.springframework.http.converter.StringHttpMessageConverter));
        assertTrue(converters.stream().anyMatch(
                converter -> converter instanceof org.springframework.http.converter.ByteArrayHttpMessageConverter));
        assertTrue(converters.stream().anyMatch(
                converter -> converter instanceof org.springframework.http.converter.FormHttpMessageConverter));
        assertFalse(converters.stream().anyMatch(
                converter -> converter.getClass().getName().contains("Jackson")),
                "native MCP startup must not trigger optional Jackson/Kotlin module discovery");
    }

    // ── Response fixture helpers ──────────────────────────────────────────

    private static String dryRunResponseJson() {
        return "{"
                + "\"dryRun\":true,"
                + "\"completed\":true,"
                + "\"persisted\":false,"
                + "\"status\":\"DRY_RUN\","
                + "\"jobId\":null,"
                + "\"factSheetId\":null,"
                + "\"entityCount\":3,"
                + "\"relationCount\":2,"
                + "\"chunksCreated\":2,"
                + "\"documentsLoaded\":1,"
                + "\"errorCount\":0,"
                + "\"errors\":[],"
                + "\"warnings\":[],"
                + "\"stepsPlanned\":{\"GRAPH_EXTRACTION\":\"RUN\"},"
                + "\"steps\":[{\"stepId\":\"GRAPH_EXTRACTION\",\"displayName\":\"Graph Extraction\","
                + "\"status\":\"COMPLETED\",\"elapsedMs\":1234}],"
                + "\"sampleEntities\":[{\"id\":\"e1\",\"name\":\"Alice\",\"type\":\"PERSON\",\"confidence\":0.9}],"
                + "\"sampleRelations\":[],"
                + "\"elapsedMs\":2345"
                + "}";
    }

    private static String persistResponseJson() {
        return "{"
                + "\"dryRun\":false,"
                + "\"completed\":true,"
                + "\"persisted\":true,"
                + "\"status\":\"COMPLETED\","
                + "\"jobId\":null,"
                + "\"factSheetId\":7,"
                + "\"entityCount\":5,"
                + "\"relationCount\":3,"
                + "\"chunksCreated\":3,"
                + "\"documentsLoaded\":1,"
                + "\"errorCount\":0,"
                + "\"errors\":[],"
                + "\"warnings\":[],"
                + "\"stepsPlanned\":{\"GRAPH_EXTRACTION\":\"RUN\",\"VECTOR_INDEXING\":\"SKIP\"},"
                + "\"steps\":[{\"stepId\":\"GRAPH_EXTRACTION\",\"displayName\":\"Graph Extraction\","
                + "\"status\":\"COMPLETED\",\"elapsedMs\":5678}],"
                + "\"sampleEntities\":[],"
                + "\"sampleRelations\":[],"
                + "\"elapsedMs\":6000"
                + "}";
    }

    private static String timedOutResponseJson(String jobId) {
        return "{"
                + "\"dryRun\":false,"
                + "\"completed\":false,"
                + "\"persisted\":false,"
                + "\"status\":\"RUNNING\","
                + "\"jobId\":\"" + jobId + "\","
                + "\"factSheetId\":null,"
                + "\"entityCount\":0,"
                + "\"relationCount\":0,"
                + "\"chunksCreated\":0,"
                + "\"documentsLoaded\":0,"
                + "\"errorCount\":0,"
                + "\"errors\":[],"
                + "\"warnings\":[],"
                + "\"elapsedMs\":10000"
                + "}";
    }
}
