/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.knowledgegraph.confidence.ExtractionConfidenceStamper;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import ai.kompile.knowledgegraph.confidence.SourceTrustResolver;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that LLM-extracted edges, when processed through
 * {@link ExtractionConfidenceStamper#stampEdgeConfidence}, carry a non-null confidence
 * strictly below 1.0 and therefore project as SOFT facts (not hard-observed) in
 * {@link GraphToFactStoreProjector}.
 *
 * <p>This is the complement to the {@code project_edgeWithNullConfidenceNullWeight_isHardFact_documentsKnownBug}
 * test in {@link EnrichmentProjectorTest}: that test documents the existing (buggy) behaviour
 * of the projector when no stamping occurs; this test proves that stamping fixes the root cause —
 * edges with a Beta-MAP confidence from the stamper project as soft facts, enabling PSL MAP
 * derivation to produce non-trivial inferences.</p>
 *
 * <p>No Spring context, no ND4J backend, no subprocess required.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StampedEdgeProjectionTest {

    private static final long FACT_SHEET_ID = 99L;

    private ExtractionConfidenceStamper stamper;
    private KnowledgeGraphService mockKgService;
    private KbGroundingService groundingService;
    private GraphToFactStoreProjector projector;

    @BeforeEach
    void setUp() {
        // Wire a real stamper with default KbConfig — no Spring context needed.
        KbConfigManager mockCfgMgr = mock(KbConfigManager.class);
        when(mockCfgMgr.current()).thenReturn(KbConfig.defaults());
        SourceTrustResolver trustResolver = new SourceTrustResolver(mockCfgMgr);
        stamper = new ExtractionConfidenceStamper(trustResolver, mockCfgMgr);

        mockKgService = mock(KnowledgeGraphService.class);
        groundingService = new KbGroundingService();
        projector = new GraphToFactStoreProjector(mockKgService, groundingService);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STAMPER CONTRACT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * {@code stampEdgeConfidence("LLM_EXTRACTION", null)} must return a finite value
     * strictly below 0.99 (i.e., < 1.0) and write provenance keys into the metadata map.
     *
     * <p>This is the core fix: before the stamper was wired to CONTAINS edges in
     * {@code GraphExtractionOrchestrator}, every doc→entity CONTAINS edge was created with
     * weight=1.0 (hardcoded). After the fix the weight is the stamper's Beta-MAP expectation
     * (≈ 0.6 for LLM_EXTRACTION with default priors and no raw confidence).</p>
     */
    @Test
    void stampEdgeConfidence_llmExtraction_nullRawConfidence_returnsSubOneSoftWeight() {
        Map<String, Object> meta = new LinkedHashMap<>();

        double weight = stamper.stampEdgeConfidence(meta, "LLM_EXTRACTION", null);

        // Must be a real Beta-MAP value, not the hard-default 1.0
        assertTrue(Double.isFinite(weight),
                "stampEdgeConfidence must return a finite value for LLM_EXTRACTION");
        assertTrue(weight < 0.99,
                "stampEdgeConfidence(LLM_EXTRACTION, null) must return < 0.99 (got " + weight + "); "
                + "1.0 means the stamper was not called or the collaborators are absent");

        // Verify provenance keys were written into metadata
        assertNotNull(meta.get("_opinion"),
                "stampEdgeConfidence must write _opinion key into metadata");
        assertNotNull(meta.get("_basisType"),
                "stampEdgeConfidence must write _basisType key into metadata");
        assertNotNull(meta.get("_validFrom"),
                "stampEdgeConfidence must write _validFrom key into metadata");
    }

    /**
     * {@code stampEdgeConfidence("LLM_EXTRACTION", 0.7)} (raw confidence provided by the LLM)
     * must also return < 0.99 and write provenance keys. This covers the case where the LLM
     * returned a calibrated score that the stamper incorporates as positive evidence.
     */
    @Test
    void stampEdgeConfidence_llmExtraction_withRawConfidence_returnsSubOneSoftWeight() {
        Map<String, Object> meta = new LinkedHashMap<>();

        double weight = stamper.stampEdgeConfidence(meta, "LLM_EXTRACTION", 0.7);

        assertTrue(Double.isFinite(weight),
                "stampEdgeConfidence with rawConfidence=0.7 must return a finite value");
        assertTrue(weight < 0.99,
                "stampEdgeConfidence(LLM_EXTRACTION, 0.7) must return < 0.99 (got " + weight + ")");
        assertNotNull(meta.get("_opinion"), "provenance _opinion key must be written");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // END-TO-END: stamped edge → soft PSL fact
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * An LLM-extracted edge whose weight was set by the stamper (not hardcoded to 1.0)
     * projects as a SOFT fact in {@link GraphToFactStoreProjector}, enabling PSL MAP
     * derivation to produce non-trivial inferences.
     *
     * <p>This is the end-to-end proof that Fix 1 (wiring the stamper to CONTAINS edges in
     * GraphExtractionOrchestrator) resolves the all-hard PSL projection problem.</p>
     */
    @Test
    void stampedEdge_projectsAsSoftFact_enablingPslDerivation() {
        // Step 1: simulate what the fixed GraphExtractionOrchestrator now does for a CONTAINS edge
        Map<String, Object> containsMeta = new LinkedHashMap<>();
        containsMeta.put("entityType", "PERSON");
        containsMeta.put("entityName", "Alice");
        double edgeWeight = stamper.stampEdgeConfidence(containsMeta, "LLM_EXTRACTION", null);

        // Step 2: build a GraphEdge with that weight (no hardcoded 1.0)
        GraphEdge edge = buildEdge("doc-1", "entity-alice", "contains", edgeWeight);

        // Step 3: project through the fact-store projector
        when(mockKgService.getNodesInFactSheet(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        when(mockKgService.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(edge));
        projector.project(FACT_SHEET_ID);

        // Step 4: assert the projected fact is SOFT (value < 0.99), not hard-observed
        FactStore factStore = groundingService.getState(FACT_SHEET_ID).factStore();
        assertFalse(factStore.allFacts().isEmpty(),
                "Projector must produce at least one atom from the stamped edge");

        Fact fact = factStore.allFacts().iterator().next();
        assertFalse(fact.hard(),
                "A stamped LLM-extracted edge must project as a SOFT fact (not hard-observed). "
                + "If this fails the stamper is still not being called at the extraction call-site. "
                + "Projected value=" + fact.value());
        assertTrue(fact.value() < 0.99,
                "Soft fact value must be < 0.99 (stamped Beta-MAP confidence). Got: " + fact.value());
    }

    /**
     * Baseline: an un-stamped edge (confidence=null, weight=1.0 hardcoded) projects as HARD.
     * This confirms the projector is unchanged and that the fix operates at the extraction
     * call-site (not in the projector itself).
     */
    @Test
    void unstampedEdge_weight1_0_projectsAsHardFact_baselineConfirmation() {
        // Simulate the OLD (buggy) behaviour: createEdgeWithMetadata(... 1.0 ...) without stamper
        GraphEdge edge = buildEdge("doc-1", "entity-alice", "contains", 1.0);

        when(mockKgService.getNodesInFactSheet(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        when(mockKgService.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(edge));
        projector.project(FACT_SHEET_ID);

        FactStore factStore = groundingService.getState(FACT_SHEET_ID).factStore();
        Fact fact = factStore.allFacts().iterator().next();
        // Projector default: weight >= 0.99 → hard fact (the projector spec is correct; the bug
        // was in the upstream extraction site not calling the stamper before persisting the edge).
        assertTrue(fact.hard(),
                "Edge with weight=1.0 (un-stamped) must project as a hard fact (value >= 0.99). "
                + "This is the baseline that the extraction-site fix corrects.");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static GraphEdge buildEdge(String srcExtId, String tgtExtId,
                                       String relType, double weight) {
        GraphNode src = GraphNode.builder()
                .nodeId(UUID.randomUUID().toString())
                .externalId(srcExtId)
                .nodeType(NodeLevel.DOCUMENT)
                .title(srcExtId)
                .build();
        GraphNode tgt = GraphNode.builder()
                .nodeId(UUID.randomUUID().toString())
                .externalId(tgtExtId)
                .nodeType(NodeLevel.ENTITY)
                .title(tgtExtId)
                .build();
        return GraphEdge.builder()
                .edgeId(UUID.randomUUID().toString())
                .sourceNode(src)
                .targetNode(tgt)
                .edgeType(EdgeType.CONTAINS)
                .relationType(relType)
                .weight(weight)
                .confidence(weight < 0.99 ? weight : null) // confidence column nullable; projector falls back to weight
                .createdAt(LocalDateTime.now())
                .build();
    }
}
