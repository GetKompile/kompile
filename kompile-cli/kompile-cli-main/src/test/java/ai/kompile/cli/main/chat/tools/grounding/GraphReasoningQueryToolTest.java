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
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.ToolSchemaOptimizer;
import ai.kompile.cli.main.project.LocalSubprocessWatchdog;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link GraphReasoningQueryTool} — the CLI MCP tool wrapping
 * {@code POST /api/graph/reasoning/query}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Tool metadata: id, permissionKey, READ_ONLY annotation, compactHint present and intact
 *       in the compact MCP listing</li>
 *   <li>Parameter schema: operation and question present; no required params (all optional)</li>
 *   <li>No configured backend → folder-scoped local graph bootstrap</li>
 *   <li>CAPABILITIES intent routed correctly, HTTP call made to the right endpoint</li>
 *   <li>Server 400 → tool error with CAPABILITIES hint</li>
 *   <li>Server 200 CAPABILITIES → formatted output with "Available operations" section</li>
 *   <li>Server 200 VERIFY SUPPORTED → formatted output with SUPPORTED headline and relation</li>
 *   <li>Formatter: plain-text rendering, no engine jargon</li>
 *   <li>Formatter: engine results keep their reasoning trace, data fields, opinions and
 *       input resolution</li>
 * </ul>
 */
