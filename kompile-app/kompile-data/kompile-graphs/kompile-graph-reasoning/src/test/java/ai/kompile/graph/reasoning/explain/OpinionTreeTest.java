/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.explain;

import ai.kompile.graph.reasoning.confidence.Opinion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5a OpinionTree: the operator tree computes the root opinion bottom-up with the correct subjective-
 * logic operator per node — and is itself the trace.
 */
@DisplayName("WP5a OpinionTree")
class OpinionTreeTest {

    private static final double EPS = 1e-9;
    private static final Opinion X = new Opinion(0.6, 0.2, 0.2, 0.5); // E = 0.70
    private static final Opinion Y = new Opinion(0.5, 0.3, 0.2, 0.4); // E = 0.58

    @Test
    void leafReturnsItsOpinion() {
        assertEquals(0.70, OpinionTree.leaf("x", X).value().expectation(), EPS);
    }

    @Test
    void conjoinMultipliesExpectations() {
        OpinionTree t = OpinionTree.conjoin("and", List.of(OpinionTree.leaf("x", X), OpinionTree.leaf("y", Y)));
        assertEquals(0.70 * 0.58, t.value().expectation(), EPS); // E(x⊙y) = E(x)·E(y)
    }

    @Test
    void consensusOfEqualCorrelatedSources_givesNoBoost() {
        // PSL & MEBN reading the same evidence must NOT inflate confidence — consensus of x,x = x.
        OpinionTree t = OpinionTree.consensus("engines", List.of(OpinionTree.leaf("psl", X), OpinionTree.leaf("mebn", X)));
        assertEquals(X.expectation(), t.value().expectation(), EPS);
        assertEquals(X.uncertainty(), t.value().uncertainty(), EPS);
    }

    @Test
    void fuseIndependentOfAgreeingSources_lowersUncertainty() {
        // Two INDEPENDENT sources agreeing → more certain (u drops) — the opposite of consensus.
        OpinionTree t = OpinionTree.fuseIndependent("docs",
                List.of(OpinionTree.leaf("doc1", X), OpinionTree.leaf("doc2", X)));
        assertTrue(t.value().uncertainty() < X.uncertainty(),
                "independent agreement should reduce uncertainty: " + t.value());
    }

    @Test
    void discountAndComplement() {
        assertEquals(0.5 * 0.6, OpinionTree.discount("trust", 0.5, OpinionTree.leaf("x", X)).value().belief(), EPS);
        Opinion c = OpinionTree.complement("not", OpinionTree.leaf("x", X)).value();
        assertEquals(X.disbelief(), c.belief(), EPS);
        assertEquals(X.belief(), c.disbelief(), EPS);
    }

    @Test
    void nestedTreeEvaluatesBottomUp() {
        // conjoin( consensus(x,x)=x , y ) → E = E(x)·E(y)
        OpinionTree tree = OpinionTree.conjoin("root", List.of(
                OpinionTree.consensus("engines", List.of(OpinionTree.leaf("psl", X), OpinionTree.leaf("mebn", X))),
                OpinionTree.leaf("type", Y)));
        assertEquals(0.70 * 0.58, tree.expectation(), EPS);
    }

    @Test
    void emptyInternalNodeIsVacuous() {
        assertTrue(OpinionTree.fuseIndependent("empty", List.of()).value().isVacuous());
    }
}
