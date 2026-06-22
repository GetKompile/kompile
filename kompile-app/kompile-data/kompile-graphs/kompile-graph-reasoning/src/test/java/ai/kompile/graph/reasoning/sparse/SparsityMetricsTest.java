/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.sparse;

import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SparsityMetrics}.
 *
 * <p>Two archetypal graph shapes are verified:</p>
 * <ul>
 *   <li>A near-complete (dense) graph — should NOT be classified sparse or bipartite.</li>
 *   <li>A bipartite star graph (rows → columns) — should be sparse and bipartite.</li>
 * </ul>
 */
@DisplayName("SparsityMetrics tests")
class SparsityMetricsTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Build a complete directed graph on {@code n} nodes (every ordered pair has an edge).
     * Density = 1.0 by construction.
     */
    private MutableReasoningGraph completeDirectedGraph(int n) {
        MutableReasoningGraph g = new MutableReasoningGraph();
        for (int i = 0; i < n; i++) {
            g.addEntity("n" + i, "NODE", "Node " + i);
        }
        int edgeId = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    g.addRelation("e" + edgeId++, "n" + i, "n" + j, "KNOWS", 1.0);
                }
            }
        }
        return g;
    }

    /**
     * Build a bipartite star: {@code rows} row-nodes all connected to the same
     * {@code cols} column-nodes (row → column). No edges within the same partition.
     */
    private MutableReasoningGraph bipartiteStarGraph(int rows, int cols) {
        MutableReasoningGraph g = new MutableReasoningGraph();
        for (int r = 0; r < rows; r++) {
            g.addEntity("row" + r, "ROW", "Row " + r);
        }
        for (int c = 0; c < cols; c++) {
            g.addEntity("col" + c, "COLUMN", "Col " + c);
        }
        int edgeId = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                g.addRelation("e" + edgeId++, "row" + r, "col" + c, "HAS_COLUMN", 1.0);
            }
        }
        return g;
    }

    // ── Empty / degenerate graphs ─────────────────────────────────────────────

    @Nested
    @DisplayName("Degenerate graphs")
    class DegenerateGraphs {

        @Test
        @DisplayName("Empty graph: density 0, sparse=true, bipartite=false (no nodes)")
        void emptyGraph() {
            MutableReasoningGraph g = new MutableReasoningGraph();
            SparsityMetrics m = SparsityMetrics.compute(g);
            assertEquals(0, m.nodeCount);
            assertEquals(0, m.edgeCount);
            assertEquals(0.0, m.density, 1e-12);
            assertTrue(m.isLikelySparse(), "empty graph should be sparse");
            // 0 nodes: bipartite heuristic returns false (n < 2)
            assertFalse(m.isLikelyBipartite(), "empty graph is not bipartite (no nodes)");
        }

        @Test
        @DisplayName("Single node: density 0, sparse=true")
        void singleNode() {
            MutableReasoningGraph g = new MutableReasoningGraph();
            g.addEntity("n0", "NODE", "Only");
            SparsityMetrics m = SparsityMetrics.compute(g);
            assertEquals(1, m.nodeCount);
            assertEquals(0, m.edgeCount);
            assertEquals(0.0, m.density, 1e-12);
            assertTrue(m.isLikelySparse());
        }

        @Test
        @DisplayName("Two nodes, no edges: density 0, bipartite=true")
        void twoNodesNoEdges() {
            MutableReasoningGraph g = new MutableReasoningGraph();
            g.addEntity("a", "NODE", "A");
            g.addEntity("b", "NODE", "B");
            SparsityMetrics m = SparsityMetrics.compute(g);
            assertEquals(0.0, m.density, 1e-12);
            assertTrue(m.isLikelySparse());
            assertTrue(m.isLikelyBipartite(), "two disconnected nodes trivially 2-colorable");
        }

        @Test
        @DisplayName("Null graph throws")
        void nullThrows() {
            assertThrows(IllegalArgumentException.class, () -> SparsityMetrics.compute(null));
        }
    }

    // ── Dense / complete graphs ───────────────────────────────────────────────

    @Nested
    @DisplayName("Dense complete graph")
    class DenseGraphTests {

        @Test
        @DisplayName("Complete 5-node graph: density 1.0, NOT sparse, NOT bipartite")
        void completeFiveNodes() {
            MutableReasoningGraph g = completeDirectedGraph(5);
            SparsityMetrics m = SparsityMetrics.compute(g);

            assertEquals(5, m.nodeCount);
            assertEquals(20, m.edgeCount); // 5*4 = 20
            assertEquals(1.0, m.density, 1e-9, "complete graph density must be 1.0");

            assertFalse(m.isLikelySparse(),
                "complete graph density=1.0 should NOT be classified as sparse");
            assertFalse(m.isLikelyBipartite(),
                "complete directed graph has many same-color edge violations");
        }

        @Test
        @DisplayName("Complete 4-node graph: mean degree = 6")
        void completeFourNodesDegrees() {
            MutableReasoningGraph g = completeDirectedGraph(4);
            SparsityMetrics m = SparsityMetrics.compute(g);
            // Each node has 3 outgoing + 3 incoming = degree 6
            assertEquals(6.0, m.meanDegree, 1e-9);
            assertEquals(6.0, m.medianDegree, 1e-9);
        }
    }

    // ── Sparse bipartite graphs ───────────────────────────────────────────────

    @Nested
    @DisplayName("Sparse bipartite graph (row/column/value)")
    class BipartiteGraphTests {

        @Test
        @DisplayName("10 rows x 3 cols: sparse and bipartite")
        void tenRowsThreeCols() {
            // 13 nodes, 30 edges; N*(N-1) = 156 → density ≈ 0.192 ... wait let's check
            // Actually: N=13, E=30, density = 30 / (13*12) = 30/156 ≈ 0.192
            // That's > 0.10. Let's use more rows to push it down.
            // 100 rows, 3 cols → N=103, E=300, density = 300/(103*102) ≈ 0.028 < 0.10
            MutableReasoningGraph g = bipartiteStarGraph(100, 3);
            SparsityMetrics m = SparsityMetrics.compute(g);

            assertEquals(103, m.nodeCount);
            assertEquals(300, m.edgeCount);
            assertTrue(m.density < SparsityMetrics.SPARSE_THRESHOLD,
                "expected sparse: density=" + m.density);
            assertTrue(m.isLikelySparse(), "100-row x 3-col graph should be sparse");
            assertTrue(m.isLikelyBipartite(), "strict bipartite graph should be detected");
        }

        @Test
        @DisplayName("5 rows x 4 cols: bipartite even if not super sparse")
        void fiveRowsFourCols() {
            // 9 nodes, 20 edges, density = 20/(9*8) = 20/72 ≈ 0.278 — above threshold
            // but the graph IS bipartite
            MutableReasoningGraph g = bipartiteStarGraph(5, 4);
            SparsityMetrics m = SparsityMetrics.compute(g);
            assertTrue(m.isLikelyBipartite(),
                "perfect bipartite graph must be detected regardless of density");
        }

        @Test
        @DisplayName("Sparse bipartite: column nodes have higher degree than row nodes")
        void columnNodesHigherDegree() {
            // 50 rows, 2 cols → col nodes each have degree = 50, row nodes have degree = 2
            MutableReasoningGraph g = bipartiteStarGraph(50, 2);
            SparsityMetrics m = SparsityMetrics.compute(g);
            // mean > median because 2 high-degree column nodes pull mean up
            assertTrue(m.meanDegree > m.medianDegree,
                "hub-and-spoke bipartite: mean degree should exceed median; mean="
                + m.meanDegree + " median=" + m.medianDegree);
        }

        @Test
        @DisplayName("Near-bipartite: one violation edge still passes tolerance")
        void nearBipartiteWithOneViolation() {
            // Build bipartite graph, then add one same-partition edge (row0 → row1)
            MutableReasoningGraph g = bipartiteStarGraph(100, 3);
            // row0 → row1 is a same-partition edge: a violation
            g.addRelation("violation_edge", "row0", "row1", "SAME_ROW", 0.5);

            SparsityMetrics m = SparsityMetrics.compute(g);
            // 301 edges, 1 violation → fraction = 1/301 ≈ 0.0033 < 0.05 tolerance
            assertTrue(m.isLikelyBipartite(),
                "one violation out of 300 edges should still pass the tolerance");
        }

        @Test
        @DisplayName("Many violations: NOT bipartite")
        void manyViolationsNotBipartite() {
            // Start with a small bipartite base and add many cross-edges within partition
            MutableReasoningGraph g = bipartiteStarGraph(5, 3);
            // Add edges between all row pairs: C(5,2)=10 violations on 15 base edges → fraction > 0.05
            int violId = 0;
            for (int i = 0; i < 5; i++) {
                for (int j = i + 1; j < 5; j++) {
                    g.addRelation("viol" + violId++, "row" + i, "row" + j, "SAME_TYPE", 0.5);
                }
            }
            SparsityMetrics m = SparsityMetrics.compute(g);
            assertFalse(m.isLikelyBipartite(),
                "graph with many same-partition edges should NOT be bipartite");
        }
    }

    // ── Degree stats ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Degree statistics")
    class DegreeStats {

        @Test
        @DisplayName("Linear chain: mean and median degrees")
        void linearChain() {
            // A → B → C: degree of A=1(out), B=2(in+out), C=1(in)
            MutableReasoningGraph g = new MutableReasoningGraph();
            g.addEntity("A", "NODE", "A");
            g.addEntity("B", "NODE", "B");
            g.addEntity("C", "NODE", "C");
            g.addRelation("e1", "A", "B", "NEXT", 1.0);
            g.addRelation("e2", "B", "C", "NEXT", 1.0);

            SparsityMetrics m = SparsityMetrics.compute(g);
            // degrees: A=1, B=2, C=1 → mean = 4/3 ≈ 1.33, median = 1.0
            assertEquals(3, m.nodeCount);
            assertEquals(2, m.edgeCount);
            assertEquals(4.0 / 3.0, m.meanDegree, 1e-9);
            assertEquals(1.0, m.medianDegree, 1e-9);
        }

        @Test
        @DisplayName("toString includes key fields")
        void toStringContainsFields() {
            MutableReasoningGraph g = bipartiteStarGraph(10, 2);
            SparsityMetrics m = SparsityMetrics.compute(g);
            String s = m.toString();
            assertTrue(s.contains("nodes="), "toString should include nodes");
            assertTrue(s.contains("edges="), "toString should include edges");
            assertTrue(s.contains("density="), "toString should include density");
        }
    }

    // ── Custom thresholds ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Custom threshold overrides")
    class CustomThresholds {

        @Test
        @DisplayName("Very strict sparse threshold: dense graph also classified sparse")
        void veryStrictSparseThreshold() {
            MutableReasoningGraph g = bipartiteStarGraph(5, 4); // density ~0.278
            // Use threshold of 0.5: even 0.278 is sparse relative to it
            SparsityMetrics m = SparsityMetrics.compute(g, 0.5, SparsityMetrics.BIPARTITE_VIOLATION_TOLERANCE);
            assertTrue(m.isLikelySparse(), "density 0.278 < threshold 0.5 → sparse");
        }

        @Test
        @DisplayName("Zero bipartite tolerance: even one violation rejects bipartite")
        void zeroBipartiteTolerance() {
            MutableReasoningGraph g = bipartiteStarGraph(100, 3);
            g.addRelation("viol", "row0", "row1", "SAME", 0.5);
            // With 0.0 tolerance, even 1 violation → not bipartite
            SparsityMetrics m = SparsityMetrics.compute(g, SparsityMetrics.SPARSE_THRESHOLD, 0.0);
            assertFalse(m.isLikelyBipartite(), "zero tolerance: one violation = not bipartite");
        }
    }
}
