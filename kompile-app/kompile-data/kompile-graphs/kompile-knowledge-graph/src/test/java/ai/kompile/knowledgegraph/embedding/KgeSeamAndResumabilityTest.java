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
package ai.kompile.knowledgegraph.embedding;

import ai.kompile.core.kgembedding.KGEmbeddingConfig;
import ai.kompile.core.kgembedding.TrainingResult;
import ai.kompile.core.kgembedding.Triple;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.embedding.adapter.MatrixKgEmbeddingGraphAdapter;
import ai.kompile.knowledgegraph.embedding.impl.TransEModel;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real unit tests for the KGE (LEARNING) pipeline phase covering:
 * <ol>
 *   <li>Seam contract: {@link MatrixKgEmbeddingGraphAdapter} uses {@link KnowledgeGraphService}
 *       exclusively when wired — never falls back to {@code store.listGraphsByFactSheet}</li>
 *   <li>Core KGE behaviour: TransE trains on non-empty triples, guards empty-triple case</li>
 *   <li>Resumability / warm-start: {@link TransEModel} seeded from prior embeddings preserves
 *       the imported values rather than cold-reinitialising from random</li>
 * </ol>
 *
 * <p>No Spring context, no subprocess, no ND4J CUDA backend required.
 * Tensors are tiny (dim=4, batch ≤ 2 triples) to keep memory bounded.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KgeSeamAndResumabilityTest {

    private static final Long FACT_SHEET_ID = 7L;

    // ── KGEmbeddingConfig that is safe with a single triple and dim=4 ──────────
    private static final KGEmbeddingConfig TINY_CONFIG = KGEmbeddingConfig.builder()
            .embeddingDim(4)
            .epochs(2)
            .learningRate(0.01)
            .batchSize(4)
            .margin(1.0)
            .negativeSamples(1)
            .normalizeEntities(true)
            .build();

    // Zero-lr config: gradient steps are no-ops; used to prove warm-start seeding
    private static final KGEmbeddingConfig ZERO_LR_CONFIG = KGEmbeddingConfig.builder()
            .embeddingDim(4)
            .epochs(1)
            .learningRate(0.0)
            .batchSize(8)
            .margin(1.0)
            .negativeSamples(1)
            .normalizeEntities(true)
            .build();

    private static final List<Triple> TWO_ENTITY_TRIPLES = Arrays.asList(
            new Triple("Alice", "KNOWS", "Bob"),
            new Triple("Bob", "LIKES", "Alice")
    );

    // AdjacencyMatrixGraph that must be closed after tests using the store fallback path
    private AdjacencyMatrixGraph openGraph;

    @BeforeEach
    void setUp() {
        openGraph = null;
    }

    @AfterEach
    void tearDown() {
        if (openGraph != null) {
            openGraph.close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SEAM TESTS: KnowledgeGraphService takes priority over store.listGraphsByFactSheet
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * When {@link KnowledgeGraphService} is wired and returns a non-empty edge list,
     * {@link MatrixKgEmbeddingGraphAdapter#hasGraphData} returns {@code true} and
     * NEVER calls {@code store.listGraphsByFactSheet} (the broken subprocess RPC path).
     *
     * <p>This validates the [FIX-2] change that stopped the "[subprocess-graph] transport error"
     * that caused KGE jobs to fail with status=FAILED even when edges exist.</p>
     */
    @Test
    void hasGraphData_withSeam_usesSeamExclusively_neverCallsStore() throws Exception {
        MatrixGraphStore mockStore = mock(MatrixGraphStore.class);
        KnowledgeGraphService mockSeam = mock(KnowledgeGraphService.class);

        GraphEdge edge = buildEdge("n1", "n2");
        when(mockSeam.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(edge));

        MatrixKgEmbeddingGraphAdapter adapter = adapterWithSeam(mockStore, mockSeam);

        boolean result = adapter.hasGraphData(FACT_SHEET_ID);

        assertTrue(result, "Seam returned 1 edge — should have graph data");
        verify(mockSeam).getEdgesInFactSheet(FACT_SHEET_ID);
        // Store MUST NOT be called: in subprocess mode this RPC throws a transport NPE
        verify(mockStore, never()).listGraphsByFactSheet(anyLong());
    }

    /**
     * When seam returns empty edges list, {@code hasGraphData} returns {@code false} and
     * still does NOT fall through to {@code store.listGraphsByFactSheet}.
     */
    @Test
    void hasGraphData_withSeam_emptyEdges_returnsFalseNeverCallsStore() throws Exception {
        MatrixGraphStore mockStore = mock(MatrixGraphStore.class);
        KnowledgeGraphService mockSeam = mock(KnowledgeGraphService.class);

        when(mockSeam.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(Collections.emptyList());

        MatrixKgEmbeddingGraphAdapter adapter = adapterWithSeam(mockStore, mockSeam);

        assertFalse(adapter.hasGraphData(FACT_SHEET_ID));
        verify(mockStore, never()).listGraphsByFactSheet(anyLong());
    }

    /**
     * When seam is null (non-Spring / in-process context), {@code hasGraphData} falls back to the
     * store path. Verifies the store fallback is still alive when the seam is absent.
     */
    @Test
    void hasGraphData_withoutSeam_fallsBackToStore() {
        MatrixGraphStore mockStore = mock(MatrixGraphStore.class);
        // No seam injected — seam is null (store-only path)
        MatrixKgEmbeddingGraphAdapter adapter = new MatrixKgEmbeddingGraphAdapter(mockStore);

        openGraph = new AdjacencyMatrixGraph("g1", 8);
        openGraph.addNode(MatrixGraphNode.builder().nodeId("a").nodeType("CONCEPT").title("a").build());
        openGraph.addNode(MatrixGraphNode.builder().nodeId("b").nodeType("CONCEPT").title("b").build());
        openGraph.addEdge("a", "b", 1.0, "RELATED_TO", false);

        when(mockStore.listGraphsByFactSheet(FACT_SHEET_ID)).thenReturn(List.of("g1"));
        when(mockStore.loadGraph("g1")).thenReturn(Optional.of(openGraph));

        assertTrue(adapter.hasGraphData(FACT_SHEET_ID), "Store fallback should detect the edge");
    }

    /**
     * When seam is wired, {@code extractTriples} returns triples built from the seam's edges and
     * NEVER calls {@code store.listGraphsByFactSheet}.
     */
    @Test
    void extractTriples_withSeam_usesSeamExclusively() throws Exception {
        MatrixGraphStore mockStore = mock(MatrixGraphStore.class);
        KnowledgeGraphService mockSeam = mock(KnowledgeGraphService.class);

        GraphEdge edge = buildEdge("node-alice", "node-bob");
        edge.setRelationType("KNOWS");
        when(mockSeam.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(edge));

        MatrixKgEmbeddingGraphAdapter adapter = adapterWithSeam(mockStore, mockSeam);

        List<Triple> triples = adapter.extractTriples(FACT_SHEET_ID);

        assertFalse(triples.isEmpty(), "Should extract at least 1 triple from the seam edge");
        Triple t = triples.get(0);
        assertEquals("node-alice", t.head());
        assertEquals("KNOWS", t.relation());
        assertEquals("node-bob", t.tail());
        verify(mockStore, never()).listGraphsByFactSheet(anyLong());
    }

    /**
     * When seam is wired but returns an empty list, extractTriples returns empty immediately
     * without calling {@code store.listGraphsByFactSheet}.
     */
    @Test
    void extractTriples_withSeam_emptyEdges_returnsEmptyNeverCallsStore() throws Exception {
        MatrixGraphStore mockStore = mock(MatrixGraphStore.class);
        KnowledgeGraphService mockSeam = mock(KnowledgeGraphService.class);

        when(mockSeam.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(Collections.emptyList());

        MatrixKgEmbeddingGraphAdapter adapter = adapterWithSeam(mockStore, mockSeam);

        List<Triple> triples = adapter.extractTriples(FACT_SHEET_ID);

        assertTrue(triples.isEmpty());
        verify(mockStore, never()).listGraphsByFactSheet(anyLong());
    }

    /**
     * When the edge has no relationType, extractTriples falls back to
     * {@code edge.getEdgeType().name()} as the relation string.
     */
    @Test
    void extractTriples_withSeam_fallsBackToEdgeTypeName_whenRelationTypeBlank() throws Exception {
        MatrixGraphStore mockStore = mock(MatrixGraphStore.class);
        KnowledgeGraphService mockSeam = mock(KnowledgeGraphService.class);

        GraphEdge edge = buildEdge("src", "tgt");
        edge.setRelationType(null); // no semantic relationType
        when(mockSeam.getEdgesInFactSheet(FACT_SHEET_ID)).thenReturn(List.of(edge));

        MatrixKgEmbeddingGraphAdapter adapter = adapterWithSeam(mockStore, mockSeam);

        List<Triple> triples = adapter.extractTriples(FACT_SHEET_ID);

        assertEquals(1, triples.size());
        assertEquals(EdgeType.USER_DEFINED.name(), triples.get(0).relation());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CORE KGE BEHAVIOUR: TransE training on triples
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * TransE trains on a non-empty triple list, returns SUCCESS, and produces the correct
     * number of entity and relation embeddings.
     */
    @Test
    void kge_trainsOnTriples_producesEmbeddings() {
        TransEModel model = new TransEModel();

        TrainingResult result = model.train(TWO_ENTITY_TRIPLES, TINY_CONFIG);

        assertTrue(result.success(), "Training should succeed. Error: " + result.errorMessage());
        assertTrue(model.isTrained());
        assertEquals(2, model.getEntityCount(), "Two distinct entities: Alice, Bob");
        assertEquals(2, model.getRelationCount(), "Two distinct relations: KNOWS, LIKES");

        // All known entities have non-null embeddings of the correct dimension
        for (String entityId : model.getEntityIds()) {
            INDArray emb = model.getEntityEmbedding(entityId);
            assertNotNull(emb, "Embedding for " + entityId + " must not be null");
            assertEquals(4, emb.columns(), "Embedding dimension must match config");
        }
    }

    /**
     * TransE returns FAILURE immediately when the triple list is empty — the "No triples found"
     * guard path that KGEmbeddingJobService uses to mark the job FAILED with a helpful message.
     */
    @Test
    void kge_emptyTriples_returnsFailureWithMessage() {
        TransEModel model = new TransEModel();

        TrainingResult result = model.train(Collections.emptyList(), TINY_CONFIG);

        assertFalse(result.success(), "Empty triples must not succeed");
        assertNotNull(result.errorMessage(), "Failure must carry an error message");
        assertFalse(result.errorMessage().isBlank());
    }

    /**
     * TransE returns FAILURE for a null triple list.
     */
    @Test
    void kge_nullTriples_returnsFailure() {
        TransEModel model = new TransEModel();

        TrainingResult result = model.train(null, TINY_CONFIG);

        assertFalse(result.success());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESUMABILITY: warm-start seeds from prior embeddings, not cold random init
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * KGE resumability / warm-start contract.
     *
     * <p>When prior embeddings are loaded via {@code importEntityEmbeddings} before {@code train},
     * TransE seeds known entities from the prior matrix instead of re-initialising from random.
     * This is the "resume training from prior embeddings" behaviour required by the crawl pipeline.</p>
     *
     * <p>Test approach (deterministic, no random-seed control needed):
     * <ol>
     *   <li>Import a known, unit-norm vector {@code [0.5, 0.5, 0.5, 0.5]} for each entity.</li>
     *   <li>Train one epoch with {@code learningRate=0} so gradient steps are no-ops.</li>
     *   <li>Assert that the entity embeddings after training equal the imported seed (the
     *       [0.5,0.5,0.5,0.5] vector is its own unit-norm, so the normalization pass leaves it
     *       unchanged, and zero-lr means zero gradient updates).</li>
     * </ol>
     *
     * <p>A cold-started model with random init would produce a different (random) unit vector
     * for each entity — proving that warm-start copied the prior rows and did not random-init.</p>
     */
    @Test
    void kge_warmStart_seedsFromPriorEmbeddings() {
        // ── dim=4 unit-norm seed: ||[0.5, 0.5, 0.5, 0.5]|| == 1.0 (normalization is a no-op)
        float[] seedValues = {0.5f, 0.5f, 0.5f, 0.5f};
        INDArray aliceSeed = Nd4j.create(seedValues).reshape(1, 4);
        INDArray bobSeed   = Nd4j.create(seedValues).reshape(1, 4);

        Map<String, INDArray> priorEmbeddings = new HashMap<>();
        priorEmbeddings.put("Alice", aliceSeed);
        priorEmbeddings.put("Bob",   bobSeed);

        TransEModel warmModel = new TransEModel();
        warmModel.importEntityEmbeddings(priorEmbeddings);

        // Verify the import succeeded before training
        assertNotNull(warmModel.getEntityEmbedding("Alice"),
                "importEntityEmbeddings must make entities accessible before train()");
        assertEquals(4, warmModel.getEmbeddingDimension(),
                "embeddingDim must be inferred from the imported vectors");

        // Train one epoch with lr=0 (no gradient updates) — warm-start must copy the seed row
        TrainingResult result = warmModel.train(TWO_ENTITY_TRIPLES, ZERO_LR_CONFIG);
        assertTrue(result.success(), "Warm-start training must succeed. Error: " + result.errorMessage());

        // ── Key assertion: Alice's embedding must match the imported seed ─────────
        // Cold-start would produce a random unit vector ≠ [0.5, 0.5, 0.5, 0.5].
        // Warm-start copies the prior row → normalizeRowsInPlace leaves it unchanged → lr=0 no-op.
        INDArray aliceAfter = warmModel.getEntityEmbedding("Alice");
        assertNotNull(aliceAfter, "Alice must be present in the warm-started model");
        assertArrayEquals(seedValues, aliceAfter.toFloatVector(), 1e-5f,
                "Warm-started Alice embedding must match the imported seed (lr=0, unit-norm seed)");

        INDArray bobAfter = warmModel.getEntityEmbedding("Bob");
        assertNotNull(bobAfter, "Bob must be present in the warm-started model");
        assertArrayEquals(seedValues, bobAfter.toFloatVector(), 1e-5f,
                "Warm-started Bob embedding must match the imported seed");
    }

    /**
     * Cold-start control test: without prior embeddings the trained embeddings are random (NOT the
     * unit-norm seed [0.5, 0.5, 0.5, 0.5]), demonstrating that the warm-start test above is
     * non-trivially asserting the seeding path.
     */
    @Test
    void kge_coldStart_producesRandomEmbedding_notTheSeed() {
        float[] seed = {0.5f, 0.5f, 0.5f, 0.5f};

        TransEModel coldModel = new TransEModel();
        // No importEntityEmbeddings — pure cold start
        TrainingResult result = coldModel.train(TWO_ENTITY_TRIPLES, ZERO_LR_CONFIG);
        assertTrue(result.success(), "Cold-start training must succeed");

        INDArray aliceAfter = coldModel.getEntityEmbedding("Alice");
        assertNotNull(aliceAfter);
        // Cold-start embeddings are randomly initialised; with overwhelming probability they
        // will not equal the specific seed vector [0.5, 0.5, 0.5, 0.5].
        // (The probability of a random unit-sphere vector equalling [0.5,0.5,0.5,0.5] to 5 dp
        // in a 4-dim space is astronomically small — effectively zero.)
        float[] coldValues = aliceAfter.toFloatVector();
        boolean matchesSeed = Arrays.equals(
                roundToDecimals(coldValues, 5),
                roundToDecimals(seed, 5));
        assertFalse(matchesSeed,
                "Cold-started Alice embedding must NOT match the warm-start seed (confirms the warm-start test is testing a real difference)");
    }

    /**
     * KGE warm-start with a dimension mismatch (prior dim ≠ config dim) degrades to cold-start
     * gracefully rather than crashing. The training must still SUCCEED.
     */
    @Test
    void kge_warmStart_dimMismatch_degradesToColdStartGracefully() {
        // Import dim=4 embeddings
        Map<String, INDArray> prior = Map.of(
                "Alice", Nd4j.create(new float[]{0.5f, 0.5f, 0.5f, 0.5f}).reshape(1, 4),
                "Bob",   Nd4j.create(new float[]{0.5f, 0.5f, 0.5f, 0.5f}).reshape(1, 4)
        );

        TransEModel model = new TransEModel();
        model.importEntityEmbeddings(prior);

        // Train with a DIFFERENT dim — forces cold-start fallback
        KGEmbeddingConfig differentDimConfig = KGEmbeddingConfig.builder()
                .embeddingDim(8)  // mismatch: prior was dim=4
                .epochs(1)
                .learningRate(0.01)
                .batchSize(4)
                .margin(1.0)
                .negativeSamples(1)
                .normalizeEntities(true)
                .build();

        TrainingResult result = model.train(TWO_ENTITY_TRIPLES, differentDimConfig);

        assertTrue(result.success(), "Dim mismatch warm-start must degrade to cold-start and still succeed");
        assertEquals(8, model.getEmbeddingDimension(), "Model must use the new config dimension");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /** Inject the KnowledgeGraphService seam into a fresh adapter via reflection. */
    private static MatrixKgEmbeddingGraphAdapter adapterWithSeam(
            MatrixGraphStore store, KnowledgeGraphService seam) throws Exception {
        MatrixKgEmbeddingGraphAdapter adapter = new MatrixKgEmbeddingGraphAdapter(store);
        Field f = MatrixKgEmbeddingGraphAdapter.class.getDeclaredField("knowledgeGraphService");
        f.setAccessible(true);
        f.set(adapter, seam);
        return adapter;
    }

    /** Build a minimal GraphEdge with valid source and target nodes for seam tests. */
    private static GraphEdge buildEdge(String srcNodeId, String tgtNodeId) {
        GraphNode src = GraphNode.builder()
                .nodeId(srcNodeId)
                .externalId(srcNodeId)
                .nodeType(NodeLevel.ENTITY)
                .title(srcNodeId)
                .build();
        GraphNode tgt = GraphNode.builder()
                .nodeId(tgtNodeId)
                .externalId(tgtNodeId)
                .nodeType(NodeLevel.ENTITY)
                .title(tgtNodeId)
                .build();
        return GraphEdge.builder()
                .edgeId(java.util.UUID.randomUUID().toString())
                .sourceNode(src)
                .targetNode(tgt)
                .edgeType(EdgeType.USER_DEFINED)
                .weight(1.0)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /** Round float[] values to {@code decimals} decimal places for comparison. */
    private static float[] roundToDecimals(float[] values, int decimals) {
        double scale = Math.pow(10, decimals);
        float[] rounded = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            rounded[i] = (float) (Math.round(values[i] * scale) / scale);
        }
        return rounded;
    }
}
