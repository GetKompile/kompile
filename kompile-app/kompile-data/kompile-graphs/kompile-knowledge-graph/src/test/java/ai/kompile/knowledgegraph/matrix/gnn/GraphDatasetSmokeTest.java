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
package ai.kompile.knowledgegraph.matrix.gnn;

import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.impl.graph.GraphCentrality;
import org.nd4j.linalg.api.ops.impl.graph.LinkPredictionEvaluation;
import org.nd4j.linalg.api.ops.impl.graph.LouvainCommunityDetection;
import org.nd4j.linalg.factory.Nd4j;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Phase-0 smoke test: proves that the DL4J graph-suite classes present in
 * {@code nd4j-api:1.0.0-SNAPSHOT} link correctly and that
 * {@link GraphToSameDiffDataset} can form a valid dataset from a synthetic
 * {@link AdjacencyMatrixGraph}.
 *
 * <p>No Spring context, no external database. The graph is built in-memory;
 * the {@link MatrixGraphStore} is a Mockito stub that returns it via
 * {@link MatrixGraphStore#loadGraph(String)}.
 *
 * <p>Graph topology (6 nodes, two triangles sharing edge 2-3):
 * <pre>
 *   0 ─── 1          triangle A: {0,1,2}
 *   |   / |          triangle B: {2,3,4}
 *   |  /  |          isolated node: 5
 *   2 ─── 3
 *   |   /
 *   4
 * </pre>
 *
 * <p>Edge types: "RELATED_TO" (structural) and "WORKS_AT" (semantic).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphDatasetSmokeTest {

    private static final long FACT_SHEET_ID = 1L;
    private static final String GRAPH_ID    = "factsheet_" + FACT_SHEET_ID;

    @Mock
    private MatrixGraphStore store;

    private AdjacencyMatrixGraph graph;
    private GraphToSameDiffDataset converter;

    // ─── Node IDs ──────────────────────────────────────────────────────────────
    private static final String[] NODE_IDS = {"n0", "n1", "n2", "n3", "n4", "n5"};

    @BeforeEach
    void buildSyntheticGraph() {
        graph = new AdjacencyMatrixGraph(GRAPH_ID, 16);

        // Add 6 nodes
        for (String id : NODE_IDS) {
            MatrixGraphNode node = MatrixGraphNode.builder()
                    .nodeId(id)
                    .nodeType("ENTITY")
                    .title("Node " + id)
                    .build();
            graph.addNode(node);
        }

        // Triangle A: 0-1-2 (RELATED_TO, bidirectional)
        addBiEdge("n0", "n1", "RELATED_TO");
        addBiEdge("n1", "n2", "RELATED_TO");
        addBiEdge("n0", "n2", "RELATED_TO");

        // Triangle B: 2-3-4 (RELATED_TO, bidirectional)
        addBiEdge("n2", "n3", "RELATED_TO");
        addBiEdge("n3", "n4", "RELATED_TO");
        addBiEdge("n2", "n4", "RELATED_TO");

        // Semantic edges: WORKS_AT (structural edge type; relationType stored in newer store versions)
        graph.addEdge("n0", "n3", 0.8, "WORKS_AT", /*bidirectional=*/false);
        graph.addEdge("n4", "n1", 0.7, "WORKS_AT", /*bidirectional=*/false);

        // n5 is isolated (no edges) — tests that empty-row CSR is handled correctly.

        // Stub the store
        when(store.loadGraph(GRAPH_ID)).thenReturn(Optional.of(graph));

        converter = new GraphToSameDiffDataset(store);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Dataset formation
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void buildProducesCorrectNodeCount() {
        GraphToSameDiffDataset.GraphDataset ds = converter.build(FACT_SHEET_ID, 1);
        assertEquals(6, ds.numNodes);
        assertEquals(GRAPH_ID, ds.graphId);
    }

    @Test
    void featureMatrixIsIdentityFallbackWhenNoEmbeddings() {
        // No embeddings stored → identity [6,6]
        GraphToSameDiffDataset.GraphDataset ds = converter.build(FACT_SHEET_ID, 1);
        assertNotNull(ds.featureMatrix);
        assertEquals(6, ds.featureMatrix.rows());
        assertEquals(6, ds.featureMatrix.columns());
    }

    @Test
    void homoCsrRowPtrHasCorrectShape() {
        GraphToSameDiffDataset.GraphDataset ds = converter.build(FACT_SHEET_ID, 1);
        GraphToSameDiffDataset.CsrArrays csr = ds.homoCsr;

        // rowPtr must have length n+1
        assertEquals(7, csr.rowPtr.length());
        // colIdx and values must be parallel
        assertEquals(csr.colIdx.length(), csr.values.length());
        // We have 6 bidirectional RELATED_TO + 2 directed WORKS_AT edges
        //   bidirectional: 6 pairs → 12 directed  +  directed: 2 → 14 total
        assertTrue(csr.numEdges() > 0, "CSR should have at least one edge");
    }

    @Test
    void perRelationCsrContainsBothEdgeTypes() {
        GraphToSameDiffDataset.GraphDataset ds = converter.build(FACT_SHEET_ID, 1);
        assertTrue(ds.relationCsr.containsKey("RELATED_TO"), "Should have RELATED_TO CSR");
        assertTrue(ds.relationCsr.containsKey("WORKS_AT"),   "Should have WORKS_AT CSR");
    }

    @Test
    void kgeDataHasTriples() {
        GraphToSameDiffDataset.GraphDataset ds = converter.build(FACT_SHEET_ID, 1);
        GraphToSameDiffDataset.KgeData kge = ds.kgeData;

        assertTrue(kge.numTriples() > 0, "KGE should have at least one triple");
        assertEquals(3, kge.triples.columns(),
                "Triples tensor must have 3 columns: (srcIdx, relTypeIdx, tgtIdx)");
        assertNotNull(kge.relationVocab);
        assertTrue(kge.numRelations() >= 1, "At least one relation type expected");
    }

    @Test
    void trainValSplitSizesAreConsistent() {
        GraphToSameDiffDataset.GraphDataset ds = converter.build(FACT_SHEET_ID, 1);
        GraphToSameDiffDataset.KgeData kge = ds.kgeData;

        long train = kge.trainEdges.size(0);
        long val   = kge.valEdges.size(0);
        long test  = kge.testEdges.size(0);
        long total = kge.numTriples();

        assertEquals(total, train + val + test,
                "Train + val + test must equal total edge count");
        assertTrue(train >= val, "Train set should not be smaller than validation set");
    }

    @Test
    void negativeSamplesHaveCorrectShape() {
        GraphToSameDiffDataset.GraphDataset ds = converter.build(FACT_SHEET_ID, 1);
        INDArray neg = ds.kgeData.negEdges;
        assertNotNull(neg);
        assertEquals(2, neg.rank(), "Negative edges must be [K, 2]");
        assertEquals(2, neg.columns());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // DL4J graph-suite algorithm smoke runs (prove classes link + execute)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void louvainCommunityDetectionRunsOnCombinedAdjacency() {
        // Use the combined dense matrix for the algorithm (the CSR → dense on demand)
        INDArray adj = graph.getCombinedAdjacencyMatrix();
        try {
            LouvainCommunityDetection.Result result = LouvainCommunityDetection.detect(adj);

            assertNotNull(result);
            assertNotNull(result.communities);
            assertEquals(graph.getNodeCount(), result.communities.length,
                    "Community array must have one entry per node");
            // With two cliques, Louvain should find at least 1 community and modularity ≥ 0
            assertTrue(result.modularity >= 0.0,
                    "Modularity should be non-negative for clique-heavy graphs");
            // At least 1, at most n communities
            long numDistinctComm = java.util.Arrays.stream(result.communities)
                    .distinct().count();
            assertTrue(numDistinctComm >= 1 && numDistinctComm <= graph.getNodeCount(),
                    "Community count must be in [1, n]");
        } finally {
            adj.close();
        }
    }

    @Test
    void graphCentralityClosenessBetweennessRun() {
        INDArray adj = graph.getCombinedAdjacencyMatrix();
        try {
            INDArray closeness   = GraphCentrality.closeness(adj);
            INDArray betweenness = GraphCentrality.betweenness(adj);

            assertNotNull(closeness);
            assertNotNull(betweenness);
            assertEquals(graph.getNodeCount(), closeness.length(),
                    "Closeness must have one value per node");
            assertEquals(graph.getNodeCount(), betweenness.length(),
                    "Betweenness must have one value per node");

            // node 5 is isolated → closeness[5] == 0
            assertEquals(0.0, closeness.getDouble(5), 1e-9,
                    "Isolated node must have closeness = 0");
            // All betweenness values must be >= 0
            for (int i = 0; i < graph.getNodeCount(); i++) {
                assertTrue(betweenness.getDouble(i) >= 0,
                        "Betweenness must be non-negative at node " + i);
            }
        } finally {
            adj.close();
        }
    }

    @Test
    void linkPredictionEvaluationRunsOnSyntheticScores() {
        // Build a synthetic pair of positive (train) and negative edges from the dataset
        GraphToSameDiffDataset.GraphDataset ds = converter.build(FACT_SHEET_ID, 1);
        GraphToSameDiffDataset.KgeData kge = ds.kgeData;

        long posCount = kge.trainEdges.size(0);
        long negCount = kge.negEdges.size(0);
        int total = (int) (posCount + negCount);

        if (total == 0) {
            // Guard: degenerate graph (shouldn't happen with our 6-node fixture)
            return;
        }

        // Synthesise scores: positives get score 1.0, negatives get 0.0
        float[] scoreArr = new float[total];
        float[] labelArr = new float[total];
        for (int i = 0; i < (int) posCount; i++) {
            scoreArr[i] = 1.0f;
            labelArr[i] = 1.0f;
        }
        // negatives: scores 0, labels 0 (already default 0 from array init)

        INDArray scores = Nd4j.create(scoreArr, new long[]{total});
        INDArray labels = Nd4j.create(labelArr, new long[]{total});

        LinkPredictionEvaluation.Result result = LinkPredictionEvaluation.eval(scores, labels);

        assertNotNull(result);
        // With perfect scores (all positives ranked before all negatives), AUC-ROC = 1.0
        assertEquals(1.0, result.getAucRoc(), 1e-9,
                "AUC-ROC must be 1.0 when positives are scored above all negatives");
        // MRR should equal 1.0 (positives ranked 1..posCount, all have rank ≤ posCount)
        assertTrue(result.getMrr() > 0, "MRR must be positive");
        // Hits@posCount should be 1.0
        assertEquals(1.0, result.hitsAtK((int) posCount), 1e-9,
                "Every positive should be in top-" + posCount);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────────────

    /** Add a bidirectional structural edge. */
    private void addBiEdge(String src, String tgt, String edgeType) {
        graph.addEdge(src, tgt, 1.0, edgeType, /*bidirectional=*/true);
    }
}
