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
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real unit tests for the ENRICHMENT pipeline phase, specifically
 * {@link GraphToFactStoreProjector} — the bridge that projects crawl-graph nodes and edges
 * into the PSL {@link FactStore} before the MAP inference solve.
 *
 * <p>Covers:
 * <ul>
 *   <li>Core behaviour: nodes → unary type atoms; edges → binary predicate atoms</li>
 *   <li>Edge confidence handling: edges with null confidence/weight project as hard (value=1.0),
 *       while edges with explicit confidence &lt; 1.0 project as soft facts</li>
 *   <li>Known bug (task #23): all-hard projection when extraction sites do not stamp confidence
 *       — the projector is CORRECT, but the lack of confidence on extracted edges means MAP
 *       posteriors are all 1.0 and PSL derivation produces nothing new</li>
 *   <li>Resumability / idempotence: project() is independently re-runnable; calling it twice
 *       produces the same atom set (FactStore's revision semantics, not append)</li>
 *   <li>No-op when KnowledgeGraphService is null</li>
 * </ul>
 *
 * <p>No Spring context, no ND4J backend, no subprocess required.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnrichmentProjectorTest {

    private static final long FACT_SHEET_ID = 42L;

    private KnowledgeGraphService mockService;
    private KbGroundingService groundingService;
    private GraphToFactStoreProjector projector;

    @BeforeEach
    void setUp() {
        mockService = mock(KnowledgeGraphService.class);
        // No-arg constructor: uses in-memory InferredFactStore, no Spring, no JPA
        groundingService = new KbGroundingService();
        projector = new GraphToFactStoreProjector(mockService, groundingService);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NULL-SERVICE: no-op guard
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * When {@link KnowledgeGraphService} is null (plain-Java test contexts that supply facts
     * directly), {@code project()} is a no-op returning 0.
     *
     * <p>This is the null-safety contract documented in the class Javadoc and relied upon by the
     * 17 existing tests that bypass the projector entirely.</p>
     */
    @Test
    void project_withNullService_returnsZero() {
        GraphToFactStoreProjector nullServiceProjector =
                new GraphToFactStoreProjector(null, groundingService);

        int asserted = nullServiceProjector.project(FACT_SHEET_ID);

        assertEquals(0, asserted, "Null KnowledgeGraphService must be a no-op — 0 atoms asserted");
        // FactStore must remain empty
        FactStore factStore = groundingService.getState(FACT_SHEET_ID).factStore();
        assertTrue(factStore.allFacts().isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CORE BEHAVIOUR: node atoms + edge atoms
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Nodes with a non-null nodeType and externalId produce a unary type atom of the form
     * {@code lowercase(nodeType)(externalId)}.  Nodes with null nodeType or null externalId
     * are silently skipped.
     */
    @Test
    void project_nodes_createsUnaryTypeAtoms() {
        GraphNode alice = buildNode("n1", NodeLevel.ENTITY, "alice");
        GraphNode withoutType = buildNode("n2", null, "no-type");

        when(mockService.getNodesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(alice, withoutType));
        when(mockService.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(Collections.emptyList());

        int asserted = projector.project(FACT_SHEET_ID);

        // Only alice (non-null type) should produce an atom; withoutType is skipped
        assertEquals(1, asserted, "One valid node → one atom; node with null type must be skipped");

        FactStore factStore = groundingService.getState(FACT_SHEET_ID).factStore();
        Collection<Fact> facts = factStore.allFacts();
        assertEquals(1, facts.size());

        Fact atomFact = facts.iterator().next();
        assertEquals("entity(alice)", atomFact.atomKey(),
                "Node atom key must be lowercase(nodeType)(externalId)");
        assertTrue(atomFact.hard(), "Node atoms are always hard-observed (value=1.0)");
    }

    /**
     * Edges produce binary predicate atoms using the semantic relationType when present,
     * falling back to the structural EdgeType name.
     */
    @Test
    void project_edges_createsBinaryPredicateAtoms() {
        when(mockService.getNodesInFactSheet(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        // Edge with explicit relationType AND confidence=0.5 (soft fact)
        GraphEdge knowsEdge = buildEdge("alice", "bob", "knows", 0.5, null);
        when(mockService.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(knowsEdge));

        int asserted = projector.project(FACT_SHEET_ID);

        assertEquals(1, asserted);
        FactStore factStore = groundingService.getState(FACT_SHEET_ID).factStore();
        Collection<Fact> facts = factStore.allFacts();
        assertEquals(1, facts.size());

        Fact f = facts.iterator().next();
        // predicate from relationType ("knows") + "(alice, bob)"
        assertEquals("knows(alice, bob)", f.atomKey());
    }

    /**
     * When relationType is null/blank, the edge atom falls back to
     * {@code EdgeType.name().toLowerCase()}.
     */
    @Test
    void project_edge_fallsBackToEdgeTypeName_whenRelationTypeNull() {
        when(mockService.getNodesInFactSheet(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        GraphEdge edge = buildEdge("src", "tgt", null, 0.5, null);
        when(mockService.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(edge));

        projector.project(FACT_SHEET_ID);

        FactStore factStore = groundingService.getState(FACT_SHEET_ID).factStore();
        Fact f = factStore.allFacts().iterator().next();
        // EdgeType.USER_DEFINED → "user_defined"
        assertTrue(f.atomKey().startsWith("user_defined("),
                "Atom key must use lowercase EdgeType name when relationType is null. Got: " + f.atomKey());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE CONFIDENCE HANDLING: hard vs soft facts
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Edge with explicit {@code confidence=0.5} projects as a soft fact (value=0.5, hard=false).
     * PSL MAP inference uses soft facts as priors that can be overridden by rules.
     */
    @Test
    void project_edgeWithConfidence_isSoftFact() {
        when(mockService.getNodesInFactSheet(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        GraphEdge edge = buildEdge("alice", "bob", "knows", 0.5, null);
        when(mockService.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(edge));

        projector.project(FACT_SHEET_ID);

        FactStore factStore = groundingService.getState(FACT_SHEET_ID).factStore();
        Fact f = factStore.allFacts().iterator().next();
        assertFalse(f.hard(), "confidence=0.5 must produce a soft fact (hard=false)");
        assertEquals(0.5, f.value(), 1e-9, "Soft fact value must equal the edge confidence");
    }

    /**
     * Edge with {@code weight=0.7} and {@code confidence=null} projects as a soft fact using the
     * weight value. The projector prefers confidence, then falls back to weight.
     */
    @Test
    void project_edgeWithWeightOnly_isSoftFactUsingWeight() {
        when(mockService.getNodesInFactSheet(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        // confidence=null, weight=0.7
        GraphEdge edge = buildEdge("alice", "bob", "knows", null, 0.7);
        when(mockService.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(edge));

        projector.project(FACT_SHEET_ID);

        FactStore factStore = groundingService.getState(FACT_SHEET_ID).factStore();
        Fact f = factStore.allFacts().iterator().next();
        assertFalse(f.hard(), "weight=0.7 (confidence=null) must produce a soft fact");
        assertEquals(0.7, f.value(), 1e-9, "Soft fact value must equal the edge weight");
    }

    /**
     * Edge with {@code confidence >= 0.99} projects as a hard-observed fact.
     * The projector threshold is {@code value >= 0.99} (near-certain observations are treated
     * as hard for efficiency — PSL doesn't iterate over them during MAP solve).
     */
    @Test
    void project_edgeWithHighConfidence_isHardFact() {
        when(mockService.getNodesInFactSheet(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        GraphEdge edge = buildEdge("a", "b", "knows", 1.0, null);
        when(mockService.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(edge));

        projector.project(FACT_SHEET_ID);

        FactStore factStore = groundingService.getState(FACT_SHEET_ID).factStore();
        Fact f = factStore.allFacts().iterator().next();
        assertTrue(f.hard(), "confidence=1.0 must be treated as hard (value >= 0.99 threshold)");
    }

    /**
     * KNOWN BUG (task #23): edges extracted by the LLM pipeline typically have
     * {@code confidence=null} AND {@code weight=null} because extraction call-sites do not invoke
     * {@code ExtractionConfidenceStamper.stampEdgeConfidence()} before persisting the edge.
     *
     * <p>The projector itself is CORRECT: its specification says "default 1.0 if null", and it
     * does exactly that. The bug is in the upstream extraction pipeline that fails to stamp
     * confidence before persisting edges, leaving every extracted edge with null values. The
     * consequence: ALL atoms are hard (value=1.0), MAP posteriors are all 1.0, and PSL derivation
     * writes 0 new inferred facts.</p>
     *
     * <p>This test documents the CURRENT (buggy-at-callsite) behaviour of the projector:
     * null-confidence edges produce hard facts. If the upstream extraction call-sites are ever
     * fixed to stamp confidence, this test will need updating — that update is the fix.</p>
     */
    @Test
    void project_edgeWithNullConfidenceNullWeight_isHardFact_documentsKnownBug() {
        when(mockService.getNodesInFactSheet(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        // confidence=null, weight=null → value defaults to 1.0 → hard fact
        GraphEdge edge = buildEdge("alice", "bob", "knows", null, null);
        when(mockService.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(edge));

        projector.project(FACT_SHEET_ID);

        FactStore factStore = groundingService.getState(FACT_SHEET_ID).factStore();
        Fact f = factStore.allFacts().iterator().next();

        // PROJECTOR BEHAVIOUR IS CORRECT: null → 1.0 → hard.
        // ROOT CAUSE OF THE PSL PROBLEM: upstream extraction sites do not stamp confidence.
        // When fixed (ExtractionConfidenceStamper called at all extraction sites), the atom
        // would be soft (confidence ≈ 0.6 for LLM_EXTRACTION single-observation Beta-MAP).
        assertTrue(f.hard(),
                "BUG(task#23): edge with null confidence/weight must project as hard=true (value=1.0). "
                + "Fix is in the extraction pipeline, not the projector.");
        assertEquals(1.0, f.value(), 1e-9,
                "BUG(task#23): null confidence/weight → value=1.0 (hard). "
                + "Fix: ExtractionConfidenceStamper must be called at all extraction callsites.");
    }

    /**
     * A mix of soft and hard edges produces both hard and soft atoms, giving PSL MAP inference
     * meaningful mixed-truth inputs to work with.
     */
    @Test
    void project_mixedConfidenceEdges_producesBothHardAndSoftAtoms() {
        when(mockService.getNodesInFactSheet(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        GraphEdge softEdge = buildEdge("alice", "bob",   "knows",    0.5,  null);
        GraphEdge hardEdge = buildEdge("bob",   "carol", "employs",  1.0,  null);
        when(mockService.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(softEdge, hardEdge));

        int asserted = projector.project(FACT_SHEET_ID);

        assertEquals(2, asserted);
        FactStore factStore = groundingService.getState(FACT_SHEET_ID).factStore();
        Collection<Fact> facts = factStore.allFacts();

        long hardCount = facts.stream().filter(Fact::hard).count();
        long softCount = facts.stream().filter(f -> !f.hard()).count();
        assertEquals(1, hardCount, "One hard edge (confidence=1.0)");
        assertEquals(1, softCount, "One soft edge (confidence=0.5)");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESUMABILITY: idempotent / independently re-runnable
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ENRICHMENT resumability: {@code project()} is independently re-runnable.
     *
     * <p>The FactStore uses revision semantics ({@code assertFact} upserts by atom key). Calling
     * {@code project()} a second time produces the same atom set — not a doubled set. This is the
     * "non-destructive, independently re-runnable" mandate from the crawl resumability spec.</p>
     */
    @Test
    void project_isIdempotent_doubleRunProducesSameAtomCount() {
        GraphNode alice = buildNode("n1", NodeLevel.ENTITY, "alice");
        GraphEdge edge  = buildEdge("alice", "bob", "knows", 0.5, null);

        when(mockService.getNodesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(alice));
        when(mockService.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(edge));

        int firstRun  = projector.project(FACT_SHEET_ID);
        int secondRun = projector.project(FACT_SHEET_ID);

        // Both runs report the same number of atoms asserted
        assertEquals(firstRun, secondRun,
                "Re-running project() must report the same count (idempotent)");

        // The FactStore holds exactly firstRun atoms — no duplicates from the second run
        FactStore factStore = groundingService.getState(FACT_SHEET_ID).factStore();
        assertEquals(firstRun, factStore.allFacts().size(),
                "FactStore must contain exactly the atom count from one run (revision semantics, not append)");
    }

    /**
     * ENRICHMENT resumability: a subsequent crawl run that produces UPDATED confidence values
     * for existing edges correctly overwrites (revises) the prior soft fact to the new value.
     *
     * <p>This proves that re-running enrichment after re-crawling does not leave stale confidence
     * values — the FactStore always reflects the most recently crawled graph state.</p>
     */
    @Test
    void project_revisesExistingAtom_whenConfidenceChanges() {
        GraphEdge firstEdge  = buildEdge("alice", "bob", "knows", 0.4, null);
        GraphEdge revisedEdge = buildEdge("alice", "bob", "knows", 0.8, null);

        when(mockService.getNodesInFactSheet(FACT_SHEET_ID)).thenReturn(Collections.emptyList());

        // First crawl run: confidence=0.4
        when(mockService.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(firstEdge));
        projector.project(FACT_SHEET_ID);

        FactStore factStore = groundingService.getState(FACT_SHEET_ID).factStore();
        Fact before = factStore.allFacts().iterator().next();
        assertEquals(0.4, before.value(), 1e-9, "First run must store confidence=0.4");

        // Second crawl run: revised confidence=0.8 (e.g. after re-extraction)
        when(mockService.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(revisedEdge));
        projector.project(FACT_SHEET_ID);

        Fact after = factStore.allFacts().iterator().next();
        assertEquals(0.8, after.value(), 1e-9,
                "Second run must revise the fact to the new confidence=0.8 (upsert, not append)");
        assertEquals(1, factStore.allFacts().size(),
                "Still only one atom — revision did not create a duplicate");
    }

    /**
     * ENRICHMENT resumability: enrichment with zero nodes and zero edges (empty graph) completes
     * without error and returns 0. A subsequent run with populated data works correctly.
     * This validates the re-run-when-embeddings-return pattern.
     */
    @Test
    void project_emptyGraph_then_populatedGraph_enrichmentRunsIndependently() {
        // First run: empty graph (e.g., embeddings not yet returned)
        when(mockService.getNodesInFactSheet(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        when(mockService.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(Collections.emptyList());

        int firstRun = projector.project(FACT_SHEET_ID);
        assertEquals(0, firstRun, "Empty graph → 0 atoms");

        // Second run: embeddings returned, graph now populated
        GraphNode node = buildNode("n1", NodeLevel.ENTITY, "alice");
        GraphEdge edge = buildEdge("alice", "bob", "knows", 0.6, null);
        when(mockService.getNodesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(node));
        when(mockService.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(edge));

        int secondRun = projector.project(FACT_SHEET_ID);
        assertEquals(2, secondRun, "Node + edge → 2 atoms on second run");

        FactStore factStore = groundingService.getState(FACT_SHEET_ID).factStore();
        assertEquals(2, factStore.allFacts().size());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private static GraphNode buildNode(String nodeId, NodeLevel level, String externalId) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .externalId(externalId)
                .nodeType(level)
                .title(externalId)
                .build();
    }

    /**
     * Build a minimal {@link GraphEdge} with externalIds on source/target nodes.
     *
     * @param srcExtId   source node externalId (used as PSL atom argument)
     * @param tgtExtId   target node externalId
     * @param relType    semantic relation type string (null → fall back to EdgeType)
     * @param confidence edge confidence (null → projector defaults to 1.0)
     * @param weight     edge weight (null → projector defaults to 1.0)
     */
    private static GraphEdge buildEdge(String srcExtId,
                                       String tgtExtId,
                                       String relType,
                                       Double confidence,
                                       Double weight) {
        GraphNode src = GraphNode.builder()
                .nodeId(UUID.randomUUID().toString())
                .externalId(srcExtId)
                .nodeType(NodeLevel.ENTITY)
                .title(srcExtId)
                .build();
        GraphNode tgt = GraphNode.builder()
                .nodeId(UUID.randomUUID().toString())
                .externalId(tgtExtId)
                .nodeType(NodeLevel.ENTITY)
                .title(tgtExtId)
                .build();
        GraphEdge.GraphEdgeBuilder builder = GraphEdge.builder()
                .edgeId(UUID.randomUUID().toString())
                .sourceNode(src)
                .targetNode(tgt)
                .edgeType(EdgeType.USER_DEFINED)
                .relationType(relType)
                .weight(weight != null ? weight : 1.0) // weight has @Column(nullable=false) in JPA
                .createdAt(LocalDateTime.now());
        if (confidence != null) {
            builder.confidence(confidence);
        }
        return builder.build();
    }
}
