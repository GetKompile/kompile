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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GraphReasonTool} — the unified, algorithm-agnostic MCP graph-reasoning tool.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Tool metadata (id, permissionKey, READ_ONLY annotation)</li>
 *   <li>Parameter schema: {@code target} required, NO {@code mode} param</li>
 *   <li>Backend-unavailable error path</li>
 *   <li>Formatter: plain-English confidence tiers</li>
 *   <li>Formatter: output contains summary + evidence and is free of algorithm jargon</li>
 *   <li>De-jargoned sibling tools: ask_graph_mebn and ask_graph_explain output checks</li>
 * </ul>
 */
@DisplayName("GraphReasonTool — unified algorithm-agnostic reasoning")
class GraphReasonToolTest {

    private ObjectMapper om;
    private ToolContext ctx;

    /** Known jargon strings that must NOT appear in any user-facing output. */
    private static final List<String> JARGON_STRINGS = List.of(
            "MEBN", "PSL", "SSBN", "MFrag", "inferenceMode"
    );

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("coder")
                .enabledTools(Set.of("*"))
                .build();
        PermissionService perms = new PermissionService();
        perms.setUserOverride("graph_reason", PermissionService.PermissionLevel.ALLOW);
        perms.setUserOverride("ask_graph_mebn", PermissionService.PermissionLevel.ALLOW);
        perms.setUserOverride("ask_graph_explain", PermissionService.PermissionLevel.ALLOW);
        ToolRegistry registry = new ToolRegistry(om);
        ctx = new ToolContext("test-session", agent, perms, Paths.get("."), registry);
    }

    // ── Metadata ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tool metadata")
    class Metadata {

        private GraphReasonTool tool;

        @BeforeEach
        void setUp() {
            tool = new GraphReasonTool(null, om);
        }

        @Test
        @DisplayName("id is 'graph_reason' — no algorithm name")
        void idIsAgnostic() {
            assertEquals("graph_reason", tool.id());
            // Must not contain any algorithm name
            assertFalse(tool.id().contains("mebn"), "id must not contain 'mebn'");
            assertFalse(tool.id().contains("psl"),  "id must not contain 'psl'");
            assertFalse(tool.id().contains("ssbn"), "id must not contain 'ssbn'");
        }

        @Test
        @DisplayName("permissionKey matches id")
        void permissionKeyMatchesId() {
            assertEquals("graph_reason", tool.permissionKey());
        }

        @Test
        @DisplayName("annotation is READ_ONLY")
        void annotationsReadOnly() {
            assertEquals(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
        }

        @Test
        @DisplayName("description contains no algorithm jargon")
        void descriptionNoJargon() {
            String desc = tool.description();
            for (String jargon : JARGON_STRINGS) {
                assertFalse(desc.contains(jargon),
                        "description must not contain '" + jargon + "' but was: " + desc);
            }
        }
    }

    // ── Parameter schema ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Parameter schema")
    class Schema {

        private GraphReasonTool tool;

        @BeforeEach
        void setUp() {
            tool = new GraphReasonTool(null, om);
        }

        @Test
        @DisplayName("'target' is the only required parameter")
        void targetRequired() {
            var schema = tool.parameterSchema();
            String required = schema.path("required").toString();
            assertTrue(required.contains("target"),
                    "'target' must appear in required array, but required was: " + required);
        }

        @Test
        @DisplayName("'target' property exists with a plain-English description")
        void targetPropertyPresent() {
            var props = tool.parameterSchema().path("properties");
            assertFalse(props.path("target").isMissingNode(),
                    "'target' must be in properties");
        }

        @Test
        @DisplayName("optional 'factSheetId' and 'depth' properties present")
        void optionalPropertiesPresent() {
            var props = tool.parameterSchema().path("properties");
            assertFalse(props.path("factSheetId").isMissingNode(), "'factSheetId' must be in properties");
            assertFalse(props.path("depth").isMissingNode(), "'depth' must be in properties");
        }

        @Test
        @DisplayName("NO 'mode' parameter — LLM never picks an algorithm")
        void modeParamAbsent() {
            var props = tool.parameterSchema().path("properties");
            assertTrue(props.path("mode").isMissingNode(),
                    "'mode' must NOT appear in properties — LLM should never need to pick an algorithm");
        }

        @Test
        @DisplayName("required array does NOT list 'mode'")
        void modeNotRequired() {
            String required = tool.parameterSchema().path("required").toString();
            assertFalse(required.contains("mode"),
                    "'mode' must not appear in the required array");
        }
    }

    // ── Backend-unavailable error path ────────────────────────────────────────────

    @Nested
    @DisplayName("Backend unavailable")
    class BackendUnavailable {

        private GraphReasonTool tool;

        @BeforeEach
        void setUp() {
            // null baseUrl → backend will not be reachable
            tool = new GraphReasonTool(null, om);
        }

        @Test
        @DisplayName("missing 'target' returns error before backend check")
        void missingTarget_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode(); // no target
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError(), "Expected error when target is missing");
            assertTrue(result.getOutput().contains("target"),
                    "Error should mention 'target'");
        }

        @Test
        @DisplayName("blank 'target' returns error")
        void blankTarget_returnsError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("target", "   ");
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError(), "Expected error for blank target");
        }

        @Test
        @DisplayName("kompile-app not running returns descriptive error")
        void backendDown_returnsDescriptiveError() throws Exception {
            ObjectNode params = om.createObjectNode();
            params.put("target", "Alice Smith");
            ToolResult result = tool.execute(params, ctx);
            assertTrue(result.isError(), "Expected error when kompile-app not running");
            assertTrue(result.getOutput().contains("kompile-app"),
                    "Error should mention kompile-app; was: " + result.getOutput());
        }
    }

    // ── Formatter ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Output formatter")
    class Formatter {

        private GraphReasonTool tool;

        @BeforeEach
        void setUp() {
            tool = new GraphReasonTool(null, om);
        }

        @Test
        @DisplayName("confidence tiers: well-supported at 0.90")
        void confidenceWellSupported() {
            assertEquals("well-supported", GraphReasonTool.confidenceToWords(0.90));
        }

        @Test
        @DisplayName("confidence tiers: likely at 0.70")
        void confidenceLikely() {
            assertEquals("likely", GraphReasonTool.confidenceToWords(0.70));
        }

        @Test
        @DisplayName("confidence tiers: uncertain at 0.50")
        void confidenceUncertain() {
            assertEquals("uncertain", GraphReasonTool.confidenceToWords(0.50));
        }

        @Test
        @DisplayName("confidence tiers: weakly supported at 0.20")
        void confidenceWeaklySupported() {
            assertEquals("weakly supported", GraphReasonTool.confidenceToWords(0.20));
        }

        @Test
        @DisplayName("confidence tiers: unsupported at 0.05")
        void confidenceUnsupported() {
            assertEquals("unsupported", GraphReasonTool.confidenceToWords(0.05));
        }

        @Test
        @DisplayName("output contains plain-English summary when present")
        void outputContainsSummary() {
            ArrayNode evidence = om.createArrayNode();
            ArrayNode rules    = om.createArrayNode();
            String summary = "Alice Smith is employed by Acme Corp as of Q1 2025.";

            String output = tool.formatReasonResult(
                    "isEmployedBy(Alice, Acme)", "SUPPORTED", 0.92,
                    summary, null, evidence, rules);

            assertTrue(output.contains(summary),
                    "output must contain the natural-language summary");
        }

        @Test
        @DisplayName("output contains evidence bullets when evidence list is non-empty")
        void outputContainsEvidence() {
            ArrayNode evidence = om.createArrayNode();
            evidence.add("Alice joined Acme on 2022-03-01");
            evidence.add("Employment contract signed and filed");
            ArrayNode rules = om.createArrayNode();

            String output = tool.formatReasonResult(
                    "isEmployedBy(Alice, Acme)", "SUPPORTED", 0.88,
                    "Alice is employed by Acme.", null, evidence, rules);

            assertTrue(output.contains("Supporting evidence"),
                    "output must contain an 'Supporting evidence' section");
            assertTrue(output.contains("Alice joined Acme"),
                    "output must contain the first evidence item");
            assertTrue(output.contains("Employment contract"),
                    "output must contain the second evidence item");
        }

        @Test
        @DisplayName("output contains reasoning steps when rules list is non-empty")
        void outputContainsReasoningSteps() {
            ArrayNode evidence = om.createArrayNode();
            ArrayNode rules    = om.createArrayNode();
            rules.add("Employment → isEmployedBy");
            rules.add("Contract filing confirms employment");

            String output = tool.formatReasonResult(
                    "isEmployedBy(Alice, Acme)", "SUPPORTED", 0.75,
                    "Alice is supported by two rules.", null, evidence, rules);

            assertTrue(output.contains("Reasoning steps"),
                    "output must contain 'Reasoning steps' section");
            assertTrue(output.contains("Employment →"),
                    "output must contain the first rule step");
        }

        @Test
        @DisplayName("output confidence rendered as percent")
        void outputConfidencePercent() {
            ArrayNode evidence = om.createArrayNode();
            ArrayNode rules    = om.createArrayNode();

            String output = tool.formatReasonResult(
                    "SomeEntity", "", 0.73,
                    "A summary.", null, evidence, rules);

            // Should contain both word and percent
            assertTrue(output.contains("likely"), "should contain word for 73%");
            assertTrue(output.contains("73%"), "should contain numeric percent");
        }

        @Test
        @DisplayName("output contains NO algorithm jargon (MEBN/PSL/SSBN/MFrag/inferenceMode)")
        void outputNoJargon() {
            ArrayNode evidence = om.createArrayNode();
            evidence.add("Human-readable evidence from the knowledge base");
            ArrayNode rules = om.createArrayNode();
            rules.add("If entity has contract then isEmployedBy holds");

            String output = tool.formatReasonResult(
                    "isEmployedBy(Alice, Acme)", "SUPPORTED", 0.88,
                    "Alice is supported by two rules.", null, evidence, rules);

            for (String jargon : JARGON_STRINGS) {
                assertFalse(output.contains(jargon),
                        "output must NOT contain jargon '" + jargon + "'");
            }
        }

        @Test
        @DisplayName("derivation JSON included when present")
        void derivationIncludedWhenPresent() {
            ArrayNode evidence = om.createArrayNode();
            ArrayNode rules    = om.createArrayNode();
            String derivJson   = "{\"atom\":\"isEmployedBy(Alice,Acme)\",\"confidence\":0.9}";

            String output = tool.formatReasonResult(
                    "isEmployedBy(Alice, Acme)", "SUPPORTED", 0.90,
                    "A summary.", derivJson, evidence, rules);

            assertTrue(output.contains("Detailed trace"),
                    "derivation section header must appear when derivation JSON is present");
            assertTrue(output.contains(derivJson),
                    "derivation JSON must be included verbatim");
        }

        @Test
        @DisplayName("verdict rendered in headline when non-blank")
        void verdictRenderedInHeadline() {
            ArrayNode evidence = om.createArrayNode();
            ArrayNode rules    = om.createArrayNode();

            String output = tool.formatReasonResult(
                    "isEmployedBy(Alice, Acme)", "SUPPORTED", 0.88,
                    "A summary.", null, evidence, rules);

            assertTrue(output.contains("SUPPORTED"),
                    "verdict must appear in the output headline");
        }
    }

    // ── De-jargoned sibling tools ─────────────────────────────────────────────────

    @Nested
    @DisplayName("De-jargoned sibling tools")
    class DeJargoningChecks {

        @Test
        @DisplayName("ask_graph_mebn description contains no 'MEBN' or 'MFrag'")
        void mebnDescriptionNoJargon() {
            AskGraphMebnTool mebn = new AskGraphMebnTool(null, om);
            String desc = mebn.description();
            assertFalse(desc.contains("MEBN"),
                    "ask_graph_mebn description must not contain 'MEBN' — found: " + desc);
            assertFalse(desc.contains("MFrag"),
                    "ask_graph_mebn description must not contain 'MFrag' — found: " + desc);
        }

        @Test
        @DisplayName("ask_graph_mebn formatted output contains no 'MEBN Inference' or 'mfrag='")
        void mebnFormatterNoJargon() {
            AskGraphMebnTool mebn = new AskGraphMebnTool(null, om);
            ObjectNode posteriors = om.createObjectNode();
            posteriors.put("var_x", 0.8);
            ObjectNode priors = om.createObjectNode();
            priors.put("var_x", 0.5);
            ObjectNode titles = om.createObjectNode();
            titles.put("var_x", "Revenue Risk");
            ObjectNode meta = om.createObjectNode();
            ObjectNode metaX = om.createObjectNode();
            metaX.put("mfragName", "RiskMFrag");
            metaX.put("entityType", "METRIC");
            metaX.put("nodeRole", "RESIDENT");
            meta.set("var_x", metaX);

            String output = mebn.formatMebnResult("node_1", posteriors, priors, titles, meta, 1, 10L);

            assertFalse(output.contains("MEBN"),
                    "formatted output must not contain 'MEBN' — found: " + output);
            assertFalse(output.contains("mfrag="),
                    "formatted output must not contain 'mfrag=' — found: " + output);
            assertTrue(output.contains("group="),
                    "formatted output should use 'group=' instead of 'mfrag=' — found: " + output);
        }

        @Test
        @DisplayName("ask_graph_explain translateInferenceMode never returns raw MEBN/PSL strings")
        void explainModeTranslation() {
            assertNotEquals("MEBN",  AskGraphExplainTool.translateInferenceMode("MEBN"));
            assertNotEquals("PSL",   AskGraphExplainTool.translateInferenceMode("PSL"));
            assertNotEquals("GROUNDING", AskGraphExplainTool.translateInferenceMode("GROUNDING"));
            assertNotEquals("HYBRID",    AskGraphExplainTool.translateInferenceMode("HYBRID"));
            assertNotEquals("CAUSAL",    AskGraphExplainTool.translateInferenceMode("CAUSAL"));
        }

        @Test
        @DisplayName("ask_graph_explain description does not expose 'atom' as jargon")
        void explainDescriptionNoAtomJargon() {
            AskGraphExplainTool explain = new AskGraphExplainTool(null, om);
            String desc = explain.description();
            // "supporting atoms" was the specific jargon phrase — now replaced with "supporting facts"
            assertFalse(desc.contains("supporting atoms"),
                    "description must not say 'supporting atoms' — found: " + desc);
        }
    }
}
