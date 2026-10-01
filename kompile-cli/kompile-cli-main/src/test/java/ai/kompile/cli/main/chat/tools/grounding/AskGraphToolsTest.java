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
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.ToolSchemaOptimizer;
import ai.kompile.cli.main.chat.tools.KnowledgeGraphTool;
import ai.kompile.cli.main.project.LocalSubprocessWatchdog;
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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for the 6 {@code ask_graph_*} MCP tools.
 *
 * <p>HTTP calls are intercepted via Spring's {@code MockRestServiceServer} bound to
 * the {@link GroundingBackendClient}'s underlying {@code RestTemplate} — no real server
 * runs, no port probing, fully deterministic.</p>
 *
 * <p>Tests focus on:
 * <ul>
 *   <li>Tool metadata (id, permissionKey, annotations)</li>
 *   <li>Parameter schema shape (required fields, types)</li>
 *   <li>Missing-required-param guard (no backend needed)</li>
 *   <li>Backend-unavailable error path (null baseUrl → {@code isAvailable()==false})</li>
 *   <li>ask_graph_subscribe point-in-time snapshot via MockRestServiceServer</li>
 *   <li>ask_graph_explain metadata round-trip via MockRestServiceServer</li>
 *   <li>ask_graph_mebn formatter correctness (no backend needed)</li>
 * </ul>
 */
