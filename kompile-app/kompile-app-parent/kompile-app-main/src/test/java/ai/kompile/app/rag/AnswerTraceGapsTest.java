/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.rag;

import ai.kompile.app.services.agent.ReasoningTrailMapper;
import ai.kompile.graph.reasoning.domain.AttributionChain;
import ai.kompile.graph.reasoning.domain.AttributionResult;
import ai.kompile.graph.reasoning.domain.BayesianInferenceResult;
import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.explain.ReasoningTrail;
import ai.kompile.event.attribution.service.BayesianNetworkService;
import ai.kompile.event.attribution.service.EventAttributionService;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.reasoning.TraceHumanizer;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests covering the 5 answer-trace gaps:
 *
 * <ul>
 *   <li><b>GAP 1</b> – binding humanization ({@code displayVariables} populated server-side)</li>
 *   <li><b>GAP 2</b> – synthesize answer title resolution (answer = human title, entityId = raw key)</li>
 *   <li><b>GAP 3</b> – causal/probabilistic fallback labels (no raw ids / paths in output)</li>
 *   <li><b>GAP 4b</b> – {@link TraceHumanizer#cleanLabel} static fallback</li>
 *   <li><b>GAP 5</b> – trail-in-prompt: compact "Reasoning trace:" section appended to context</li>
 * </ul>
 */
@DisplayName("Answer-trace gaps (1-5)")
class AnswerTraceGapsTest {

    // ── UUID / path leak patterns — these must never appear in humanized output ────
    private static final Pattern UUID_PAT =
            Pattern.compile("\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern PATH_PAT = Pattern.compile("[a-zA-Z0-9_\\-]+/[a-zA-Z0-9_\\-/]+");

    // ── GAP 4b: TraceHumanizer.cleanLabel static fallback ────────────────────────

    @Test
    @DisplayName("GAP 4b: cleanLabel strips underscores and title-cases")
    void cleanLabelStripsUnderscores() {
        assertEquals("Country usa", TraceHumanizer.cleanLabel("country_usa"));
        assertEquals("My entity", TraceHumanizer.cleanLabel("my_entity"));
    }

    @Test
    @DisplayName("GAP 4b: cleanLabel strips atom wrapper predicate(arg)")
    void cleanLabelStripsAtomWrapper() {
        String result = TraceHumanizer.cleanLabel("entity(country_usa)");
        // Should extract arg "country_usa" → "Country usa"
        assertEquals("Country usa", result);
    }

    @Test
    @DisplayName("GAP 4b: cleanLabel takes last path segment, never emits '/'")
    void cleanLabelStripsPathSegments() {
        String result = TraceHumanizer.cleanLabel("/home/user/project/data/file.txt");
        assertFalse(result.contains("/"), "result must not contain '/': " + result);
        assertFalse(result.isBlank(), "result must not be blank");
    }

    @Test
    @DisplayName("GAP 4b: cleanLabel on a raw UUID returns placeholder, not the UUID")
    void cleanLabelReplacesUuid() {
        String uuid = "3f6c8e2d-4a1b-4e7e-9f3c-1234567890ab";
        String result = TraceHumanizer.cleanLabel(uuid);
        assertFalse(UUID_PAT.matcher(result).find(), "UUID must not appear in label: " + result);
    }

    @Test
    @DisplayName("GAP 4b: cleanLabel strips surrounding quotes")
    void cleanLabelStripsQuotes() {
        assertEquals("My entity", TraceHumanizer.cleanLabel("\"my_entity\""));
        assertEquals("My entity", TraceHumanizer.cleanLabel("'my_entity'"));
    }

    @Test
    @DisplayName("GAP 4b: null/blank input returns as-is")
    void cleanLabelNullBlankPassthrough() {
        assertNull(TraceHumanizer.cleanLabel(null));
        assertEquals("", TraceHumanizer.cleanLabel(""));
        assertEquals("   ", TraceHumanizer.cleanLabel("   ")); // blank returns as-is per contract
    }

    // ── GAP 3: causal fallback title + GAP 5: trail-in-prompt ─────────────────────

    @Test
    @DisplayName("GAP 3: causal trail.targetId uses resolved node title, not raw id")
    void causalFallbackTitleResolvesViaGraphService() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        EventAttributionService attribution = mock(EventAttributionService.class);

        GraphNode seedNode = mock(GraphNode.class);
        when(seedNode.getNodeId()).thenReturn("node-abc");
        when(graph.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of(seedNode));

        // Attribution returns result WITH a target title
        AttributionResult result = AttributionResult.builder()
                .targetTitle("Revenue Drop")
                .synthesizedExplanation("Caused by market contraction.")
                .chains(List.of(AttributionChain.builder()
                        .rootCauseTitle("Market Contraction")
                        .overallConfidence(0.75)
                        .build()))
                .build();
        when(attribution.explain(any())).thenReturn(result);

        GraphReasoningRetriever retriever = new GraphReasoningRetriever(graph, attribution, null);
        GraphReasoningRetriever.ReasoningRetrieval r =
                retriever.retrieveWithTrail("Why did revenue drop?", "CAUSAL", 5);

        assertNotNull(r);
        // targetId should be "Revenue Drop", not "node-abc"
        assertEquals("Revenue Drop", r.trail().targetId());
        assertFalse(UUID_PAT.matcher(r.trail().targetId()).find(),
                "targetId must not be a raw UUID: " + r.trail().targetId());
    }

    @Test
    @DisplayName("GAP 3: causal trail.targetId uses cleanLabel fallback when title null and no graph hit")
    void causalFallbackTitleUsesCleanLabelWhenNoTitle() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        EventAttributionService attribution = mock(EventAttributionService.class);

        GraphNode seedNode = mock(GraphNode.class);
        when(seedNode.getNodeId()).thenReturn("revenue_event");
        when(graph.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of(seedNode));
        when(graph.getNode("revenue_event")).thenReturn(Optional.empty());
        when(graph.getNodeByExternalId(eq("revenue_event"), any())).thenReturn(Optional.empty());

        // Attribution returns result WITHOUT a target title
        AttributionResult result = AttributionResult.builder()
                .targetTitle(null)
                .chains(List.of(AttributionChain.builder()
                        .rootCauseTitle("Bad Data")
                        .overallConfidence(0.60)
                        .build()))
                .build();
        when(attribution.explain(any())).thenReturn(result);

        GraphReasoningRetriever retriever = new GraphReasoningRetriever(graph, attribution, null);
        GraphReasoningRetriever.ReasoningRetrieval r =
                retriever.retrieveWithTrail("Why?", "CAUSAL", 5);

        assertNotNull(r);
        String targetId = r.trail().targetId();
        // cleanLabel("revenue_event") → "Revenue event"
        assertEquals("Revenue event", targetId);
        assertFalse(PATH_PAT.matcher(targetId).find(), "targetId must not be path-like: " + targetId);
    }

    @Test
    @DisplayName("GAP 3: probabilistic trail.targetId is resolved from seed, not raw node id")
    void probabilisticSeedTitleResolved() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        BayesianNetworkService bayesian = mock(BayesianNetworkService.class);

        GraphNode seedNode = mock(GraphNode.class);
        when(seedNode.getNodeId()).thenReturn("customer_churn");
        when(graph.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of(seedNode));

        // graph.getNode() resolves the seed to a real title
        GraphNode resolvedNode = new GraphNode();
        resolvedNode.setTitle("Customer Churn");
        when(graph.getNode("customer_churn")).thenReturn(Optional.of(resolvedNode));

        BayesianInferenceResult inference = BayesianInferenceResult.builder()
                .posteriors(Map.of("v1", 0.75))
                .variableToTitle(Map.of("v1", "Price increase"))
                .build();
        when(bayesian.queryMebnFromKg(any(), any(), anyInt(), anyInt())).thenReturn(inference);

        GraphReasoningRetriever retriever = new GraphReasoningRetriever(graph, null, bayesian);
        GraphReasoningRetriever.ReasoningRetrieval r =
                retriever.retrieveWithTrail("Most likely cause?", "PROBABILISTIC", 5);

        assertNotNull(r);
        assertEquals("Customer Churn", r.trail().targetId());
        assertFalse(UUID_PAT.matcher(r.trail().targetId()).find());
    }

    @Test
    @DisplayName("GAP 3: probabilistic fallback variable title uses cleanLabel when missing from map")
    void probabilisticMissingVariableTitleUsesCleanLabel() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        BayesianNetworkService bayesian = mock(BayesianNetworkService.class);

        GraphNode seedNode = mock(GraphNode.class);
        when(seedNode.getNodeId()).thenReturn("evt-99");
        when(graph.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of(seedNode));
        when(graph.getNode(anyString())).thenReturn(Optional.empty());
        when(graph.getNodeByExternalId(anyString(), any())).thenReturn(Optional.empty());

        // "raw_var" is NOT in variableToTitle → should fall back to cleanLabel
        BayesianInferenceResult inference = BayesianInferenceResult.builder()
                .posteriors(Map.of("raw_var", 0.9))
                .variableToTitle(Map.of())   // empty map — no title resolution
                .build();
        when(bayesian.queryMebnFromKg(any(), any(), anyInt(), anyInt())).thenReturn(inference);

        GraphReasoningRetriever retriever = new GraphReasoningRetriever(graph, null, bayesian);
        GraphReasoningRetriever.ReasoningRetrieval r =
                retriever.retrieveWithTrail("What is most likely?", "PROBABILISTIC", 5);

        assertNotNull(r);
        String context = r.context();
        // Evidence must contain "Raw var" (cleanLabel of "raw_var"), never the raw id
        assertTrue(context.contains("Raw var"), "context should have humanized label. Was:\n" + context);
        assertFalse(context.contains("raw_var"), "raw key must not appear in context: " + context);
    }

    @Test
    @DisplayName("GAP 5: trail-in-prompt appends 'Reasoning trace:' section when enabled (default)")
    void trailInPromptAppendsTraceSection() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        EventAttributionService attribution = mock(EventAttributionService.class);

        GraphNode seedNode = mock(GraphNode.class);
        when(seedNode.getNodeId()).thenReturn("evt-x");
        when(graph.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of(seedNode));
        when(graph.getNode(anyString())).thenReturn(Optional.empty());
        when(graph.getNodeByExternalId(anyString(), any())).thenReturn(Optional.empty());

        AttributionResult result = AttributionResult.builder()
                .targetTitle("Crash")
                .synthesizedExplanation("Server crashed due to OOM.")
                .chains(List.of(AttributionChain.builder()
                        .rootCauseTitle("Memory leak")
                        .overallConfidence(0.88)
                        .build()))
                .build();
        when(attribution.explain(any())).thenReturn(result);

        // Use default KbConfig (reasoningTrailInPromptEnabled = true)
        KbConfigManager cfgManager = mock(KbConfigManager.class);
        when(cfgManager.current()).thenReturn(KbConfig.defaults());

        GraphReasoningRetriever retriever = new GraphReasoningRetriever(graph, attribution, null, cfgManager);
        GraphReasoningRetriever.ReasoningRetrieval r =
                retriever.retrieveWithTrail("Why did it crash?", "CAUSAL", 5);

        assertNotNull(r);
        String context = r.context();
        assertTrue(context.contains("Reasoning trace (CAUSAL):"),
                "context should contain 'Reasoning trace (CAUSAL):' section. Was:\n" + context);
        assertTrue(context.contains("Evidence:"),
                "context should contain 'Evidence:' subsection. Was:\n" + context);
        assertTrue(context.contains("target=Crash; confidence=0.880"),
                "context should contain header confidence. Was:\n" + context);
    }

    @Test
    @DisplayName("GAP 5: trail-in-prompt is suppressed when kbReasoningTrailInPromptEnabled=false")
    void trailInPromptSuppressedWhenDisabled() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        EventAttributionService attribution = mock(EventAttributionService.class);

        GraphNode seedNode = mock(GraphNode.class);
        when(seedNode.getNodeId()).thenReturn("evt-y");
        when(graph.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of(seedNode));
        when(graph.getNode(anyString())).thenReturn(Optional.empty());
        when(graph.getNodeByExternalId(anyString(), any())).thenReturn(Optional.empty());

        AttributionResult result = AttributionResult.builder()
                .targetTitle("Outage")
                .chains(List.of(AttributionChain.builder()
                        .rootCauseTitle("Disk full")
                        .overallConfidence(0.70)
                        .build()))
                .build();
        when(attribution.explain(any())).thenReturn(result);

        // Disable trail-in-prompt via config
        KbConfig cfg = KbConfig.defaults();
        cfg.setReasoningTrailInPromptEnabled(false);
        KbConfigManager cfgManager = mock(KbConfigManager.class);
        when(cfgManager.current()).thenReturn(cfg);

        GraphReasoningRetriever retriever = new GraphReasoningRetriever(graph, attribution, null, cfgManager);
        GraphReasoningRetriever.ReasoningRetrieval r =
                retriever.retrieveWithTrail("What happened?", "CAUSAL", 5);

        assertNotNull(r);
        assertFalse(r.context().contains("Reasoning trace"),
                "context must NOT contain 'Reasoning trace' when disabled. Was:\n" + r.context());
    }

    @Test
    @DisplayName("GAP 5: trail-in-prompt respects kbReasoningTrailPromptMaxLines")
    void trailInPromptRespectsMaxLines() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        BayesianNetworkService bayesian = mock(BayesianNetworkService.class);

        GraphNode seedNode = mock(GraphNode.class);
        when(seedNode.getNodeId()).thenReturn("seed-z");
        when(graph.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of(seedNode));
        when(graph.getNode(anyString())).thenReturn(Optional.empty());
        when(graph.getNodeByExternalId(anyString(), any())).thenReturn(Optional.empty());

        // Build a large posteriors map (20 entries)
        Map<String, Double> posteriors = new java.util.LinkedHashMap<>();
        Map<String, String> titles = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= 20; i++) {
            posteriors.put("var" + i, 1.0 - i * 0.04);
            titles.put("var" + i, "Factor " + i);
        }
        BayesianInferenceResult inference = BayesianInferenceResult.builder()
                .posteriors(posteriors)
                .variableToTitle(titles)
                .build();
        when(bayesian.queryMebnFromKg(any(), any(), anyInt(), anyInt())).thenReturn(inference);

        // Restrict to 5 max lines
        KbConfig cfg = KbConfig.defaults();
        cfg.setReasoningTrailInPromptEnabled(true);
        cfg.setReasoningTrailPromptMaxLines(5);
        KbConfigManager cfgManager = mock(KbConfigManager.class);
        when(cfgManager.current()).thenReturn(cfg);

        GraphReasoningRetriever retriever = new GraphReasoningRetriever(graph, null, bayesian, cfgManager);
        GraphReasoningRetriever.ReasoningRetrieval r =
                retriever.retrieveWithTrail("Most relevant factors?", "PROBABILISTIC", 20);

        assertNotNull(r);
        // Count lines in the "Reasoning trace:" section only
        String context = r.context();
        int traceStart = context.indexOf("Reasoning trace");
        assertTrue(traceStart >= 0, "should have trace section: " + context);
        String traceSection = context.substring(traceStart);
        long lineCount = traceSection.lines().count();
        assertTrue(lineCount <= 6, // <= maxLines + 1 header line tolerance
                "trace section must be bounded by maxLines=5, but had " + lineCount + " lines:\n" + traceSection);
        assertTrue(traceSection.contains("target=Seed-z; confidence=0.960"), traceSection);
        assertTrue(traceSection.contains("Confidence breakdown: mebn=0.960"), traceSection);
        assertTrue(traceSection.contains("Evidence: [trace.evidence.0] Factor 1: P(true)=0.960"), traceSection);
    }

    @Test
    @DisplayName("ReasoningTrailMapper exposes LLM context for trail and canonical trace DTOs")
    void reasoningTrailMapperExposesLlmContext() {
        ReasoningTrail trail = ReasoningTrail.builder("target-node")
                .question("Why target?")
                .inferenceMode("CAUSAL")
                .confidence(0.7)
                .evidence(List.of("Root cause: upstream demand (confidence 0.70)"))
                .build();

        Map<String, Object> trailDto = ReasoningTrailMapper.toTrailDto(trail);
        assertTrue(trailDto.containsKey("llmContext"));
        String trailContext = (String) trailDto.get("llmContext");
        assertTrue(trailContext.contains("Reasoning trace (CAUSAL): id=trace.root; target=target-node; confidence=0.700"));
        assertTrue(trailContext.contains("Evidence:"));
        List<Map<String, Object>> trailAttributions = (List<Map<String, Object>>) trailDto.get("attributionIndex");
        assertTrue(trailAttributions.stream().anyMatch(step -> "trace.evidence.0".equals(step.get("stepId"))));
        Map<String, Object> trailEvidenceAttribution = trailAttributions.stream()
                .filter(step -> "trace.evidence.0".equals(step.get("stepId")))
                .findFirst()
                .orElseThrow();
        List<Map<String, Object>> trailEvidenceRefs =
                (List<Map<String, Object>>) trailEvidenceAttribution.get("evidenceRefs");
        assertTrue(trailEvidenceRefs.stream().anyMatch(ref -> "EVIDENCE".equals(ref.get("refType"))
                && "evidence".equals(ref.get("sourceId"))));
        List<Map<String, Object>> trailGaps = (List<Map<String, Object>>) trailDto.get("traceGaps");
        assertTrue(trailGaps.stream().anyMatch(gap -> "SINGLE_SUPPORT".equals(gap.get("gapType"))
                && ((List<String>) gap.get("relatedStepIds")).contains("trace.evidence.0")));

        ReasoningTrace trace = ReasoningTrace.of(ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.RULE,
                "derived(target)",
                "rule-1",
                0.8,
                ReasoningTrace.Step.fact("fact(target)", 1.0, "graph edge:fact-edge")));
        Map<String, Object> traceDto = ReasoningTrailMapper.toTrailDto(trace);
        assertTrue(traceDto.containsKey("llmContext"));
        String traceContext = (String) traceDto.get("llmContext");
        assertTrue(traceContext.contains("Reasoning trace (RULE): id=trace.root; conclusion=derived(target); confidence=0.800"));
        assertTrue(traceContext.contains("fact(target)"));
        Map<String, Object> derivationTree = (Map<String, Object>) traceDto.get("derivationTree");
        assertEquals("trace.root", derivationTree.get("stepId"));
        List<Map<String, Object>> traceAttributions = (List<Map<String, Object>>) traceDto.get("attributionIndex");
        assertTrue(traceAttributions.stream().anyMatch(step -> "trace.root.0".equals(step.get("stepId"))));
        Map<String, Object> traceFactAttribution = traceAttributions.stream()
                .filter(step -> "trace.root.0".equals(step.get("stepId")))
                .findFirst()
                .orElseThrow();
        List<Map<String, Object>> traceEvidenceRefs =
                (List<Map<String, Object>>) traceFactAttribution.get("evidenceRefs");
        assertTrue(traceEvidenceRefs.stream().anyMatch(ref -> "fact-edge".equals(ref.get("edgeId"))
                && "graph edge:fact-edge".equals(ref.get("sourceId"))));
        List<Map<String, Object>> traceGaps = (List<Map<String, Object>>) traceDto.get("traceGaps");
        assertTrue(traceGaps.stream().anyMatch(gap -> "SINGLE_SUPPORT".equals(gap.get("gapType"))
                && ((List<String>) gap.get("relatedStepIds")).contains("trace.root.0")));
    }

    // ── KbConfig knob defaults ────────────────────────────────────────────────────

    @Test
    @DisplayName("KbConfig: reasoningTrailInPromptEnabled defaults to true")
    void kbConfigReasoningTrailInPromptDefaultTrue() {
        KbConfig cfg = KbConfig.defaults();
        assertTrue(cfg.isReasoningTrailInPromptEnabled());
    }

    @Test
    @DisplayName("KbConfig: reasoningTrailPromptMaxLines defaults to 20")
    void kbConfigReasoningTrailPromptMaxLinesDefault20() {
        KbConfig cfg = KbConfig.defaults();
        assertEquals(20, cfg.getReasoningTrailPromptMaxLines());
    }

    @Test
    @DisplayName("KbConfig: toMap includes both new knobs")
    void kbConfigToMapIncludesNewKnobs() {
        Map<String, Object> m = KbConfig.defaults().toMap();
        assertTrue(m.containsKey("kbReasoningTrailInPromptEnabled"),
                "toMap should include kbReasoningTrailInPromptEnabled");
        assertTrue(m.containsKey("kbReasoningTrailPromptMaxLines"),
                "toMap should include kbReasoningTrailPromptMaxLines");
        assertEquals(true, m.get("kbReasoningTrailInPromptEnabled"));
        assertEquals(20, m.get("kbReasoningTrailPromptMaxLines"));
    }

    @Test
    @DisplayName("KbConfig: from() round-trips new knobs")
    void kbConfigFromRoundTrips() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
        root.put("kbReasoningTrailInPromptEnabled", false);
        root.put("kbReasoningTrailPromptMaxLines", 15);
        KbConfig parsed = KbConfig.from(root);
        assertFalse(parsed.isReasoningTrailInPromptEnabled());
        assertEquals(15, parsed.getReasoningTrailPromptMaxLines());
    }
}
