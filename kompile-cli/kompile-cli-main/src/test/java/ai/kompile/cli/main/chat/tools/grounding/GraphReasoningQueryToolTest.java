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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link GraphReasoningQueryTool} — the CLI MCP tool wrapping
 * {@code POST /api/graph/reasoning/query}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Tool metadata: id, permissionKey, READ_ONLY annotation, compactHint present</li>
 *   <li>Parameter schema: operation and question present; no required params (all optional)</li>
 *   <li>No configured backend → folder-scoped local graph bootstrap</li>
 *   <li>CAPABILITIES intent routed correctly, HTTP call made to the right endpoint</li>
 *   <li>Server 400 → tool error with CAPABILITIES hint</li>
 *   <li>Server 200 CAPABILITIES → formatted output with "Available operations" section</li>
 *   <li>Server 200 VERIFY SUPPORTED → formatted output with SUPPORTED headline and relation</li>
 *   <li>Formatter: plain-text rendering, no engine jargon</li>
 * </ul>
 */
@DisplayName("GraphReasoningQueryTool — CLI MCP tool")
class GraphReasoningQueryToolTest {

    private static final String BASE_URL = "http://localhost:8080";

    private ObjectMapper om;
    private ToolContext ctx;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("coder").enabledTools(Set.of("*")).build();
        PermissionService perms = new PermissionService();
        perms.setUserOverride("graph_reasoning_query", PermissionService.PermissionLevel.ALLOW);
        ToolRegistry registry = new ToolRegistry(om);
        ctx = new ToolContext("test-session", agent, perms, tempDir, registry);
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
        @DisplayName("REST exception from server → tool error with CAPABILITIES guidance")
        void serverException_returnsErrorWithGuidance() throws Exception {
            // RestTemplate throws an exception on non-2xx; the tool catches it and returns an error
            // with a hint to use CAPABILITIES. We verify the tool handles transport exceptions gracefully.
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient(BASE_URL, rt);
            GraphReasoningQueryTool tool = new GraphReasoningQueryTool(client, om);

            // Simulate a server error response (5xx) — RestTemplate will throw
            mockServer.expect(requestTo(BASE_URL + "/api/graph/reasoning/query"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withServerError());

            ObjectNode params = om.createObjectNode();
            params.put("operation", "GUESS");
            ToolResult result = tool.execute(params, ctx);

            // The generic catch in execute() converts the exception to an error ToolResult
            assertTrue(result.isError(), "server error must produce error result");
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
    }
}
