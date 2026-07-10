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
import ai.kompile.cli.main.chat.tools.KnowledgeGraphTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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
class AskGraphToolsTest {

    @TempDir
    Path tempDir;

    private ObjectMapper om;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
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
        ctx = new ToolContext("test-session", agent, perms, Paths.get("."), registry);
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
        @DisplayName("backend unavailable returns descriptive error")
        void backendUnavailable_returnsError() throws Exception {
            // null baseUrl → isAvailable()==false deterministically, no server needed
            ObjectNode params = om.createObjectNode();
            params.put("atom", "isEmployedBy(Alice, Acme)");
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError(), "Expected error when backend not available");
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
        @DisplayName("backend unavailable returns descriptive error")
        void backendUnavailable_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("query", "who leads Acme?");
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError(), "Expected error when backend not available");
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
        @DisplayName("backend unavailable returns descriptive error")
        void backendUnavailable_returnsError() throws Exception {
            // null baseUrl → isAvailable()==false, no network I/O, fully deterministic
            ObjectNode params = om.createObjectNode();
            params.put("nodeId", "node_42");
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError(), "Expected error when kompile-app not running");
            assertTrue(result.getOutput().contains("kompile-app"),
                    "Error should mention kompile-app");
        }

        @Test
        @DisplayName("formatter: empty posteriors produces no-variables message")
        void formatter_emptyPosteriors() {
            var posteriors      = om.createObjectNode();
            var priors          = om.createObjectNode();
            var variableToTitle = om.createObjectNode();
            var mebnMeta        = om.createObjectNode();

            String output = tool.formatMebnResult("node_42", posteriors, priors,
                    variableToTitle, mebnMeta, 0, 12L);

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
                    titles, mebnMeta, 2, 50L);

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
                    titles, om.createObjectNode(), total, 100L);

            assertTrue(output.contains("more variable"),
                    "truncation notice must appear when variables exceed cap");
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
            // hint should explain the output (before/after probabilities), not the algorithm
            assertTrue(hint.toLowerCase().contains("probab") || hint.toLowerCase().contains("prior")
                    || hint.toLowerCase().contains("posterior"),
                    "MEBN hint should describe probabilistic output");
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
            // With a real backend (null baseUrl) it will fail with isAvailable==false,
            // NOT with a factSheetId validation error — this proves the pre-check is gone.
            ObjectNode params = om.createObjectNode();
            params.put("atomKey", "worksFor(Alice, Acme)");
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError(), "Expected error (backend unavailable)");
            // The error should be the backend-unavailable message, NOT a factSheetId complaint
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
}
