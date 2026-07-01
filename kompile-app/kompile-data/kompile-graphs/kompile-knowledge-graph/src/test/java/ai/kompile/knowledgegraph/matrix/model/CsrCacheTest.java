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
package ai.kompile.knowledgegraph.matrix.model;

import org.junit.jupiter.api.Test;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ndarray.SparseFormat;
import org.nd4j.linalg.api.ndarray.SparseNDArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Phase-A CSR cache correctness and invalidation tests for {@link AdjacencyMatrixGraph}.
 *
 * <p>Two properties are verified:
 * <ol>
 *   <li><b>Correctness</b> — the cached CSR encodes the same adjacency as a dense
 *       round-trip would produce: {@code sparseCSR.toDense()} matches the expected
 *       [n×n] float matrix.</li>
 *   <li><b>Invalidation</b> — after any edge mutation for a given edge type the next
 *       call to {@link AdjacencyMatrixGraph#getCsrForEdgeType} returns a fresh
 *       {@link SparseNDArray} (different object reference) that reflects the mutation.</li>
 * </ol>
 */
class CsrCacheTest {

    /** Builds a graph with nodes "a", "b", "c" (matrix indices 0, 1, 2). */
    private AdjacencyMatrixGraph threeNodeGraph() {
        AdjacencyMatrixGraph g = new AdjacencyMatrixGraph("test", 8);
        g.addNode(MatrixGraphNode.builder().nodeId("a").nodeType("ENTITY").build());
        g.addNode(MatrixGraphNode.builder().nodeId("b").nodeType("ENTITY").build());
        g.addNode(MatrixGraphNode.builder().nodeId("c").nodeType("ENTITY").build());
        return g;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CORRECTNESS: cached CSR toDense() == expected dense adjacency
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void cachedCsrMatchesExpectedDenseAdjacency() {
        try (AdjacencyMatrixGraph g = threeNodeGraph()) {
            // a→b weight 0.5, b→c weight 1.0 (unidirectional)
            g.addEdge("a", "b", 0.5, "RELATED_TO", false);
            g.addEdge("b", "c", 1.0, "RELATED_TO", false);

            SparseNDArray csr = g.getCsrForEdgeType("RELATED_TO");
            assertNotNull(csr, "getCsrForEdgeType must return a non-null SparseNDArray");
            assertEquals(SparseFormat.CSR, csr.getFormat(),
                    "Returned SparseNDArray must be in CSR format");

            // Shape: [3, 3]
            assertEquals(3L, csr.rows(), "CSR row count must equal nodeCount");
            assertEquals(3L, csr.cols(), "CSR col count must equal nodeCount");

            // Materialise to dense and verify individual entries
            INDArray dense = csr.toDense();   // [3, 3] FLOAT
            try {
                // Expected: a→b=0.5 at [0,1], b→c=1.0 at [1,2], all others=0
                assertEquals(0.5f, dense.getFloat(0, 1), 1e-5f,
                        "a→b edge weight must be 0.5 at [0,1]");
                assertEquals(1.0f, dense.getFloat(1, 2), 1e-5f,
                        "b→c edge weight must be 1.0 at [1,2]");
                assertEquals(0.0f, dense.getFloat(0, 0), 1e-5f, "diagonal must be 0");
                assertEquals(0.0f, dense.getFloat(1, 0), 1e-5f, "reverse b→a must be 0");
                assertEquals(0.0f, dense.getFloat(2, 0), 1e-5f, "c→a must be 0");
            } finally {
                dense.close();
            }
        }
    }

    @Test
    void cacheHitReturnsSameInstance() {
        try (AdjacencyMatrixGraph g = threeNodeGraph()) {
            g.addEdge("a", "b", 1.0, "RELATED_TO", false);

            SparseNDArray first  = g.getCsrForEdgeType("RELATED_TO");
            SparseNDArray second = g.getCsrForEdgeType("RELATED_TO");
            assertSame(first, second,
                    "Repeated getCsrForEdgeType without mutation must return the same cached instance");
        }
    }

    @Test
    void emptyEdgeTypeProducesValidEmptyCsr() {
        try (AdjacencyMatrixGraph g = threeNodeGraph()) {
            // No edges for "HIERARCHICAL" — cache should still return valid empty CSR
            SparseNDArray csr = g.getCsrForEdgeType("HIERARCHICAL");
            assertNotNull(csr);
            assertEquals(SparseFormat.CSR, csr.getFormat());
            assertEquals(0L, csr.nnz(), "Empty edge type must produce zero-nnz CSR");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INVALIDATION: edge mutation evicts the cache
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void addEdgeEvictsCacheAndNewCsrReflectsAddedEdge() {
        try (AdjacencyMatrixGraph g = threeNodeGraph()) {
            // Populate cache
            g.addEdge("a", "b", 0.5, "RELATED_TO", false);
            SparseNDArray before = g.getCsrForEdgeType("RELATED_TO");

            // Mutate — must evict
            g.addEdge("b", "c", 0.8, "RELATED_TO", false);
            SparseNDArray after = g.getCsrForEdgeType("RELATED_TO");

            assertNotSame(before, after,
                    "After addEdge the cache must be evicted (new instance expected)");
            assertEquals(2L, after.nnz(),
                    "CSR after adding second edge must have nnz=2");

            // Verify new edge is present in the dense view
            INDArray dense = after.toDense();
            try {
                assertEquals(0.5f, dense.getFloat(0, 1), 1e-5f, "a→b still 0.5");
                assertEquals(0.8f, dense.getFloat(1, 2), 1e-5f, "new b→c must be 0.8");
            } finally {
                dense.close();
            }
        }
    }

    @Test
    void removeEdgeEvictsCacheAndNewCsrReflectsRemoval() {
        try (AdjacencyMatrixGraph g = threeNodeGraph()) {
            g.addEdge("a", "b", 1.0, "RELATED_TO", false);
            g.addEdge("b", "c", 1.0, "RELATED_TO", false);

            SparseNDArray before = g.getCsrForEdgeType("RELATED_TO");
            assertEquals(2L, before.nnz(), "before: nnz=2");

            // Remove one edge
            g.removeEdge("a", "b", "RELATED_TO");
            SparseNDArray after = g.getCsrForEdgeType("RELATED_TO");

            assertNotSame(before, after,
                    "After removeEdge the cache must be evicted (new instance expected)");
            assertEquals(1L, after.nnz(),
                    "After removal nnz must drop to 1");

            INDArray dense = after.toDense();
            try {
                assertEquals(0.0f, dense.getFloat(0, 1), 1e-5f, "a→b must be gone");
                assertEquals(1.0f, dense.getFloat(1, 2), 1e-5f, "b→c must remain");
            } finally {
                dense.close();
            }
        }
    }

    @Test
    void mutationOfOneTypeDoesNotEvictOtherType() {
        try (AdjacencyMatrixGraph g = threeNodeGraph()) {
            g.addEdge("a", "b", 1.0, "RELATED_TO", false);
            g.addEdge("b", "c", 1.0, "HIERARCHICAL", false);

            // Populate both caches
            SparseNDArray relCsr  = g.getCsrForEdgeType("RELATED_TO");
            SparseNDArray hierCsr = g.getCsrForEdgeType("HIERARCHICAL");

            // Mutate RELATED_TO only
            g.addEdge("a", "c", 0.3, "RELATED_TO", false);

            // RELATED_TO cache must be evicted
            SparseNDArray relCsrAfter = g.getCsrForEdgeType("RELATED_TO");
            assertNotSame(relCsr, relCsrAfter,
                    "RELATED_TO cache must be evicted after mutation");

            // HIERARCHICAL cache must be untouched
            SparseNDArray hierCsrAfter = g.getCsrForEdgeType("HIERARCHICAL");
            assertSame(hierCsr, hierCsrAfter,
                    "HIERARCHICAL cache must be unaffected by mutation of RELATED_TO");
        }
    }

    @Test
    void removeNodeFlushesCacheForAllTypes() {
        try (AdjacencyMatrixGraph g = threeNodeGraph()) {
            g.addEdge("a", "b", 1.0, "RELATED_TO", false);
            g.addEdge("b", "c", 1.0, "HIERARCHICAL", false);

            // Populate both caches
            SparseNDArray relCsr  = g.getCsrForEdgeType("RELATED_TO");
            SparseNDArray hierCsr = g.getCsrForEdgeType("HIERARCHICAL");

            // Remove node "b" — touches both edge types
            g.removeNode("b");

            assertNotSame(relCsr, g.getCsrForEdgeType("RELATED_TO"),
                    "RELATED_TO cache must be flushed after removeNode");
            assertNotSame(hierCsr, g.getCsrForEdgeType("HIERARCHICAL"),
                    "HIERARCHICAL cache must be flushed after removeNode");
        }
    }
}