@DisplayName("GraphReasoningQueryTool — CLI MCP tool")
@TemporaryUserHome
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class GraphReasoningQueryToolTest {

    private static final String BASE_URL = "http://localhost:8080";

    private ObjectMapper om;
    private ToolContext ctx;
    private String previousAdmissionMode;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        previousAdmissionMode = System.getProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY);
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, "off");
        om = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("coder").enabledTools(Set.of("*")).build();
        PermissionService perms = new PermissionService();
        perms.setUserOverride("graph_reasoning_query", PermissionService.PermissionLevel.ALLOW);
        ToolRegistry registry = new ToolRegistry(om);
        ctx = new ToolContext("test-session", agent, perms, tempDir, registry);
    }

    @AfterEach
    void tearDown() {
        if (previousAdmissionMode == null) {
            System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY);
        } else {
            System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, previousAdmissionMode);
        }
    }

    @Test
    @DisplayName("native image registers the complete local graph query response DTO graph")
    void nativeImageRegistersLocalGraphQueryResponseTypes() throws Exception {
        String resource = "/META-INF/native-image/ai.kompile/kompile-cli/reflect-config.json";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input, "missing native-image reflection configuration");
            JsonNode config = om.readTree(input);
            Set<String> registered = new HashSet<>();
            config.forEach(entry -> registered.add(entry.path("name").asText()));

            Set<String> required = Set.of(
                    "ai.kompile.graph.reasoning.query.GraphQueryEngine$Result",
                    "ai.kompile.graph.reasoning.query.GraphQueryEngine$EntityView",
                    "ai.kompile.graph.reasoning.query.GraphQueryEngine$RelationView",
                    "ai.kompile.graph.reasoning.query.GraphQueryEngine$ResolutionView",
                    "ai.kompile.graph.reasoning.query.GraphQueryEngine$PathStep",
                    "ai.kompile.graph.reasoning.query.GraphQueryEngine$Capability",
                    "ai.kompile.graph.reasoning.explain.ReasoningTrace",
                    "ai.kompile.graph.reasoning.explain.ReasoningTrace$Step",
                    "ai.kompile.graph.reasoning.confidence.Opinion",
                    "ai.kompile.graph.reasoning.quantitative.ModelRetrieval",
                    "ai.kompile.graph.reasoning.quantitative.ModelRetrieval$ScoreBreakdown",
                    "ai.kompile.graph.reasoning.quantitative.ModelRetrieval$ModelMatch",
                    "ai.kompile.graph.reasoning.quantitative.ModelRetrieval$Plan",
                    "ai.kompile.graph.reasoning.quantitative.ModelRetrieval$ResolvedIntervention",
                    "ai.kompile.graph.reasoning.quantitative.ModelRetrieval$Gap",
                    "ai.kompile.graph.reasoning.quantitative.QuantitativeRule",
                    "ai.kompile.graph.reasoning.quantitative.QuantitativeRule$Input",
                    "ai.kompile.graph.reasoning.quantitative.QuantitativeQuery",
                    "ai.kompile.graph.reasoning.quantitative.QuantitativeQuery$MeasureSelector",
                    "ai.kompile.graph.reasoning.quantitative.QuantitativeQuery$Intervention",
                    "ai.kompile.graph.reasoning.quantitative.QuantitativeQuery$Goal",
                    "ai.kompile.graph.reasoning.quantitative.ScenarioResult",
                    "ai.kompile.graph.reasoning.quantitative.QuantitativeScenarioEngine$GoalSeekResult",
                    "ai.kompile.graph.reasoning.quantitative.QuantitativeScenarioEngine$GoalSeekResult$Alternative");
            Set<String> missing = new HashSet<>(required);
            missing.removeAll(registered);
            assertTrue(missing.isEmpty(), () -> "Missing native reflection metadata: " + missing);
        }
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tool metadata")
    class Metadata {

        @Test
        @DisplayName("id is 'graph_reasoning_query' — matches backend tool id")
        void idMatchesBackendTool() {
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool((String) null, om);
            assertEquals("graph_reasoning_query", tool.id());
        }

        @Test
        @DisplayName("permissionKey matches id")
        void permissionKeyMatchesId() {
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool((String) null, om);
            assertEquals("graph_reasoning_query", tool.permissionKey());
        }

        @Test
        @DisplayName("annotation is READ_ONLY")
        void annotationsReadOnly() {
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool((String) null, om);
            assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
        }

        @Test
        @DisplayName("compactHint is non-null and contains key guidance words")
        void compactHintPresent() {
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool((String) null, om);
            String hint = tool.compactHint();
            assertNotNull(hint, "compactHint must be non-null");
            assertFalse(hint.isBlank(), "compactHint must not be blank");
            assertTrue(hint.contains("capabilities") || hint.contains("CAPABILITIES"),
                    "compactHint must mention capabilities intent");
            assertTrue(hint.contains("current folder"),
                    "compactHint must advertise the folder-scoped local default");
            assertTrue(hint.contains("optional remote/legacy"),
                    "compactHint must describe factSheetId only as an optional compatibility override");
            for (String operation : List.of("MODELS", "CALCULATE", "SCENARIO", "SOLVE_TARGET")) {
                assertTrue(hint.contains(operation), operation);
            }
        }

        @Test
        @DisplayName("compact MCP listing keeps the whole hint, every operation, and the quantitative object")
        void compactMcpListingKeepsTheContract() {
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool((String) null, om);
            ArrayNode definitions = om.createArrayNode();
            definitions.addObject()
                    .put("name", tool.id())
                    .put("description", tool.description())
                    .set("inputSchema", tool.parameterSchema());
            JsonNode listed = ToolSchemaOptimizer.optimize(definitions,
                    ToolSchemaOptimizer.OptimizationLevel.COMPACT, Map.of(tool.id(), tool.compactHint())).get(0);

            assertEquals(tool.compactHint(), listed.path("description").asText(),
                    "the MCP listing cuts hints longer than 200 characters");
            JsonNode listedProperties = listed.path("inputSchema").path("properties");
            assertEquals(om.convertValue(tool.parameterSchema().path("properties").path("operation").path("enum"),
                            List.class),
                    om.convertValue(listedProperties.path("operation").path("enum"), List.class));
            assertFalse(listedProperties.path("quantitative").path("properties").path("target").isMissingNode(),
                    listedProperties.path("quantitative").toString());
        }

        @Test
        @DisplayName("description is non-blank and mentions CAPABILITIES")
        void descriptionMentionsCapabilities() {
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool((String) null, om);
            String desc = tool.description();
            assertFalse(desc.isBlank());
            assertTrue(desc.toLowerCase().contains("knowledge graph"),
                    "description must mention knowledge graph");
            assertTrue(desc.contains("project-local"),
                    "description must advertise the default in-process backend");
            assertFalse(desc.toLowerCase().contains("requires kompile"),
                    "description must not claim that a centralized service is required");
        }
    }

    // ── Parameter schema ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Parameter schema")
    class Schema {

        @Test
        @DisplayName("schema has 'operation' property")
        void schemaHasOperation() {
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool((String) null, om);
            JsonNode props = tool.parameterSchema().path("properties");
            assertFalse(props.path("operation").isMissingNode());
            JsonNode values = props.path("operation").path("enum");
            assertTrue(values.isArray());
            assertEquals(List.of(
                            "CAPABILITIES", "OVERVIEW", "SCHEMA", "SEARCH", "RELATIONS",
                            "DESCRIBE", "NEIGHBORS", "PATH", "TIMELINE", "FACTS", "SIMILAR",
                            "VERIFY", "WHY", "WHY_NOT", "RANK", "ASSETS", "ARTIFACT",
                            "MODELS", "CALCULATE", "SCENARIO", "SOLVE_TARGET"),
                    om.convertValue(values, List.class));
        }

        @Test
        @DisplayName("schema publishes the quantitative object the formula operations read")
        void schemaHasQuantitativeObject() {
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool((String) null, om);
            JsonNode quantitative = tool.parameterSchema().path("properties").path("quantitative");
            assertEquals("object", quantitative.path("type").asText());
            for (String member : List.of("target", "interventions", "goal", "dimensions", "asOf", "topK")) {
                assertFalse(quantitative.path("properties").path(member).isMissingNode(), member);
            }
            assertEquals(List.of("SET", "ADD", "SCALE"), om.convertValue(quantitative.path("properties")
                    .path("interventions").path("items").path("properties").path("operation").path("enum"),
                    List.class));
            assertTrue(tool.description().contains("MODELS, CALCULATE, SCENARIO, SOLVE_TARGET"),
                    tool.description());
            assertTrue(tool.compactHint().contains("quantitative={\"target\":"), tool.compactHint());
        }

        @Test
        @DisplayName("schema has 'question' convenience property")
        void schemaHasQuestion() {
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool((String) null, om);
            JsonNode props = tool.parameterSchema().path("properties");
            assertFalse(props.path("question").isMissingNode(),
                    "question convenience field must be in schema");
        }

        @Test
        @DisplayName("schema has 'factSheetId' property")
        void schemaHasFactSheetId() {
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool((String) null, om);
            JsonNode props = tool.parameterSchema().path("properties");
            assertFalse(props.path("factSheetId").isMissingNode());
        }

        @Test
        @DisplayName("no required parameters — all are optional")
        void noRequiredParams() {
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool((String) null, om);
            JsonNode required = tool.parameterSchema().path("required");
            // required must be either missing or an empty array
            assertTrue(required.isMissingNode() || (!required.isArray() || required.size() == 0),
                    "schema must have no required params; found: " + required);
        }

        @Test
        @DisplayName("schema has all structural params: entityId, targetId, direction, structural")
        void schemaHasStructuralParams() {
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool((String) null, om);
            JsonNode props = tool.parameterSchema().path("properties");
            assertFalse(props.path("entityId").isMissingNode());
            assertFalse(props.path("targetId").isMissingNode());
            assertFalse(props.path("direction").isMissingNode());
            assertFalse(props.path("structural").isMissingNode());
        }
    }

    // ── Folder-scoped local backend ────────────────────────────────────────────

    @Nested
    @DisplayName("Folder-scoped local backend")
    class BackendUnavailable {

        @Test
        @DisplayName("null baseUrl bootstraps the project-local graph")
        void nullBaseUrl_usesProjectLocalBackend() throws Exception {
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool((String) null, om);
            ObjectNode params = om.createObjectNode();
            params.put("operation", "CAPABILITIES");

            ToolResult result = tool.execute(params, ctx);
            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("Available operations"), result.getOutput());
            assertTrue(java.nio.file.Files.isRegularFile(
                    tempDir.resolve("data/crawls")
                            .resolve(tempDir.getFileName().toString().toLowerCase() + "-knowledge")
                            .resolve(LocalProjectGraphBackend.GRAPH_FILE)));
        }

        @Test
        @DisplayName("empty params use the folder graph and default query intent")
        void emptyParams_unavailableBackend_returnsError() throws Exception {
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool((String) null, om);
            ToolResult result = tool.execute(om.createObjectNode(), ctx);
            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("Available operations"), result.getOutput());
        }
    }

    // ── HTTP routing ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("HTTP routing")
    class HttpRouting {

        @Test
        @DisplayName("CAPABILITIES intent posts to /api/graph/reasoning/query and formats response")
        void capabilitiesPostsToCorrectEndpoint() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient(BASE_URL, rt);
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool(client, om);

            // Realistic capabilities response from the server
            String capabilitiesJson = """
                    {
                      "status": "OK",
                      "intent": "CAPABILITIES",
                      "summary": "17 operations available",
                      "entities": [],
                      "relations": [],
                      "path": [],
                      "capabilities": [
                        {"intent":"CAPABILITIES","purpose":"list all supported operations","requiredFields":[],"defaults":{}},
                        {"intent":"OVERVIEW","purpose":"counts and density","requiredFields":[],"defaults":{}},
                        {"intent":"SCHEMA","purpose":"predicate vocabulary","requiredFields":[],"defaults":{}},
                        {"intent":"SEARCH","purpose":"text search over nodes","requiredFields":["queryText"],"defaults":{}},
                        {"intent":"DESCRIBE","purpose":"describe an entity","requiredFields":["entityId"],"defaults":{}},
                        {"intent":"NEIGHBORS","purpose":"immediate neighbors","requiredFields":["entityId"],"defaults":{}},
                        {"intent":"PATH","purpose":"shortest path","requiredFields":["entityId","targetId"],"defaults":{}},
                        {"intent":"TIMELINE","purpose":"time-ordered events","requiredFields":["entityId"],"defaults":{}},
                        {"intent":"FACTS","purpose":"grounded facts","requiredFields":[],"defaults":{}},
                        {"intent":"SIMILAR","purpose":"similar entities","requiredFields":["entityId"],"defaults":{}},
                        {"intent":"VERIFY","purpose":"check a typed relation","requiredFields":["entityId","targetId","relationTypes"],"defaults":{}},
                        {"intent":"WHY","purpose":"explain why a relation holds","requiredFields":["entityId","targetId","relationTypes"],"defaults":{}},
                        {"intent":"WHY_NOT","purpose":"explain why a relation does not hold","requiredFields":["entityId","targetId","relationTypes"],"defaults":{}},
                        {"intent":"RANK","purpose":"ranked entities","requiredFields":[],"defaults":{}},
                        {"intent":"ASSETS","purpose":"vector layers and weight maps","requiredFields":[],"defaults":{}},
                        {"intent":"ARTIFACT","purpose":"named model artifact","requiredFields":["queryText"],"defaults":{}},
                        {"intent":"RELATIONS","purpose":"relations for an entity","requiredFields":["entityId"],"defaults":{}}
                      ],
                      "guidance": [],
                      "data": {},
                      "resolutions": []
                    }
                    """;

            mockServer.expect(requestTo(BASE_URL + "/api/graph/reasoning/query"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(capabilitiesJson, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("operation", "CAPABILITIES");
            ToolResult result = tool.execute(params, ctx);

            assertFalse(result.isError(), "CAPABILITIES should succeed: " + result.getOutput());
            assertTrue(result.getOutput().contains("Available operations"),
                    "output must contain 'Available operations' section: " + result.getOutput());
            assertTrue(result.getOutput().contains("CAPABILITIES"),
                    "output must list CAPABILITIES intent: " + result.getOutput());
            mockServer.verify();
        }

        @Test
        @DisplayName("server error → tool error naming the HTTP status")
        void serverError_reportsStatus() throws Exception {
            // The client returns 5xx as a value, so the tool's status branch reports it.
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient(BASE_URL, rt);
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool(client, om);

            mockServer.expect(requestTo(BASE_URL + "/api/graph/reasoning/query"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withServerError());

            ObjectNode params = om.createObjectNode();
            params.put("operation", "GUESS");
            ToolResult result = tool.execute(params, ctx);

            assertTrue(result.isError(), "server error must produce error result");
            assertTrue(result.getOutput().startsWith("graph_reasoning_query failed (HTTP 500)"), result.getOutput());
            mockServer.verify();
        }

        @Test
        @DisplayName("rejected request → the server's reason plus CAPABILITIES guidance")
        void rejectedRequest_reportsServerReasonWithGuidance() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient(BASE_URL, rt);
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool(client, om);

            // The app's GlobalExceptionHandler body for an IllegalArgumentException.
            mockServer.expect(requestTo(BASE_URL + "/api/graph/reasoning/query"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"error\":\"Bad request\",\"type\":\"IllegalArgumentException\","
                                    + "\"message\":\"Unknown operation: GUESS\"}"));

            ObjectNode params = om.createObjectNode();
            params.put("operation", "GUESS");
            ToolResult result = tool.execute(params, ctx);

            assertTrue(result.isError());
            assertEquals("graph_reasoning_query: Unknown operation: GUESS. "
                    + "Use operation=CAPABILITIES to see valid intents.", result.getOutput());
            mockServer.verify();
        }

        @Test
        @DisplayName("VERIFY SUPPORTED response renders relation and headline")
        void verifySupported_rendersHeadlineAndRelation() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient(BASE_URL, rt);
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool(client, om);

            String verifyJson = """
                    {
                      "status": "SUPPORTED",
                      "intent": "VERIFY",
                      "summary": "Alice WORKS_AT Acme — relation confirmed",
                      "entities": [],
                      "relations": [
                        {"id":"r1","type":"WORKS_AT","sourceId":"a","sourceLabel":"Alice",
                         "targetId":"b","targetLabel":"Acme","weight":0.9,"confidence":0.9,
                         "directed":true,"tags":[],"embeddingDimension":0,"attributes":{}}
                      ],
                      "path": [],
                      "capabilities": [],
                      "guidance": [],
                      "data": {},
                      "resolutions": [],
                      "trace": {"conclusion":"Direct edge r1 supports WORKS_AT","steps":[]}
                    }
                    """;

            mockServer.expect(requestTo(BASE_URL + "/api/graph/reasoning/query"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(verifyJson, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("operation", "verify");
            params.put("entityId", "Alice");
            params.put("targetId", "Acme");
            params.putArray("relationTypes").add("WORKS_AT");

            ToolResult result = tool.execute(params, ctx);

            assertFalse(result.isError(), "VERIFY SUPPORTED should succeed: " + result.getOutput());
            assertTrue(result.getOutput().contains("SUPPORTED"),
                    "output must contain SUPPORTED headline: " + result.getOutput());
            assertTrue(result.getOutput().contains("WORKS_AT"),
                    "output must render the relation type: " + result.getOutput());
            assertTrue(result.getOutput().contains("Alice"),
                    "output must render the source entity: " + result.getOutput());
            mockServer.verify();
        }

        @Test
        @DisplayName("question param is forwarded in the request body")
        void questionParamForwarded() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient(BASE_URL, rt);
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool(client, om);

            mockServer.expect(requestTo(BASE_URL + "/api/graph/reasoning/query"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(
                            "{\"status\":\"OK\",\"intent\":\"SEARCH\",\"summary\":\"results\","
                            + "\"entities\":[],\"relations\":[],\"path\":[],\"capabilities\":[],"
                            + "\"guidance\":[],\"data\":{},\"resolutions\":[]}",
                            MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("question", "Who sent the email?");
            ToolResult result = tool.execute(params, ctx);

            assertFalse(result.isError(), "question param should succeed: " + result.getOutput());
            mockServer.verify();
        }

        @Test
        @DisplayName("quantitative object is forwarded unchanged in the request body")
        void quantitativeForwarded() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient(BASE_URL, rt);
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool(client, om);
            List<JsonNode> bodies = new ArrayList<>();

            mockServer.expect(requestTo(BASE_URL + "/api/graph/reasoning/query"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(request -> bodies.add(
                            om.readTree(((MockClientHttpRequest) request).getBodyAsString())))
                    .andRespond(withSuccess(
                            "{\"status\":\"OK\",\"intent\":\"CALCULATE\",\"summary\":\"Calculated a3 = 30.0.\","
                            + "\"entities\":[],\"relations\":[],\"path\":[],\"capabilities\":[],"
                            + "\"guidance\":[],\"data\":{},\"resolutions\":[]}",
                            MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("operation", "CALCULATE");
            params.putObject("quantitative").putObject("target").put("text", "Total");
            ToolResult result = tool.execute(params, ctx);

            assertFalse(result.isError(), result.getOutput());
            mockServer.verify();
            assertEquals(1, bodies.size());
            assertEquals("CALCULATE", bodies.get(0).path("operation").asText());
            assertEquals(params.get("quantitative"), bodies.get(0).get("quantitative"));
            assertTrue(result.getOutput().contains("Calculated a3 = 30.0."), result.getOutput());
        }
    }

    // ── Formatter ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Output formatter")
    class Formatter {

        private GraphReasoningQueryTool tool;

        @BeforeEach
        void setUp() {
            tool = new GraphReasoningQueryTool((String) null, om);
        }

        @Test
        @DisplayName("empty-entity OVERVIEW renders summary only")
        void emptyEntityOverview_rendersSummary() throws Exception {
            JsonNode result = om.readTree("""
                    {"status":"OK","intent":"OVERVIEW","summary":"0 entities, 0 relations",
                     "entities":[],"relations":[],"path":[],"capabilities":[],
                     "guidance":[],"data":{},"resolutions":[]}
                    """);
            ToolResult tr = tool.formatResult(result);
            assertFalse(tr.isError());
            assertTrue(tr.getOutput().contains("0 entities"));
        }

        @Test
        @DisplayName("entities section renders label and type")
        void entitiesSection() throws Exception {
            JsonNode result = om.readTree("""
                    {"status":"OK","intent":"SEARCH","summary":"2 matches",
                     "entities":[
                       {"id":"e1","label":"Jordan Lee","type":"PERSON","score":0.95,"weight":0.9,
                        "confidence":0.9,"tags":[],"embeddingDimension":0,"attributes":{}},
                       {"id":"e2","label":"Alex Jordan","type":"PERSON","score":0.80,"weight":0.8,
                        "confidence":0.8,"tags":[],"embeddingDimension":0,"attributes":{}}
                     ],
                     "relations":[],"path":[],"capabilities":[],"guidance":[],"data":{},"resolutions":[]}
                    """);
            ToolResult tr = tool.formatResult(result);
            assertFalse(tr.isError());
            assertTrue(tr.getOutput().contains("Jordan Lee"), "must render first entity");
            assertTrue(tr.getOutput().contains("PERSON"),     "must render entity type");
            assertTrue(tr.getOutput().contains("Alex Jordan"), "must render second entity");
            assertTrue(tr.getOutput().contains("id=e1"), "must expose stable id for follow-up queries");
        }

        @Test
        @DisplayName("guidance section renders when non-empty")
        void guidanceSection() throws Exception {
            JsonNode result = om.readTree("""
                    {"status":"NOT_FOUND","intent":"PATH","summary":"No path found",
                     "entities":[],"relations":[],"path":[],"capabilities":[],
                     "guidance":["Try SEARCH to locate both entities first"],
                     "data":{},"resolutions":[]}
                    """);
            ToolResult tr = tool.formatResult(result);
            assertFalse(tr.isError());
            assertTrue(tr.getOutput().contains("Guidance"), "must render guidance section");
            assertTrue(tr.getOutput().contains("SEARCH"),   "must include guidance text");
        }

        @Test
        @DisplayName("FACTS renders ranked atoms returned in data.facts")
        void factsSection() throws Exception {
            JsonNode result = om.readTree("""
                    {"status":"OK","intent":"FACTS","summary":"Returned 1 ranked graph fact(s).",
                     "entities":[],"relations":[],"path":[],"capabilities":[],"guidance":[],
                     "data":{"facts":[
                       {"atom":"WORKS_AT(alice,acme)","kind":"relation","confidence":0.91,"source":"r1"}
                     ]},"resolutions":[]}
                    """);
            ToolResult tr = tool.formatResult(result);
            assertFalse(tr.isError());
            assertTrue(tr.getOutput().contains("Facts (1)"), tr.getOutput());
            assertTrue(tr.getOutput().contains("WORKS_AT(alice,acme)"), tr.getOutput());
            assertTrue(tr.getOutput().contains("confidence=0.91"), tr.getOutput());
            assertEquals(1, tr.getMetadata().get("factCount"));
        }

        @Test
        @DisplayName("metadata contains status, intent, entity and relation counts")
        void metadataPopulated() throws Exception {
            JsonNode result = om.readTree("""
                    {"status":"OK","intent":"NEIGHBORS","summary":"3 neighbors",
                     "entities":[
                       {"id":"e1","label":"A","type":"X","score":0,"weight":0,"confidence":0,
                        "tags":[],"embeddingDimension":0,"attributes":{}}
                     ],
                     "relations":[
                       {"id":"r1","type":"LINKED","sourceId":"a","sourceLabel":"A","targetId":"b",
                        "targetLabel":"B","weight":0.5,"confidence":0.5,"directed":true,
                        "tags":[],"embeddingDimension":0,"attributes":{}}
                     ],
                     "path":[],"capabilities":[],"guidance":[],"data":{},"resolutions":[]}
                    """);
            ToolResult tr = tool.formatResult(result);
            assertEquals("OK",        tr.getMetadata().get("status"));
            assertEquals("NEIGHBORS", tr.getMetadata().get("intent"));
            assertEquals(1,           tr.getMetadata().get("entityCount"));
            assertEquals(1,           tr.getMetadata().get("relationCount"));
        }

        @Test
        @DisplayName("engine VERIFY renders the trace tree and labels the stored relation opinion as a learned score")
        void verifyRendersTraceTree() {
            UnifiedGraph graph = new UnifiedGraph().graphId("g")
                    .addEntity("alice", "PERSON", "Alice")
                    .addEntity("acme", "COMPANY", "Acme")
                    .addRelation("r1", "alice", "acme", "WORKS_AT", 0.9)
                    .putRelationOpinion("r1", new Opinion(0.8, 0.05, 0.15, 0.5));
            GraphQueryEngine.Result result = new GraphQueryEngine().query(graph,
                    GraphQueryEngine.Query.claim(GraphQueryEngine.Intent.VERIFY, "alice", "WORKS_AT", "acme"));

            ToolResult tr = tool.formatResult(om.valueToTree(result));

            String out = tr.getOutput();
            assertTrue(out.contains("SUPPORTED"), out);
            assertTrue(out.contains("Reasoning trace (INFERENCE)"), out);
            assertTrue(out.contains("[trace.root.0]"), out);
            // The opinion's expectation is 0.8 + 0.5 * 0.15 = 0.875. It is a learned score, so it is
            // labelled as one instead of being shown as the recorded fact's opinion.
            assertTrue(out.contains("[FACT confidence=0.900 learnedScore=0.875] WORKS_AT(alice,acme)"),
                    "trace step labels the learned score: " + out);
            assertFalse(out.contains("[b=0.800 d=0.050 u=0.150]"), out);
            assertTrue(out.contains("learnedScore=0.88  id=r1"), "relation line labels the learned score: " + out);
            assertFalse(out.contains("opinion(b=0.80 d=0.05 u=0.15 a=0.50)  id=r1"), out);
            assertTrue(out.contains("does not decide"), out);
            assertEquals(result.trace().size(), tr.getMetadata().get("traceSteps"));
        }

        @Test
        @DisplayName("engine SCHEMA renders its vocabulary from data")
        void schemaRendersDataVocabulary() {
            UnifiedGraph graph = new UnifiedGraph().graphId("g")
                    .addEntity("alice", "PERSON", "Alice")
                    .addEntity("acme", "COMPANY", "Acme")
                    .addRelation("r1", "alice", "acme", "WORKS_AT", 0.9);

            String out = tool.formatResult(om.valueToTree(
                    new GraphQueryEngine().query(graph, GraphQueryEngine.Query.schema()))).getOutput();

            assertTrue(out.contains("\nData:\n"), out);
            assertTrue(out.contains("entityTypes (2): {COMPANY: 1, PERSON: 1}"), out);
            assertTrue(out.contains("relationTypes (1): {WORKS_AT: 1}"), out);
        }

        @Test
        @DisplayName("engine DESCRIBE renders confidence, learned score, tags, time and attributes")
        void describeRendersEntityDetails() {
            UnifiedGraph graph = new UnifiedGraph().graphId("g")
                    .addEntity(GraphEntity.builder("alice").type("PERSON").label("Alice").confidence(0.9)
                            .tag("crawled").timestamp(Instant.parse("2024-03-01T00:00:00Z"))
                            .attribute("description", "Staff engineer at Acme").build())
                    .putEntityOpinion("alice", new Opinion(0.7, 0.1, 0.2, 0.5));

            String out = tool.formatResult(om.valueToTree(
                    new GraphQueryEngine().query(graph, GraphQueryEngine.Query.describe("alice")))).getOutput();

            assertTrue(out.contains("confidence=0.90"), out);
            // The opinion's expectation is 0.7 + 0.5 * 0.2 = 0.8, labelled as a learned score.
            assertTrue(out.contains("learnedScore=0.80  id=alice"), "entity line labels the learned score: " + out);
            assertFalse(out.contains("opinion(b=0.70 d=0.10 u=0.20 a=0.50)  id=alice"), out);
            assertTrue(out.contains("tags=crawled"), out);
            assertTrue(out.contains("time=2024-03-01T00:00:00Z"), out);
            assertTrue(out.contains("attributes: {description: \"Staff engineer at Acme\"}"), out);
        }

        @Test
        @DisplayName("engine ARTIFACT renders the artifact text line by line")
        void artifactRendersText() {
            UnifiedGraph graph = new UnifiedGraph().graphId("g")
                    .putArtifact("notes.md", "line one\nline two".getBytes(StandardCharsets.UTF_8));

            String out = tool.formatResult(om.valueToTree(
                    new GraphQueryEngine().query(graph, GraphQueryEngine.Query.artifact("notes.md")))).getOutput();

            assertTrue(out.contains("  name: notes.md"), out);
            assertTrue(out.contains("  text:\n    line one\n    line two"), out);
        }

        @Test
        @DisplayName("a trace longer than the line budget says how many steps were cut")
        void longTraceReportsHiddenSteps() {
            List<ReasoningTrace.Step> premises = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                premises.add(ReasoningTrace.Step.fact("F" + i + "(a,b)", 0.9, "s" + i));
            }
            ObjectNode result = om.createObjectNode()
                    .put("status", "OK").put("intent", "FACTS").put("summary", "25 facts");
            result.set("trace", om.valueToTree(ReasoningTrace.of(ReasoningTrace.Step.derived(
                    ReasoningTrace.StepKind.QUERY, "25 facts", "graph_query:facts", 1.0, premises))));

            ToolResult tr = tool.formatResult(result);

            String out = tr.getOutput();
            assertTrue(out.contains("Reasoning trace (QUERY)"), out);
            assertTrue(out.contains("F0(a,b)"), out);
            assertFalse(out.contains("F24(a,b)"), out);
            assertTrue(out.contains("... 7 more trace step(s) not shown"), out);
            assertEquals(26, tr.getMetadata().get("traceSteps"));
        }

        @Test
        @DisplayName("a trace this client cannot rebuild still shows its conclusion")
        void unreadableTraceFallsBackToConclusion() throws Exception {
            JsonNode legacy = om.readTree("""
                    {"status":"SUPPORTED","intent":"VERIFY","summary":"Supported",
                     "trace":{"conclusion":"Direct edge r1 supports WORKS_AT","steps":[]}}
                    """);
            JsonNode unknownKind = om.readTree("""
                    {"status":"OK","intent":"FACTS","summary":"1 fact",
                     "trace":{"conclusion":{"kind":"NOT_A_KIND","conclusion":"Root text","premises":[]}}}
                    """);

            ToolResult tr = tool.formatResult(legacy);

            assertTrue(tr.getOutput().contains("Reasoning: Direct edge r1 supports WORKS_AT"), tr.getOutput());
            assertEquals(0, tr.getMetadata().get("traceSteps"));
            String out = tool.formatResult(unknownKind).getOutput();
            assertTrue(out.contains("Reasoning: Root text"), out);
        }

        @Test
        @DisplayName("unresolved inputs list their candidates")
        void unresolvedInputListsCandidates() throws Exception {
            JsonNode result = om.readTree("""
                    {"status":"AMBIGUOUS","intent":"DESCRIBE","summary":"'Jordan' matches several entities",
                     "resolutions":[
                       {"role":"entityId","input":"Jordan","resolvedId":null,"resolvedLabel":null,"score":0.8,
                        "candidates":[{"id":"e1","label":"Jordan Lee","score":0.8},
                                      {"id":"e2","label":"Alex Jordan","score":0.79}]}]}
                    """);

            String out = tool.formatResult(result).getOutput();

            assertTrue(out.contains("entityId 'Jordan' unresolved"), out);
            assertTrue(out.contains("candidates: Jordan Lee (id=e1, score=0.80); Alex Jordan (id=e2, score=0.79)"), out);
        }

        @Test
        @DisplayName("large data fields are bounded")
        void largeDataFieldIsBounded() {
            ObjectNode result = om.createObjectNode()
                    .put("status", "OK").put("intent", "ASSETS").put("summary", "1 vector layer");
            ArrayNode vector = result.putObject("data").putObject("selectedVectorLayer").putArray("vector");
            for (int i = 0; i < 5_000; i++) {
                vector.add(0.123456);
            }

            String out = tool.formatResult(result).getOutput();

            assertTrue(out.contains("selectedVectorLayer (1): {vector: [0.123456, "), out);
            assertTrue(out.contains(" more]}"), out);
            assertTrue(out.length() < 6_000, "bounded, was " + out.length());
        }
    }
}