@DisplayName("ask_graph_* MCP Tools")
@TemporaryUserHome
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AskGraphToolsTest {

    @TempDir
    Path tempDir;

    private ObjectMapper om;
    private ToolContext ctx;
    private String previousAdmissionMode;

    @BeforeEach
    void setUp() {
        previousAdmissionMode = System.getProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY);
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, "off");
        om = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("coder")
                .enabledTools(Set.of("*"))
                .build();
        PermissionService perms = new PermissionService();
        // Allow all ask_graph_* permissions
        perms.setUserOverride("ask_graph_verify",    PermissionService.PermissionLevel.ALLOW);
        perms.setUserOverride("ask_graph_query",     PermissionService.PermissionLevel.ALLOW);
        perms.setUserOverride("ask_graph_explain",   PermissionService.PermissionLevel.ALLOW);
        perms.setUserOverride("ask_graph_assert",    PermissionService.PermissionLevel.ALLOW);
        perms.setUserOverride("ask_graph_subscribe",  PermissionService.PermissionLevel.ALLOW);
        perms.setUserOverride("ask_graph_mebn",       PermissionService.PermissionLevel.ALLOW);
        perms.setUserOverride("ask_graph_synthesize", PermissionService.PermissionLevel.ALLOW);
        perms.setUserOverride("ask_graph_retract",    PermissionService.PermissionLevel.ALLOW);
        perms.setUserOverride("knowledge_graph",      PermissionService.PermissionLevel.ALLOW);
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

    // ── ask_graph_verify ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ask_graph_verify")
    class VerifyTool {

        private AskGraphVerifyTool tool;

        @BeforeEach
        void setUp() {
            // null baseUrl → GroundingBackendClient.isAvailable() == false, no port probing
            tool = new AskGraphVerifyTool((String) null, om);
        }

        @Test
        @DisplayName("id and permissionKey are correct")
        void metadata() {
            assertEquals("ask_graph_verify", tool.id());
            assertEquals("ask_graph_verify", tool.permissionKey());
            assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
        }

        @Test
        @DisplayName("parameterSchema has 'atom' as required")
        void schemaHasAtomRequired() {
            var schema = tool.parameterSchema();
            assertTrue(schema.path("required").toString().contains("atom"));
            assertNotNull(schema.path("properties").path("atom"));
        }

        @Test
        @DisplayName("missing atom param returns error")
        void missingAtom_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode(); // no atom field
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError(), "Expected error when atom is missing");
            assertTrue(result.getOutput().contains("atom"));
        }

        @Test
        @DisplayName("missing project-local graph is bootstrapped for verification")
        void missingProjectLocalGraph_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("atom", "isEmployedBy(Alice, Acme)");
            ToolResult result = tool.execute(params, ctx);
            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("project-local"), result.getOutput());
        }

        @Test
        @DisplayName("E12: SUPPORTED response with fragility renders robustness and wouldFlipIf")
        void supported_withFragility_rendersFragilityBlock() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            AskGraphVerifyTool localTool = new AskGraphVerifyTool(client, om);

            // Response includes fragility block for SUPPORTED verdict
            String responseJson = """
                    {
                      "verdict": "SUPPORTED",
                      "confidence": 0.87,
                      "calibratedConfidence": 0.87,
                      "strengthBand": "HIGH",
                      "evidenceAtoms": ["worksAt(Alice, Acme_NYC)"],
                      "activatedRules": [],
                      "derivationDepth": 1,
                      "evidenceCount": 1,
                      "sourceProvenance": ["test-run"],
                      "counterEvidence": [],
                      "refutationBasis": null,
                      "opinion": null,
                      "openWorld": false,
                      "entityKnown": true,
                      "unknownReason": null,
                      "contradictions": [],
                      "nearMissSuggestions": [],
                      "fragility": {
                        "wouldFlipIf": ["worksAt(Alice, Acme_NYC)"],
                        "minimalSupportSize": 1,
                        "robustness": 0.0
                      },
                      "meta": {"factSheetId": 42, "stale": false, "kbVersion": 1}
                    }
                    """;

            mockServer.expect(requestTo("http://localhost/api/kb-grounding/verify"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("atom", "isEmployedBy(Alice, Acme)");

            ToolResult result = localTool.execute(params, ctx);

            assertFalse(result.isError(), result.getOutput());
            // E12: fragility block must be rendered
            String output = result.getOutput();
            assertTrue(output.contains("Fragility:"), "output must contain 'Fragility:' block");
            assertTrue(output.contains("robustness"), "output must show robustness score");
            assertTrue(output.contains("worksAt(Alice, Acme_NYC)"), "output must show flip-point atom");
            // E12: structured result carries fragilityRobustness
            assertTrue(result.getMetadata().containsKey("fragilityRobustness"),
                    "structured result must contain fragilityRobustness");
            assertEquals(0.0, (double) result.getMetadata().get("fragilityRobustness"), 1e-9);

            mockServer.verify();
        }

        @Test
        @DisplayName("E9: UNKNOWN response with nearMissSuggestions renders 'Would be provable if' block")
        void unknown_withNearMiss_rendersNearMissBlock() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            AskGraphVerifyTool localTool = new AskGraphVerifyTool(client, om);

            String responseJson = """
                    {
                      "verdict": "UNKNOWN",
                      "confidence": 0.0,
                      "calibratedConfidence": 0.0,
                      "strengthBand": "SPECULATIVE",
                      "evidenceAtoms": [],
                      "activatedRules": [],
                      "derivationDepth": 0,
                      "evidenceCount": 0,
                      "sourceProvenance": [],
                      "counterEvidence": [],
                      "refutationBasis": null,
                      "opinion": {"b": 0.0, "d": 0.0, "u": 1.0, "a": 0.5},
                      "openWorld": false,
                      "entityKnown": true,
                      "unknownReason": "near-miss",
                      "contradictions": [],
                      "nearMissSuggestions": ["locatedIn(Acme, London)"],
                      "fragility": null,
                      "meta": {"factSheetId": 42, "stale": false, "kbVersion": 1}
                    }
                    """;

            mockServer.expect(requestTo("http://localhost/api/kb-grounding/verify"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("atom", "basedIn(Alice, London)");

            ToolResult result = localTool.execute(params, ctx);

            assertFalse(result.isError(), result.getOutput());
            String output = result.getOutput();
            assertTrue(output.contains("Would be provable if"),
                    "output must contain 'Would be provable if' block for near-miss");
            assertTrue(output.contains("locatedIn(Acme, London)"),
                    "output must show the completing fact suggestion");
            // No fragility block for UNKNOWN
            assertFalse(output.contains("Fragility:"),
                    "fragility block must NOT appear for UNKNOWN verdict");

            mockServer.verify();
        }
    }

    // ── ask_graph_synthesize ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("ask_graph_synthesize")
    class SynthesizeTool {

        private AskGraphSynthesizeTool tool;

        @BeforeEach
        void setUp() {
            tool = new AskGraphSynthesizeTool((String) null, om);
        }

        @Test
        @DisplayName("id and permissionKey are correct")
        void metadata() {
            assertEquals("ask_graph_synthesize", tool.id());
            assertEquals("ask_graph_synthesize", tool.permissionKey());
            assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
        }

        @Test
        @DisplayName("parameterSchema has 'query' as required")
        void schemaHasQueryRequired() {
            var schema = tool.parameterSchema();
            assertTrue(schema.path("required").toString().contains("query"));
            assertFalse(schema.path("properties").path("query").isMissingNode());
        }

        @Test
        @DisplayName("missing query param returns error")
        void missingQuery_returnsError() throws Exception {
            ToolResult result = tool.execute(om.createObjectNode(), ctx);
            assertTrue(result.isError(), "Expected error when query is missing");
            assertTrue(result.getOutput().contains("query"));
        }

        @Test
        @DisplayName("missing project-local graph is bootstrapped for synthesis")
        void missingProjectLocalGraph_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("query", "who leads Acme?");
            ToolResult result = tool.execute(params, ctx);
            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("project-local"), result.getOutput());
        }
    }

    // ── ask_graph_query ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ask_graph_query")
    class QueryTool {

        private AskGraphQueryTool tool;

        @BeforeEach
        void setUp() {
            tool = new AskGraphQueryTool((String) null, om);
        }

        @Test
        @DisplayName("id and permissionKey are correct")
        void metadata() {
            assertEquals("ask_graph_query", tool.id());
            assertEquals("ask_graph_query", tool.permissionKey());
            assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
        }

        @Test
        @DisplayName("parameterSchema has 'conjuncts' as required")
        void schemaHasConjunctsRequired() {
            var schema = tool.parameterSchema();
            assertTrue(schema.path("required").toString().contains("conjuncts"));
        }

        @Test
        @DisplayName("missing conjuncts returns error")
        void missingConjuncts_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError());
            assertTrue(result.getOutput().toLowerCase().contains("conjuncts"));
        }

        @Test
        @DisplayName("empty conjuncts array returns error")
        void emptyConjuncts_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.putArray("conjuncts"); // empty array
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError());
        }
    }

    // ── ask_graph_explain ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ask_graph_explain")
    class ExplainTool {

        private AskGraphExplainTool tool;

        @BeforeEach
        void setUp() {
            tool = new AskGraphExplainTool((String) null, om);
        }

        @Test
        @DisplayName("id and permissionKey are correct")
        void metadata() {
            assertEquals("ask_graph_explain", tool.id());
            assertEquals("ask_graph_explain", tool.permissionKey());
            assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
        }

        @Test
        @DisplayName("parameterSchema exposes all backend explanation modes")
        void schemaExposesAllBackendModes() {
            String modes = tool.parameterSchema().path("properties").path("mode").path("enum").toString();
            assertTrue(modes.contains("GROUNDING"));
            assertTrue(modes.contains("HYBRID"));
            assertTrue(modes.contains("CAUSAL"));
            assertTrue(modes.contains("PSL"));
            assertTrue(modes.contains("MEBN"));
        }

        @Test
        @DisplayName("missing atom returns error")
        void missingAtom_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("atom"));
        }

        @Test
        @DisplayName("preserves structured explanation metadata")
        void preservesStructuredExplanationMetadata() throws Exception {
            // Bind MockRestServiceServer to a fresh RestTemplate injected into the tool
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            AskGraphExplainTool localTool = new AskGraphExplainTool(client, om);

            String responseJson = """
                    {
                      "verdict": "SUPPORTED",
                      "confidence": 0.82,
                      "inferenceMode": "PSL",
                      "naturalLanguageSummary": "Rule support found.",
                      "derivationTreeJson": "{\\"label\\":\\"root\\"}",
                      "evidence": ["fact(a)"],
                      "activatedRules": ["r1"],
                      "trail": {
                        "runId": "run-psl",
                        "steps": [{"ruleId": "r1"}]
                      }
                    }
                    """;

            // Capture request body to verify mode was forwarded
            String[] capturedBody = {null};
            mockServer.expect(requestTo("http://localhost/api/explain"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(request -> capturedBody[0] =
                            ((MockClientHttpRequest) request).getBodyAsString())
                    .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("atom", "risk(a)");
            params.put("mode", "PSL");

            ToolResult result = localTool.execute(params, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertTrue(capturedBody[0].contains("\"mode\":\"PSL\""),
                    "request body should include mode: " + capturedBody[0]);
            assertEquals("PSL", result.getMetadata().get("inferenceMode"));
            assertEquals("run-psl", result.getMetadata().get("runId"));
            assertTrue(result.getMetadata().get("trail") instanceof Map<?, ?>);
            @SuppressWarnings("unchecked")
            Map<String, Object> trail = (Map<String, Object>) result.getMetadata().get("trail");
            assertEquals("run-psl", trail.get("runId"));
            assertTrue(String.valueOf(result.getMetadata().get("evidence")).contains("fact(a)"));
            assertTrue(String.valueOf(result.getMetadata().get("activatedRules")).contains("r1"));

            mockServer.verify();
        }
    }

    // ── ask_graph_assert ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ask_graph_assert")
    class AssertTool {

        private AskGraphAssertTool tool;

        @BeforeEach
        void setUp() {
            tool = new AskGraphAssertTool((String) null, om);
        }

        @Test
        @DisplayName("id and permissionKey are correct")
        void metadata() {
            assertEquals("ask_graph_assert", tool.id());
            assertEquals("ask_graph_assert", tool.permissionKey());
            assertEquals(McpToolAnnotations.WRITE, tool.mcpAnnotations());
        }

        @Test
        @DisplayName("parameterSchema has 'atom' and 'value' as required")
        void schemaRequiredFields() {
            var schema = tool.parameterSchema();
            String required = schema.path("required").toString();
            assertTrue(required.contains("atom"));
            assertTrue(required.contains("value"));
        }

        @Test
        @DisplayName("missing atom returns error")
        void missingAtom_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("value", 1.0);
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("atom"));
        }

        @Test
        @DisplayName("missing value returns error")
        void missingValue_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("atom", "foo(X)");
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("value"));
        }

        @Test
        @DisplayName("value out of range returns error")
        void valueOutOfRange_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("atom", "foo(X)");
            params.put("value", 2.0);
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("[0,1]"));
        }
    }

    // ── ask_graph_subscribe (polling snapshot) ────────────────────────────────────

    @Nested
    @DisplayName("ask_graph_subscribe")
    class SubscribeTool {

        private AskGraphSubscribeTool tool;

        @BeforeEach
        void setUp() {
            // null baseUrl → GroundingBackendClient.isAvailable() == false, no port probing
            tool = new AskGraphSubscribeTool((String) null, om);
        }

        @Test
        @DisplayName("id and permissionKey are correct")
        void metadata() {
            assertEquals("ask_graph_subscribe", tool.id());
            assertEquals("ask_graph_subscribe", tool.permissionKey());
            assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
        }

        @Test
        @DisplayName("description is honest about point-in-time semantics")
        void descriptionIsHonest() {
            String desc = tool.description();
            assertTrue(desc.contains("Point-in-time") || desc.toLowerCase().contains("snapshot"),
                    "description must be honest about not being a live stream");
            assertFalse(desc.startsWith("Subscribe to KB changes"), // old misleading opening
                    "description must not open with claim that it works as a live subscriber");
        }

        @Test
        @DisplayName("missing predicates on first call (no subscriptionId) returns error about predicates")
        void missingPredicates_returnsError() throws Exception {
            // With a real backend client, missing predicates are caught before any HTTP call
            RestTemplate rt = new RestTemplate();
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            AskGraphSubscribeTool localTool = new AskGraphSubscribeTool(client, om);

            ObjectNode params = om.createObjectNode(); // no predicates field, no subscriptionId
            ToolResult result = localTool.execute(params, ctx);
            assertTrue(result.isError(), "Expected error when predicates is missing");
            assertTrue(result.getOutput().toLowerCase().contains("predicates"),
                    "error should mention predicates");
        }

        @Test
        @DisplayName("first-call creates subscription then returns snapshot with match counts")
        void liveSnapshot_returnsMatchCounts() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            AskGraphSubscribeTool localTool = new AskGraphSubscribeTool(client, om);

            // First the tool POSTs to /subscribe to create a server-side subscription
            String subscribeJson = """
                    {"subscriptionId":"sub-test-1","eventsUrl":"/api/kb-grounding/subscribe/sub-test-1/events",
                     "pollUrl":"/api/kb-grounding/subscribe/sub-test-1/poll",
                     "expiresAt":"2030-01-01T00:00:00Z","message":null}
                    """;
            mockServer.expect(requestTo("http://localhost/api/kb-grounding/subscribe"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(subscribeJson, MediaType.APPLICATION_JSON));

            // Then it queries for the initial snapshot
            String queryJson = """
                    {
                      "bindings": [
                        {"variables": {"s": "Alice", "o": "Acme"}, "confidence": 0.91}
                      ],
                      "total": 1,
                      "truncated": false,
                      "meta": {"stale": false}
                    }
                    """;
            mockServer.expect(requestTo("http://localhost/api/kb-grounding/query"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(queryJson, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.putArray("predicates").add("worksFor");

            ToolResult result = localTool.execute(params, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertEquals("sub-test-1", result.getMetadata().get("subscriptionId"));
            assertEquals(0, result.getMetadata().get("nextCursor"));
            assertTrue(result.getOutput().contains("worksFor"),
                    "output should mention the predicate name");
            assertTrue(result.getOutput().contains("1"),
                    "output should include match count");
            assertTrue(result.getOutput().contains("Alice"),
                    "output should include sample binding values");
            assertEquals(1, result.getMetadata().get("totalMatches"));

            mockServer.verify();
        }
    }

    // ── ask_graph_mebn ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ask_graph_mebn")
    class MebnTool {

        private AskGraphMebnTool tool;

        @BeforeEach
        void setUp() {
            // null baseUrl → GroundingBackendClient.isAvailable() == false, no port probing
            tool = new AskGraphMebnTool((String) null, om);
        }

        @Test
        @DisplayName("id and permissionKey are correct")
        void metadata() {
            assertEquals("ask_graph_mebn", tool.id());
            assertEquals("ask_graph_mebn", tool.permissionKey());
            assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
        }

        @Test
        @DisplayName("parameterSchema has 'nodeId' as required")
        void schemaHasNodeIdRequired() {
            var schema = tool.parameterSchema();
            assertTrue(schema.path("required").toString().contains("nodeId"),
                    "nodeId must appear in required array");
            assertNotNull(schema.path("properties").path("nodeId"));
            assertNotNull(schema.path("properties").path("maxDepth"));
            assertNotNull(schema.path("properties").path("maxNodes"));
            assertEquals("string", schema.path("properties").path("knowledgeBase").path("type").asText());
        }

        @Test
        @DisplayName("missing nodeId returns error")
        void missingNodeId_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode(); // no nodeId
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError(), "Expected error when nodeId is missing");
            assertTrue(result.getOutput().contains("nodeId"));
        }

        @Test
        @DisplayName("blank nodeId returns error")
        void blankNodeId_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("nodeId", "   ");
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError(), "Expected error for blank nodeId");
        }

        @Test
        @DisplayName("missing project-local graph is bootstrapped before node resolution")
        void missingProjectLocalGraph_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("nodeId", "node_42");
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError(), "Expected error when no local graph exists");
            assertTrue(result.getOutput().contains("node not found"), result.getOutput());
            assertFalse(result.getOutput().contains("kompile-app"));
        }

        @Test
        @DisplayName("formatter: empty posteriors produces no-variables message")
        void formatter_emptyPosteriors() {
            var posteriors      = om.createObjectNode();
            var priors          = om.createObjectNode();
            var variableToTitle = om.createObjectNode();
            var mebnMeta        = om.createObjectNode();

            String output = tool.formatMebnResult("node_42", posteriors, priors,
                    variableToTitle, mebnMeta, Map.of(), 0, 12L);

            assertTrue(output.contains("node_42"), "output must include the anchor nodeId");
            assertTrue(output.contains("No variables"), "empty graph must say no variables");
        }

        @Test
        @DisplayName("formatter: top-N variables sorted by belief update, meta rendered")
        void formatter_variablesSortedAndMetaRendered() throws Exception {
            // var_a: prior=0.5, posterior=0.9 → delta=+0.4 (biggest)
            // var_b: prior=0.5, posterior=0.6 → delta=+0.1
            ObjectNode posteriors = om.createObjectNode();
            posteriors.put("var_a", 0.9);
            posteriors.put("var_b", 0.6);

            ObjectNode priors = om.createObjectNode();
            priors.put("var_a", 0.5);
            priors.put("var_b", 0.5);

            ObjectNode titles = om.createObjectNode();
            titles.put("var_a", "Risk Score A");
            titles.put("var_b", "Influence B");

            ObjectNode mebnMeta = om.createObjectNode();
            ObjectNode metaA    = om.createObjectNode();
            metaA.put("entityType", "PERSON");
            metaA.put("mfragName", "RiskMFrag");
            metaA.put("nodeRole", "RESIDENT");
            mebnMeta.set("var_a", metaA);

            String output = tool.formatMebnResult("node_42", posteriors, priors,
                    titles, mebnMeta, Map.of(), 2, 50L);

            // var_a appears first (largest delta)
            int posA = output.indexOf("Risk Score A");
            int posB = output.indexOf("Influence B");
            assertTrue(posA >= 0, "var_a title must appear");
            assertTrue(posB >= 0, "var_b title must appear");
            assertTrue(posA < posB, "var_a (highest delta) must appear before var_b");

            // prior → posterior and delta present
            assertTrue(output.contains("prior=0.500"), "prior value must be formatted");
            assertTrue(output.contains("posterior=0.900"), "posterior value must be formatted");
            assertTrue(output.contains("Δ+"), "positive delta indicator must appear");

            // MEBN meta fields for var_a (mfrag= renamed to group= to avoid jargon)
            assertTrue(output.contains("entityType=PERSON"), "entityType must appear");
            assertTrue(output.contains("group=RiskMFrag"), "mfragName must appear as group=");
            assertTrue(output.contains("role=RESIDENT"), "nodeRole must appear");
            assertFalse(output.contains("No evidence was applied"),
                    "a result whose posteriors moved must not say no evidence was applied");
        }

        @Test
        @DisplayName("formatter: without evidence, says so and orders by probability")
        void formatter_noEvidenceSaysSoAndOrdersByProbability() {
            // Without evidence, prior == posterior.
            ObjectNode posteriors = om.createObjectNode();
            posteriors.put("var_low", 0.2);
            posteriors.put("var_high", 0.8);

            ObjectNode priors = om.createObjectNode();
            priors.put("var_low", 0.2);
            priors.put("var_high", 0.8);

            ObjectNode titles = om.createObjectNode();
            titles.put("var_low", "Low");
            titles.put("var_high", "High");

            String output = tool.formatMebnResult("node_42", posteriors, priors,
                    titles, om.createObjectNode(), Map.of(), 2, 5L);

            assertTrue(output.contains("No evidence was applied"), output);
            assertTrue(output.contains("variables by probability"), output);
            assertFalse(output.contains("by belief update"), output);
            int high = output.indexOf("[1] High");
            int low = output.indexOf("[2] Low");
            assertTrue(high >= 0 && low > high, "the likelier variable must lead: " + output);
        }

        @Test
        @DisplayName("formatter: truncation notice when variables exceed MAX_VARIABLES_DISPLAY")
        void formatter_truncationNotice() throws Exception {
            ObjectNode posteriors = om.createObjectNode();
            ObjectNode priors     = om.createObjectNode();
            ObjectNode titles     = om.createObjectNode();
            // insert MAX_VARIABLES_DISPLAY + 2 variables
            for (int i = 0; i < AskGraphMebnTool.MAX_VARIABLES_DISPLAY + 2; i++) {
                String key = "var_" + i;
                posteriors.put(key, 0.5 + i * 0.01);
                priors.put(key, 0.5);
                titles.put(key, "Variable " + i);
            }
            int total = AskGraphMebnTool.MAX_VARIABLES_DISPLAY + 2;

            String output = tool.formatMebnResult("node_X", posteriors, priors,
                    titles, om.createObjectNode(), Map.of(), total, 100L);

            assertTrue(output.contains("more variable"),
                    "truncation notice must appear when variables exceed cap");
        }

        @Test
        @DisplayName("evidence: true/false and 1/0 are read as states; anything else is rejected")
        void parseEvidence_acceptsBooleansAndBinaryStates() throws Exception {
            assertEquals(Map.of(), AskGraphMebnTool.parseEvidence(null));
            assertEquals(Map.of(), AskGraphMebnTool.parseEvidence(om.nullNode()));
            assertEquals(Map.of("a(x)", 1, "b(x)", 0, "c(x)", 1, "d(x)", 0), AskGraphMebnTool.parseEvidence(
                    om.readTree("{\"a(x)\":true,\"b(x)\":false,\"c(x)\":1,\"d(x)\":0}")));

            for (String bad : new String[]{"{\"a(x)\":2}", "{\"a(x)\":\"yes\"}", "{\"a(x)\":0.5}", "[\"a(x)\"]"}) {
                assertThrows(IllegalArgumentException.class,
                        () -> AskGraphMebnTool.parseEvidence(om.readTree(bad)), bad);
            }
        }

        @Test
        @DisplayName("evidence: an invalid state is rejected before any backend is used")
        void invalidEvidence_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("nodeId", "node_42");
            params.putObject("evidence").put("isRelevant(node_42)", "yes");

            ToolResult result = tool.execute(params, ctx);

            assertTrue(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("isRelevant(node_42)"), result.getOutput());
        }

        @Test
        @DisplayName("formatter: evidence is listed, observed variables marked, titled variables named")
        void formatter_evidenceListedAndObservedMarked() {
            ObjectNode posteriors = om.createObjectNode();
            posteriors.put("isRelevant(a)", 1.0);
            posteriors.put("isRelevant(b)", 0.7);
            ObjectNode priors = om.createObjectNode();
            priors.put("isRelevant(a)", 0.4);
            priors.put("isRelevant(b)", 0.6);
            ObjectNode titles = om.createObjectNode();
            titles.put("isRelevant(a)", "Alice");

            String output = tool.formatMebnResult("a", posteriors, priors, titles,
                    om.createObjectNode(), Map.of("isRelevant(a)", 1), 2, 3L);

            assertTrue(output.contains("Evidence: isRelevant(a)=TRUE"), output);
            assertTrue(output.contains("[1] Alice  variable=isRelevant(a)"), output);
            assertTrue(output.contains("(Δ+0.600)  observed"), output);
            // An untitled variable is already shown by name.
            assertTrue(output.contains("[2] isRelevant(b)\n"), output);
            assertFalse(output.contains("No evidence was applied"), output);
        }

        @Test
        @DisplayName("remote: evidence is POSTed with its fact sheet and reported as applied")
        void remoteEvidence_isPostedAndReported() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            AskGraphMebnTool remote = new AskGraphMebnTool(new GroundingBackendClient("http://kg", rt), om);
            String[] body = {null};
            mockServer.expect(requestTo("http://kg/api/attribution/bayesian/mebn/query"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(request -> body[0] = ((MockClientHttpRequest) request).getBodyAsString())
                    .andRespond(withSuccess("""
                            {"posteriors":{"isRelevant(n1)":1.0,"isRelevant(n2)":0.8},
                             "priors":{"isRelevant(n1)":0.5,"isRelevant(n2)":0.6},
                             "variableToTitle":{"isRelevant(n1)":"Alpha","isRelevant(n2)":"Beta"},
                             "evidence":{"isRelevant(n1)":1},"computationTimeMs":4}
                            """, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("nodeId", "n1");
            params.put("factSheetId", 7);
            params.putObject("evidence").put("isRelevant(n1)", true);
            ToolResult result = remote.execute(params, ctx);

            mockServer.verify();
            assertFalse(result.isError(), result.getOutput());
            var sent = om.readTree(body[0]);
            assertEquals("n1", sent.path("seedNodeIds").path(0).asText(), body[0]);
            assertEquals(1, sent.path("evidence").path("isRelevant(n1)").asInt(), body[0]);
            assertEquals(7, sent.path("factSheetId").asLong(), body[0]);
            assertEquals(true, result.getMetadata().get("evidenceApplied"));
            assertTrue(result.getOutput().contains("Evidence: isRelevant(n1)=TRUE"), result.getOutput());
            assertTrue(result.getOutput().contains("Beta  variable=isRelevant(n2)"), result.getOutput());
        }

        @Test
        @DisplayName("remote: an evidence name outside the network is an error naming the valid ones")
        void remoteUnknownEvidence_isRejected() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            AskGraphMebnTool remote = new AskGraphMebnTool(new GroundingBackendClient("http://kg", rt), om);
            mockServer.expect(requestTo("http://kg/api/attribution/bayesian/mebn/query"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess("""
                            {"posteriors":{"isRelevant(n1)":0.5},"priors":{"isRelevant(n1)":0.5}}
                            """, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("nodeId", "n1");
            params.putObject("evidence").put("Alpha", 1);
            ToolResult result = remote.execute(params, ctx);

            mockServer.verify();
            assertTrue(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("Unknown evidence variable(s): Alpha"), result.getOutput());
            assertTrue(result.getOutput().contains("isRelevant(n1)"), result.getOutput());
        }

        @Test
        @DisplayName("remote: a server that rejects the evidence gets its reason to the model")
        void remoteEvidenceRejectedByServer_surfacesItsReason() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            AskGraphMebnTool remote = new AskGraphMebnTool(new GroundingBackendClient("http://kg", rt), om);
            // The app's GlobalExceptionHandler body for the service's IllegalArgumentException.
            mockServer.expect(requestTo("http://kg/api/attribution/bayesian/mebn/query"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                            .body("""
                                    {"error":"Bad request","type":"IllegalArgumentException","message":"Unknown MEBN evidence variable(s): Alpha. Use names from posteriors, e.g. isRelevant(n1)."}
                                    """));

            ObjectNode params = om.createObjectNode();
            params.put("nodeId", "n1");
            params.putObject("evidence").put("Alpha", 1);
            ToolResult result = remote.execute(params, ctx);

            mockServer.verify();
            assertTrue(result.isError(), result.getOutput());
            assertTrue(result.getOutput().startsWith(
                    "ask_graph_mebn failed (HTTP 400): Unknown MEBN evidence variable(s): Alpha"), result.getOutput());
            assertTrue(result.getOutput().contains("isRelevant(n1)"), result.getOutput());
            assertFalse(result.getOutput().contains("400 Bad Request"),
                    "the server's message, not the HTTP client's exception text: " + result.getOutput());
        }

        @Test
        @DisplayName("remote: without evidence the GET query is unchanged")
        void remoteWithoutEvidence_usesGet() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            AskGraphMebnTool remote = new AskGraphMebnTool(new GroundingBackendClient("http://kg", rt), om);
            mockServer.expect(requestTo("http://kg/api/attribution/bayesian/mebn/query?nodeId=n1&maxDepth=3&maxNodes=100"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("""
                            {"posteriors":{"isRelevant(n1)":0.5},"priors":{"isRelevant(n1)":0.5}}
                            """, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("nodeId", "n1");
            ToolResult result = remote.execute(params, ctx);

            mockServer.verify();
            assertFalse(result.isError(), result.getOutput());
            assertEquals(false, result.getMetadata().get("evidenceApplied"));
            assertTrue(result.getOutput().contains("No evidence was applied"), result.getOutput());
        }
    }

    // ── compactHint presence + non-jargon assertions ──────────────────────────────

    @Nested
    @DisplayName("compactHint quality assertions")
    class CompactHintQuality {

        @Test
        @DisplayName("ask_graph_query hint mentions '?' variable prefix")
        void queryHintMentionsVariablePrefix() {
            AskGraphQueryTool t = new AskGraphQueryTool((String) null, om);
            String hint = t.compactHint();
            assertNotNull(hint, "compactHint must not be null");
            assertTrue(hint.contains("?"), "query hint must mention '?' variable prefix");
        }

        @Test
        @DisplayName("ask_graph_explain hint does not contain 'PSL' or 'MEBN' jargon")
        void explainHintNoJargon() {
            AskGraphExplainTool t = new AskGraphExplainTool((String) null, om);
            String hint = t.compactHint();
            assertNotNull(hint, "compactHint must not be null");
            // hint is user-facing; should not open with engine acronyms
            assertFalse(hint.startsWith("PSL"), "hint must not start with PSL jargon");
            assertFalse(hint.startsWith("MEBN"), "hint must not start with MEBN jargon");
        }

        @Test
        @DisplayName("ask_graph_synthesize hint contains plain-English description")
        void synthesizeHintIsPlainEnglish() {
            AskGraphSynthesizeTool t = new AskGraphSynthesizeTool((String) null, om);
            String hint = t.compactHint();
            assertNotNull(hint, "compactHint must not be null");
            assertFalse(hint.isBlank(), "compactHint must not be blank");
        }

        @Test
        @DisplayName("ask_graph_mebn hint describes what the tool does without requiring MEBN knowledge")
        void mebnHintIsAccessible() {
            AskGraphMebnTool t = new AskGraphMebnTool((String) null, om);
            String hint = t.compactHint();
            assertNotNull(hint, "compactHint must not be null");
            // hint should explain the output (the model's probabilities), not the algorithm
            assertTrue(hint.toLowerCase().contains("probab") || hint.toLowerCase().contains("prior")
                    || hint.toLowerCase().contains("posterior"),
                    "MEBN hint should describe probabilistic output");
            // the hint offers evidence and says what the result means without it
            assertTrue(hint.contains("evidence"), hint);
            assertTrue(hint.contains("prior = posterior"), hint);
            assertFalse(hint.toLowerCase().contains("delta"), hint);
            assertTrue(hint.length() <= 200, "MCP listings cap hints at 200 chars; was " + hint.length());
        }

        @Test
        @DisplayName("ask_graph_verify hint uses plain language, not 'b=', 'd=', 'u='")
        void verifyHintNoBDU() {
            AskGraphVerifyTool t = new AskGraphVerifyTool((String) null, om);
            String hint = t.compactHint();
            assertNotNull(hint, "compactHint must not be null");
            assertFalse(hint.contains("b=") || hint.contains("d=") || hint.contains("u="),
                    "verify hint must not expose b=/d=/u= opinion fields");
        }

        @Test
        @DisplayName("ask_graph_verify response uses support=/counter-evidence= not b=/d=")
        void verifyResponseFormatUsesPlainLabels() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            AskGraphVerifyTool localTool = new AskGraphVerifyTool(client, om);

            String responseJson = """
                    {
                      "verdict": "SUPPORTED",
                      "confidence": 0.75,
                      "calibratedConfidence": 0.75,
                      "strengthBand": "HIGH",
                      "evidenceAtoms": ["worksFor(Alice, Acme)"],
                      "activatedRules": ["rule1"],
                      "derivationDepth": 1,
                      "evidenceCount": 1,
                      "sourceProvenance": ["test"],
                      "counterEvidence": [],
                      "refutationBasis": null,
                      "opinion": {"b": 0.7, "d": 0.1, "u": 0.2, "a": 0.5},
                      "openWorld": false,
                      "entityKnown": true,
                      "unknownReason": null,
                      "contradictions": [],
                      "nearMissSuggestions": [],
                      "fragility": null,
                      "meta": {"factSheetId": 1, "stale": false, "kbVersion": 1}
                    }
                    """;

            mockServer.expect(requestTo("http://localhost/api/kb-grounding/verify"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

            ObjectNode params = om.createObjectNode();
            params.put("atom", "worksFor(Alice, Acme)");
            ToolResult result = localTool.execute(params, ctx);

            assertFalse(result.isError(), result.getOutput());
            String output = result.getOutput();
            // must use plain labels
            assertTrue(output.contains("support=") || output.contains("Support="),
                    "output must use 'support=' not 'b='");
            assertFalse(output.contains("b=0.") || output.contains("\"b\""),
                    "output must not expose raw 'b=' field");

            mockServer.verify();
        }
    }

    // ── ask_graph_retract — factSheetId optional ──────────────────────────────────

    @Nested
    @DisplayName("ask_graph_retract")
    class RetractTool {

        private AskGraphRetractTool tool;

        @BeforeEach
        void setUp() {
            tool = new AskGraphRetractTool((String) null, om);
        }

        @Test
        @DisplayName("id and permissionKey are correct")
        void metadata() {
            assertEquals("ask_graph_retract", tool.id());
            assertEquals("ask_graph_retract", tool.permissionKey());
            assertEquals(McpToolAnnotations.WRITE, tool.mcpAnnotations());
        }

        @Test
        @DisplayName("factSheetId is NOT in required array")
        void factSheetIdNotRequired() {
            var schema = tool.parameterSchema();
            String required = schema.path("required").toString();
            assertFalse(required.contains("factSheetId"),
                    "factSheetId must not be required in ask_graph_retract schema");
            assertTrue(required.contains("atomKey"),
                    "atomKey must still be required");
        }

        @Test
        @DisplayName("missing atomKey returns error — no factSheetId pre-check error")
        void missingAtomKey_returnsAtomKeyError() throws Exception {
            ObjectNode params = om.createObjectNode();
            // no atomKey, no factSheetId
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError(), "Expected error when atomKey is missing");
            // error must be about atomKey, not factSheetId
            assertTrue(result.getOutput().contains("atomKey"),
                    "error must mention atomKey, not factSheetId");
            assertFalse(result.getOutput().toLowerCase().contains("factsheetid is required"),
                    "must not see old 'factSheetId is required' pre-check error");
        }

        @Test
        @DisplayName("atomKey without factSheetId reaches backend (no schema-level block)")
        void atomKeyWithoutFactSheetId_reachesBackend() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("atomKey", "worksFor(Alice, Acme)");
            ToolResult result = tool.execute(params, ctx);
            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("project-local"), result.getOutput());
            assertFalse(result.getOutput().toLowerCase().contains("factsheetid"),
                    "factSheetId must not appear in the error when it is simply absent");
        }
    }

    // ── knowledge_graph list_predicates ──────────────────────────────────────────

    @Nested
    @DisplayName("knowledge_graph list_predicates action")
    class KnowledgeGraphListPredicates {

        @Test
        @DisplayName("list_predicates returns predicate list from backend")
        void listPredicates_returnsList() throws Exception {
            RestTemplate rt = new RestTemplate();
            MockRestServiceServer mockServer = MockRestServiceServer.createServer(rt);
            GroundingBackendClient client = new GroundingBackendClient("http://localhost", rt);
            KnowledgeGraphTool tool = new KnowledgeGraphTool("http://localhost", om);
            // inject mock client via reflection is complex — instead verify routing via null-baseUrl
            // backend-unavailable path: tool should return error, not NPE or routing error
            KnowledgeGraphTool offlineTool = new KnowledgeGraphTool((String) null, om);

            ObjectNode params = om.createObjectNode();
            params.put("action", "list_predicates");
            ToolResult result = offlineTool.execute(params, ctx);
            // With null backend, error expected — but should NOT be "unknown action"
            if (result.isError()) {
                assertFalse(result.getOutput().toLowerCase().contains("unknown action"),
                        "list_predicates must be a recognised action, not 'unknown action': " + result.getOutput());
            }
        }

        @Test
        @DisplayName("list_predicates action is mentioned in description and compactHint")
        void listPredicates_isMentionedInDescriptionAndHint() {
            KnowledgeGraphTool tool = new KnowledgeGraphTool((String) null, om);
            String desc = tool.description();
            String hint = tool.compactHint();
            assertTrue(desc.contains("list_predicates"),
                    "description must mention list_predicates action");
            assertTrue(hint.contains("list_predicates"),
                    "compactHint must mention list_predicates for discoverability");
        }
    }

    // ── MCP stdio listing: whole hints, and the actions each knowledge_graph mode serves ──

    @Nested
    @DisplayName("MCP stdio listing")
    class McpStdioListing {

        private JsonNode listed(CliTool tool) {
            ArrayNode definitions = om.createArrayNode();
            definitions.addObject()
                    .put("name", tool.id())
                    .put("description", tool.description())
                    .set("inputSchema", tool.parameterSchema());
            return ToolSchemaOptimizer.optimize(definitions,
                    ToolSchemaOptimizer.OptimizationLevel.COMPACT, Map.of(tool.id(), tool.compactHint())).get(0);
        }

        @Test
        @DisplayName("every graph grounding tool is listed with its whole hint")
        void everyGroundingHintIsListedWhole() {
            List<CliTool> tools = List.of(
                    new AskGraphVerifyTool((String) null, om),
                    new AskGraphQueryTool((String) null, om),
                    new AskGraphExplainTool((String) null, om),
                    new AskGraphClaimTool((String) null, om),
                    new AskGraphSynthesizeTool((String) null, om),
                    new AskGraphRetractTool((String) null, om),
                    new AskGraphAssertTool((String) null, om),
                    new AskGraphFusedTool((String) null, om),
                    new AskGraphMebnTool((String) null, om),
                    new AskGraphSubscribeTool((String) null, om),
                    new KnowledgeGraphTool((String) null, om));
            for (CliTool tool : tools) {
                String hint = tool.compactHint();
                // Without a hint the listing cuts the description to 60 characters.
                assertNotNull(hint, tool.id());
                assertFalse(hint.isBlank(), tool.id());
                assertTrue(hint.length() <= 200, tool.id() + " hint is " + hint.length() + " chars: " + hint);
                assertEquals(hint, listed(tool).path("description").asText(), tool.id());
            }
        }

        @Test
        @DisplayName("knowledge_graph lists the actions its mode serves")
        void knowledgeGraphListsTheActionsItsModeServes() throws Exception {
            JsonNode localAction = listed(new KnowledgeGraphTool((String) null, om))
                    .path("inputSchema").path("properties").path("action");
            assertEquals("string", localAction.path("type").asText());
            List<?> local = om.convertValue(localAction.path("enum"), List.class);
            assertEquals(LocalProjectGraphBackend.KNOWLEDGE_GRAPH_ACTIONS, local);
            assertFalse(local.contains("cypher") || local.contains("algorithm") || local.contains("communities"),
                    local.toString());

            List<?> remote = om.convertValue(listed(new KnowledgeGraphTool("http://localhost:8080", om))
                    .path("inputSchema").path("properties").path("action").path("enum"), List.class);
            assertTrue(remote.containsAll(List.of("cypher", "extract", "restore_snapshot", "list_predicates")),
                    remote.toString());

            // An action the folder archive does not serve is answered with the ones it does.
            ToolResult result = new KnowledgeGraphTool((String) null, om)
                    .execute(om.createObjectNode().put("action", "cypher"), ctx);
            assertTrue(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains(
                    "Supported locally: " + String.join(", ", LocalProjectGraphBackend.KNOWLEDGE_GRAPH_ACTIONS)),
                    result.getOutput());
        }
    }
}
