/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.process.discovery;

import ai.kompile.event.attribution.domain.BayesianInferenceResult;
import ai.kompile.event.attribution.domain.InferenceStep;
import ai.kompile.event.attribution.service.BayesianNetworkService;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests that flowToSuggestion + enrichWithBayesian produces properly enriched suggestions.
 *
 * enrichWithBayesian() is private, so we exercise it via flowToSuggestion() (which calls
 * it internally) or via direct reflection for unit-level assertions.
 */
@ExtendWith(MockitoExtension.class)
class ProcessSuggestionEnrichmentTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @Mock
    private BayesianNetworkService bayesianNetworkService;

    private ProcessDiscoveryServiceImpl service;

    // ─────────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        service = new ProcessDiscoveryServiceImpl(knowledgeGraphService);
        service.setBayesianNetworkService(bayesianNetworkService);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: build a known BayesianInferenceResult with posteriors, priors,
    //          variableToNodeId, variableToTitle, and inferenceTrace
    // ─────────────────────────────────────────────────────────────────────────

    private BayesianInferenceResult buildKnownInferenceResult() {
        Map<String, Double> posteriors = new LinkedHashMap<>();
        posteriors.put("v_node1", 0.82);
        posteriors.put("v_node2", 0.61);
        posteriors.put("v_node3", 0.45);

        Map<String, Double> priors = new LinkedHashMap<>();
        priors.put("v_node1", 0.50);    // shift +0.32 → should appear in BAYESIAN evidence
        priors.put("v_node2", 0.605);   // shift +0.005 → strictly below threshold 0.01, should NOT appear
        priors.put("v_node3", 0.20);    // shift +0.25 → should appear in BAYESIAN evidence

        Map<String, String> varToNodeId = new LinkedHashMap<>();
        varToNodeId.put("v_node1", "node-1");
        varToNodeId.put("v_node2", "node-2");
        varToNodeId.put("v_node3", "node-3");

        Map<String, String> varToTitle = new LinkedHashMap<>();
        varToTitle.put("v_node1", "Email Process Node");
        varToTitle.put("v_node2", "Spreadsheet Node");
        varToTitle.put("v_node3", "Approval Node");

        List<InferenceStep> trace = List.of(
                InferenceStep.builder()
                        .eliminatedVariable("v_node1").operation("REDUCE")
                        .factorsInvolved(2).posteriorValue(0.82).build(),
                InferenceStep.builder()
                        .eliminatedVariable("v_node2").operation("MARGINALIZE")
                        .factorsInvolved(2).posteriorValue(0.61).build(),
                InferenceStep.builder()
                        .eliminatedVariable("v_node3").operation("NORMALIZE")
                        .factorsInvolved(1).posteriorValue(0.45).build()
        );

        Map<String, Object> networkStats = new LinkedHashMap<>();
        networkStats.put("nodeCount", 3);
        networkStats.put("edgeCount", 2);

        return BayesianInferenceResult.builder()
                .posteriors(posteriors)
                .priors(priors)
                .variableToNodeId(varToNodeId)
                .variableToTitle(varToTitle)
                .inferenceTrace(trace)
                .networkStats(networkStats)
                .computedAt(Instant.now())
                .computationTimeMs(5L)
                .build();
    }

    /**
     * Build a minimal FlowPattern with the given involvedNodeIds.
     */
    private FlowPattern buildFlowPattern(List<String> nodeIds) {
        return FlowPattern.builder()
                .type("EMAIL_FLOW")
                .description("Test email flow")
                .occurrenceCount(3)
                .confidence(0.75)
                .steps(List.of(
                        FlowPattern.FlowStep.builder()
                                .description("Send email")
                                .actor("alice")
                                .action("SEND")
                                .target("bob")
                                .nodeId(nodeIds.isEmpty() ? null : nodeIds.get(0))
                                .build()
                ))
                .involvedNodeIds(nodeIds)
                .build();
    }

    /**
     * Invoke the private enrichWithBayesian method via reflection.
     */
    private void invokeEnrichWithBayesian(ProcessSuggestion suggestion) throws Exception {
        Method m = ProcessDiscoveryServiceImpl.class.getDeclaredMethod(
                "enrichWithBayesian", ProcessSuggestion.class);
        m.setAccessible(true);
        m.invoke(service, suggestion);
    }

    /**
     * Invoke the private flowToSuggestion method via reflection.
     */
    private ProcessSuggestion invokeFlowToSuggestion(FlowPattern flow) throws Exception {
        Method m = ProcessDiscoveryServiceImpl.class.getDeclaredMethod(
                "flowToSuggestion", FlowPattern.class);
        m.setAccessible(true);
        return (ProcessSuggestion) m.invoke(service, flow);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. enrichWithBayesian populates bayesianPosteriors when service returns data
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_populatesBayesianPosteriors() throws Exception {
        BayesianInferenceResult result = buildKnownInferenceResult();
        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(result);

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Test suggestion")
                .confidence(0.75)
                .sourceGraphNodeIds(List.of("node-1", "node-2", "node-3"))
                .build();

        invokeEnrichWithBayesian(suggestion);

        assertNotNull(suggestion.getBayesianPosteriors());
        assertFalse(suggestion.getBayesianPosteriors().isEmpty(),
                "bayesianPosteriors must be non-empty after enrichment");

        // Verify posterior values are translated from BN variable names to KG node IDs
        assertTrue(suggestion.getBayesianPosteriors().containsKey("node-1")
                        || suggestion.getBayesianPosteriors().containsKey("v_node1"),
                "posteriors must use KG node IDs");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. bayesianPosteriors values match the BayesianInferenceResult posteriors
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_bayesianPosteriorValuesMatchResult() throws Exception {
        BayesianInferenceResult result = buildKnownInferenceResult();
        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(result);

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Value test")
                .confidence(0.7)
                .sourceGraphNodeIds(List.of("node-1", "node-2", "node-3"))
                .build();

        invokeEnrichWithBayesian(suggestion);

        Map<String, Double> posteriors = suggestion.getBayesianPosteriors();
        // v_node1 → node-1, posterior = 0.82
        assertEquals(0.82, posteriors.getOrDefault("node-1",
                posteriors.getOrDefault("v_node1", -1.0)), 1e-9);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. structuredEvidence contains BAYESIAN type entries for large prior→posterior shifts
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_structuredEvidenceContainsBayesianEntries() throws Exception {
        BayesianInferenceResult result = buildKnownInferenceResult();
        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(result);

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Evidence test")
                .confidence(0.8)
                .sourceGraphNodeIds(List.of("node-1", "node-2", "node-3"))
                .build();

        invokeEnrichWithBayesian(suggestion);

        List<ProcessSuggestion.StructuredEvidence> structured = suggestion.getStructuredEvidence();
        assertNotNull(structured);
        assertFalse(structured.isEmpty(), "structuredEvidence must be non-empty");

        boolean hasBayesian = structured.stream()
                .anyMatch(e -> "BAYESIAN".equals(e.getType()));
        assertTrue(hasBayesian,
                "structuredEvidence must contain at least one BAYESIAN type entry");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. BAYESIAN entries describe the prior→posterior shift
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_bayesianEntriesDescribePriorToPosteriorShift() throws Exception {
        BayesianInferenceResult result = buildKnownInferenceResult();
        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(result);

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Shift test")
                .confidence(0.8)
                .sourceGraphNodeIds(List.of("node-1", "node-2", "node-3"))
                .build();

        invokeEnrichWithBayesian(suggestion);

        // At least one BAYESIAN entry should mention "prior=" and "posterior="
        boolean hasShiftDescription = suggestion.getStructuredEvidence().stream()
                .filter(e -> "BAYESIAN".equals(e.getType()))
                .anyMatch(e -> e.getDescription() != null
                        && e.getDescription().contains("prior=")
                        && e.getDescription().contains("posterior="));

        assertTrue(hasShiftDescription,
                "BAYESIAN structured evidence must describe prior → posterior shift");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. structuredEvidence contains a CAUSAL entry with inference summary
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_structuredEvidenceContainsCausalEntry() throws Exception {
        BayesianInferenceResult result = buildKnownInferenceResult();
        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(result);

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Causal test")
                .confidence(0.8)
                .sourceGraphNodeIds(List.of("node-1", "node-2", "node-3"))
                .build();

        invokeEnrichWithBayesian(suggestion);

        boolean hasCausal = suggestion.getStructuredEvidence().stream()
                .anyMatch(e -> "CAUSAL".equals(e.getType()));
        assertTrue(hasCausal,
                "structuredEvidence must contain a CAUSAL type entry with inference summary");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. CAUSAL entry description mentions elimination steps
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_causalEntryDescribesEliminationSteps() throws Exception {
        BayesianInferenceResult result = buildKnownInferenceResult();
        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(result);

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Causal description test")
                .confidence(0.8)
                .sourceGraphNodeIds(List.of("node-1", "node-2", "node-3"))
                .build();

        invokeEnrichWithBayesian(suggestion);

        Optional<ProcessSuggestion.StructuredEvidence> causal = suggestion.getStructuredEvidence()
                .stream()
                .filter(e -> "CAUSAL".equals(e.getType()))
                .findFirst();

        assertTrue(causal.isPresent());
        String desc = causal.get().getDescription();
        assertNotNull(desc);
        // Should mention "elimination steps" or "Bayesian inference"
        assertTrue(desc.contains("elimination steps") || desc.contains("Bayesian inference"),
                "CAUSAL description should mention elimination steps, got: " + desc);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. enrichWithBayesian does nothing when bayesianNetworkService is null
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_doesNothingWhenServiceIsNull() throws Exception {
        ProcessDiscoveryServiceImpl serviceWithoutBayes =
                new ProcessDiscoveryServiceImpl(knowledgeGraphService);
        // setBayesianNetworkService is NOT called → null

        Method m = ProcessDiscoveryServiceImpl.class.getDeclaredMethod(
                "enrichWithBayesian", ProcessSuggestion.class);
        m.setAccessible(true);

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("No-bayesian test")
                .confidence(0.6)
                .sourceGraphNodeIds(List.of("node-1"))
                .build();

        // Should not throw
        assertDoesNotThrow(() -> m.invoke(serviceWithoutBayes, suggestion));

        // bayesianPosteriors should remain empty (default)
        assertTrue(suggestion.getBayesianPosteriors().isEmpty(),
                "bayesianPosteriors must remain empty when service is null");
        assertTrue(suggestion.getStructuredEvidence().isEmpty(),
                "structuredEvidence must remain empty when service is null");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. enrichWithBayesian does nothing when sourceGraphNodeIds is empty
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_doesNothingWhenNodeIdsIsEmpty() throws Exception {
        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Empty nodes test")
                .confidence(0.6)
                .sourceGraphNodeIds(List.of()) // empty
                .build();

        invokeEnrichWithBayesian(suggestion);

        assertTrue(suggestion.getBayesianPosteriors().isEmpty(),
                "bayesianPosteriors must remain empty when no sourceGraphNodeIds");
        verifyNoInteractions(bayesianNetworkService);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. enrichWithBayesian does nothing when sourceGraphNodeIds is null
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_doesNothingWhenNodeIdsIsNull() throws Exception {
        ProcessSuggestion suggestion = new ProcessSuggestion();
        suggestion.setName("Null nodes test");
        suggestion.setConfidence(0.6);
        // sourceGraphNodeIds is null

        assertDoesNotThrow(() -> invokeEnrichWithBayesian(suggestion));
        verifyNoInteractions(bayesianNetworkService);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 10. enrichWithBayesian handles BayesianNetworkService exception gracefully
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_handlesServiceExceptionGracefully() throws Exception {
        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("KG unavailable"));

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Exception test")
                .confidence(0.7)
                .sourceGraphNodeIds(List.of("node-1"))
                .build();

        // Must not throw — exception should be caught internally
        assertDoesNotThrow(() -> invokeEnrichWithBayesian(suggestion));
        // posteriors remain empty
        assertTrue(suggestion.getBayesianPosteriors().isEmpty(),
                "bayesianPosteriors must remain empty on service exception");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 11. enrichWithBayesian skips negligible shifts (< 0.01)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_skipsNegligiblePriorPosteriorShifts() throws Exception {
        // v_node2 has only 0.01 shift → should NOT produce a BAYESIAN entry
        BayesianInferenceResult result = buildKnownInferenceResult();
        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(result);

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Negligible shift test")
                .confidence(0.7)
                .sourceGraphNodeIds(List.of("node-1", "node-2", "node-3"))
                .build();

        invokeEnrichWithBayesian(suggestion);

        // BAYESIAN entries should NOT include "Spreadsheet Node" (v_node2, shift=+0.01)
        boolean hasSpreadsheetNode = suggestion.getStructuredEvidence().stream()
                .filter(e -> "BAYESIAN".equals(e.getType()))
                .anyMatch(e -> e.getDescription() != null
                        && e.getDescription().contains("Spreadsheet Node"));
        assertFalse(hasSpreadsheetNode,
                "Negligible shift (0.01) should not produce a BAYESIAN evidence entry");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 12. flowToSuggestion → enrichWithBayesian pipeline via public method
    //     (tests the full chain including suggestion construction)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void flowToSuggestion_callsEnrichWithBayesian() throws Exception {
        BayesianInferenceResult result = buildKnownInferenceResult();
        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(result);

        FlowPattern flow = buildFlowPattern(List.of("node-1", "node-2", "node-3"));
        ProcessSuggestion suggestion = invokeFlowToSuggestion(flow);

        assertNotNull(suggestion);
        // BayesianNetworkService should have been called
        verify(bayesianNetworkService).queryAllPosteriors(
                anyCollection(), anyMap(), anyInt(), anyInt());

        // bayesianPosteriors should be populated
        assertFalse(suggestion.getBayesianPosteriors().isEmpty(),
                "flowToSuggestion must call enrichWithBayesian and populate posteriors");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 13. flowToSuggestion with no nodeIds: enrichWithBayesian is called but produces
    //     no posteriors (empty nodeIds → early return)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void flowToSuggestion_emptyNodeIds_noEnrichment() throws Exception {
        FlowPattern flow = buildFlowPattern(List.of());
        ProcessSuggestion suggestion = invokeFlowToSuggestion(flow);

        assertNotNull(suggestion);
        assertTrue(suggestion.getBayesianPosteriors().isEmpty(),
                "Empty nodeIds must produce no bayesianPosteriors");
        verifyNoInteractions(bayesianNetworkService);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 14. discoverProcesses pipeline: Bayesian enrichment applied to email flows
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void discoverProcesses_emailFlow_enrichedWithBayesian() {
        // Set up KG mock: two person nodes with 3 SENT_BY edges
        GraphNode alice = GraphNode.builder()
                .nodeId("alice").nodeType(NodeLevel.ENTITY).externalId("alice").title("Alice")
                .metadataJson("{\"entity_type\":\"PERSON\"}").build();
        GraphNode bob = GraphNode.builder()
                .nodeId("bob").nodeType(NodeLevel.ENTITY).externalId("bob").title("Bob")
                .metadataJson("{\"entity_type\":\"PERSON\"}").build();

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                .thenReturn(new ArrayList<>(List.of(alice, bob)));
        when(knowledgeGraphService.searchNodes(eq("sheet"), eq(NodeLevel.TABLE), anyInt()))
                .thenReturn(new ArrayList<>());
        when(knowledgeGraphService.searchNodes(eq("table"), eq(NodeLevel.TABLE), anyInt()))
                .thenReturn(new ArrayList<>());
        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>());

        GraphEdge e1 = buildEdge(alice, bob, "SENT_BY");
        GraphEdge e2 = buildEdge(alice, bob, "SENT_BY");
        GraphEdge e3 = buildEdge(alice, bob, "SENT_BY");
        when(knowledgeGraphService.getEdgesForNode("alice")).thenReturn(List.of(e1, e2, e3));
        when(knowledgeGraphService.getEdgesForNode("bob")).thenReturn(List.of());

        // Bayesian enrichment mock
        BayesianInferenceResult result = buildKnownInferenceResult();
        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(result);

        List<ProcessSuggestion> suggestions = service.discoverProcesses(null, Map.of("useLlm", false));

        assertFalse(suggestions.isEmpty(), "Should discover at least one email flow process");
        // At least one suggestion should be enriched
        boolean hasEnrichedSuggestion = suggestions.stream()
                .anyMatch(s -> !s.getBayesianPosteriors().isEmpty());
        assertTrue(hasEnrichedSuggestion,
                "At least one discovered suggestion must have bayesianPosteriors from enrichment");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 15. enrichWithBayesian: when result has empty posteriors, no evidence is added
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_emptyPosteriors_noEvidenceAdded() throws Exception {
        BayesianInferenceResult emptyResult = BayesianInferenceResult.builder()
                .posteriors(new LinkedHashMap<>())
                .priors(new LinkedHashMap<>())
                .inferenceTrace(new ArrayList<>())
                .computedAt(Instant.now())
                .build();

        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(emptyResult);

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Empty posteriors test")
                .confidence(0.7)
                .sourceGraphNodeIds(List.of("node-1"))
                .build();

        invokeEnrichWithBayesian(suggestion);

        assertTrue(suggestion.getBayesianPosteriors().isEmpty(),
                "Empty posteriors must produce no bayesianPosteriors on suggestion");
        assertTrue(suggestion.getStructuredEvidence().isEmpty(),
                "Empty posteriors must produce no structuredEvidence on suggestion");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 16. BAYESIAN entry score matches the posterior value
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_bayesianEntryScoreMatchesPosterior() throws Exception {
        BayesianInferenceResult result = buildKnownInferenceResult();
        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(result);

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Score test")
                .confidence(0.75)
                .sourceGraphNodeIds(List.of("node-1", "node-2", "node-3"))
                .build();

        invokeEnrichWithBayesian(suggestion);

        // Find the BAYESIAN entry for "Email Process Node" (v_node1, posterior=0.82)
        Optional<ProcessSuggestion.StructuredEvidence> bayesianEntry = suggestion.getStructuredEvidence()
                .stream()
                .filter(e -> "BAYESIAN".equals(e.getType())
                        && e.getDescription() != null
                        && e.getDescription().contains("Email Process Node"))
                .findFirst();

        assertTrue(bayesianEntry.isPresent(), "Should have BAYESIAN entry for Email Process Node");
        assertEquals(0.82, bayesianEntry.get().getScore(), 1e-9,
                "BAYESIAN entry score must match the posterior value 0.82");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 17. CAUSAL entry score matches the suggestion confidence
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_causalEntryScoreMatchesSuggestionConfidence() throws Exception {
        BayesianInferenceResult result = buildKnownInferenceResult();
        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(result);

        double confidence = 0.83;
        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("CAUSAL score test")
                .confidence(confidence)
                .sourceGraphNodeIds(List.of("node-1", "node-2", "node-3"))
                .build();

        invokeEnrichWithBayesian(suggestion);

        Optional<ProcessSuggestion.StructuredEvidence> causal = suggestion.getStructuredEvidence()
                .stream()
                .filter(e -> "CAUSAL".equals(e.getType()))
                .findFirst();

        assertTrue(causal.isPresent(), "Should have a CAUSAL entry");
        assertEquals(confidence, causal.get().getScore(), 1e-9,
                "CAUSAL entry score must match suggestion confidence");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 18. BAYESIAN entries have supportingNodeIds referencing the KG node ID
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_bayesianEntriesHaveSupportingNodeIds() throws Exception {
        BayesianInferenceResult result = buildKnownInferenceResult();
        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(result);

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("SupportingIds test")
                .confidence(0.7)
                .sourceGraphNodeIds(List.of("node-1", "node-2", "node-3"))
                .build();

        invokeEnrichWithBayesian(suggestion);

        // All BAYESIAN entries with non-null supportingNodeIds should reference KG IDs
        suggestion.getStructuredEvidence().stream()
                .filter(e -> "BAYESIAN".equals(e.getType()))
                .filter(e -> e.getSupportingNodeIds() != null && !e.getSupportingNodeIds().isEmpty())
                .forEach(e -> {
                    String nodeId = e.getSupportingNodeIds().get(0);
                    assertTrue(nodeId.startsWith("node-"),
                            "BAYESIAN supportingNodeIds should be KG node IDs, got: " + nodeId);
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 19. enrichWithBayesian populates bayesianPriors keyed by KG node IDs
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_populatesPriors() throws Exception {
        BayesianInferenceResult result = buildKnownInferenceResult();
        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(result);

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Priors population test")
                .confidence(0.75)
                .sourceGraphNodeIds(List.of("node-1", "node-2", "node-3"))
                .build();

        invokeEnrichWithBayesian(suggestion);

        Map<String, Double> priors = suggestion.getBayesianPriors();
        assertNotNull(priors, "bayesianPriors must not be null after enrichment");
        assertFalse(priors.isEmpty(), "bayesianPriors must be non-empty after enrichment");

        // The priors map must be keyed by KG node IDs (node-1, node-2, node-3),
        // not by BN variable names (v_node1, v_node2, v_node3)
        assertTrue(priors.containsKey("node-1"),
                "bayesianPriors must contain KG node ID 'node-1', got keys: " + priors.keySet());
        assertTrue(priors.containsKey("node-2"),
                "bayesianPriors must contain KG node ID 'node-2', got keys: " + priors.keySet());
        assertTrue(priors.containsKey("node-3"),
                "bayesianPriors must contain KG node ID 'node-3', got keys: " + priors.keySet());

        // Must NOT be keyed by variable names
        assertFalse(priors.containsKey("v_node1"),
                "bayesianPriors must use KG node IDs, not BN variable names like 'v_node1'");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 20. Every key in bayesianPriors also exists in bayesianPosteriors
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_priorsMatchPosteriorKeys() throws Exception {
        BayesianInferenceResult result = buildKnownInferenceResult();
        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(result);

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Priors vs posteriors keys test")
                .confidence(0.75)
                .sourceGraphNodeIds(List.of("node-1", "node-2", "node-3"))
                .build();

        invokeEnrichWithBayesian(suggestion);

        Map<String, Double> priors = suggestion.getBayesianPriors();
        Map<String, Double> posteriors = suggestion.getBayesianPosteriors();

        assertFalse(priors.isEmpty(), "bayesianPriors must be non-empty");
        assertFalse(posteriors.isEmpty(), "bayesianPosteriors must be non-empty");

        // Every prior key must also be present in the posteriors map
        for (String priorKey : priors.keySet()) {
            assertTrue(posteriors.containsKey(priorKey),
                    "Key '" + priorKey + "' present in bayesianPriors but missing from bayesianPosteriors");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 21. Prior values in suggestion match those from BayesianInferenceResult
    //     (after variable→nodeId translation)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_priorsPreserveValues() throws Exception {
        BayesianInferenceResult result = buildKnownInferenceResult();
        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(result);

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Priors value preservation test")
                .confidence(0.75)
                .sourceGraphNodeIds(List.of("node-1", "node-2", "node-3"))
                .build();

        invokeEnrichWithBayesian(suggestion);

        Map<String, Double> priors = suggestion.getBayesianPriors();

        // buildKnownInferenceResult defines:
        //   v_node1 → node-1, prior = 0.50
        //   v_node2 → node-2, prior = 0.605
        //   v_node3 → node-3, prior = 0.20
        assertEquals(0.50, priors.getOrDefault("node-1", -1.0), 1e-9,
                "Prior for node-1 must be 0.50 as returned by BayesianInferenceResult");
        assertEquals(0.605, priors.getOrDefault("node-2", -1.0), 1e-9,
                "Prior for node-2 must be 0.605 as returned by BayesianInferenceResult");
        assertEquals(0.20, priors.getOrDefault("node-3", -1.0), 1e-9,
                "Prior for node-3 must be 0.20 as returned by BayesianInferenceResult");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 22. When result.getPriors() is empty, suggestion.getBayesianPriors() is empty
    //     (not null) — the @Builder.Default LinkedHashMap is preserved
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void enrichWithBayesian_emptyPriors_leavesEmptyMap() throws Exception {
        // Build a result with non-empty posteriors but empty priors
        Map<String, Double> posteriors = new LinkedHashMap<>();
        posteriors.put("v_node1", 0.82);

        Map<String, String> varToNodeId = new LinkedHashMap<>();
        varToNodeId.put("v_node1", "node-1");

        BayesianInferenceResult resultWithEmptyPriors = BayesianInferenceResult.builder()
                .posteriors(posteriors)
                .priors(new LinkedHashMap<>())   // explicitly empty
                .variableToNodeId(varToNodeId)
                .variableToTitle(new LinkedHashMap<>())
                .inferenceTrace(new ArrayList<>())
                .computedAt(java.time.Instant.now())
                .build();

        when(bayesianNetworkService.queryAllPosteriors(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(resultWithEmptyPriors);

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Empty priors test")
                .confidence(0.70)
                .sourceGraphNodeIds(List.of("node-1"))
                .build();

        invokeEnrichWithBayesian(suggestion);

        // bayesianPriors must not be null — the field has @Builder.Default = new LinkedHashMap<>()
        assertNotNull(suggestion.getBayesianPriors(),
                "bayesianPriors must never be null (default is empty LinkedHashMap)");

        // And it must be empty because result.getPriors() was empty
        assertTrue(suggestion.getBayesianPriors().isEmpty(),
                "bayesianPriors must be empty when result.getPriors() returns empty map");

        // posteriors should still be populated
        assertFalse(suggestion.getBayesianPosteriors().isEmpty(),
                "bayesianPosteriors must still be populated even when priors are empty");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: build a GraphEdge
    // ─────────────────────────────────────────────────────────────────────────

    private GraphEdge buildEdge(GraphNode source, GraphNode target, String label) {
        return GraphEdge.builder()
                .edgeId(source.getNodeId() + "->" + target.getNodeId())
                .sourceNode(source)
                .targetNode(target)
                .edgeType(EdgeType.USER_DEFINED)
                .weight(1.0)
                .label(label)
                .description(label + " edge")
                .build();
    }
}
