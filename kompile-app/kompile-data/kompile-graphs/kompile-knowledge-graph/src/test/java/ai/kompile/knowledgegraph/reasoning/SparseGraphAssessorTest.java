/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.sparse.SparsityMetrics;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SparseGraphAssessor} — the production consumer of the sparse-evidence model.
 * The structural decision is exercised against hand-built {@link MutableReasoningGraph}s (no Spring),
 * proving the open-world (sparse → vacuous) vs closed-world (dense → disbelief) representation.
 */
class SparseGraphAssessorTest {

    /** Bipartite star (many ROWs → one COLUMN) — structurally sparse, like an Excel/CSV import. */
    private MutableReasoningGraph sparseGraph() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity("col0", "COLUMN", "Col");
        for (int r = 0; r < 12; r++) {
            g.addEntity("row" + r, "ROW", "Row " + r);
            g.addRelation("e" + r, "row" + r, "col0", "HAS_COLUMN", 1.0);
        }
        return g;
    }

    /** Complete directed graph — every node linked to every other. Dense (closed-world applies). */
    private MutableReasoningGraph denseGraph() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        int n = 6, e = 0;
        for (int i = 0; i < n; i++) {
            g.addEntity("n" + i, "NODE", "N" + i);
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    g.addRelation("e" + (e++), "n" + i, "n" + j, "KNOWS", 1.0);
                }
            }
        }
        return g;
    }

    @Test
    void sparseGraph_absentFact_isVacuousOpenWorld() {
        SparsityMetrics m = SparsityMetrics.compute(sparseGraph());
        assertTrue(SparseGraphAssessor.isSparse(m), "bipartite star must be sparse");

        Opinion o = SparseGraphAssessor.absentFactOpinion(m, 0.05);
        // Vacuous: full uncertainty, expectation == base rate — uncertainty, NOT disbelief.
        assertEquals(1.0, o.uncertainty(), 1e-9, "absent-in-sparse must be fully uncertain");
        assertEquals(0.05, o.expectation(), 1e-9, "expectation must equal the supplied base rate");
    }

    @Test
    void denseGraph_absentFact_isClosedWorldDisbelief() {
        SparsityMetrics m = SparsityMetrics.compute(denseGraph());
        assertFalse(SparseGraphAssessor.isSparse(m), "complete graph must be dense");

        Opinion o = SparseGraphAssessor.absentFactOpinion(m, 0.05);
        assertTrue(o.expectation() < 0.1,
                "absent-in-dense must be near-disbelief; got " + o.expectation());
    }

    @Test
    void emptyOrSingletonGraph_isSparse() {
        assertTrue(SparseGraphAssessor.isSparse(SparsityMetrics.compute(new MutableReasoningGraph())),
                "empty graph has no closed world -> sparse");
        MutableReasoningGraph one = new MutableReasoningGraph();
        one.addEntity("only", "NODE", "Only");
        assertTrue(SparseGraphAssessor.isSparse(SparsityMetrics.compute(one)), "singleton -> sparse");
    }

    @Test
    void factSheetPath_emptyGraph_yieldsVacuousAtBaseRate() {
        KnowledgeGraphService kg = mock(KnowledgeGraphService.class);
        when(kg.getNodesInFactSheet(7L)).thenReturn(List.of());
        SparseGraphAssessor assessor = new SparseGraphAssessor(kg);

        assertTrue(assessor.isSparse(7L));
        Opinion o = assessor.absentFactOpinion(7L, 0.2);
        assertEquals(0.2, o.expectation(), 1e-9, "empty fact sheet -> open-world vacuous at base rate");
    }
}
