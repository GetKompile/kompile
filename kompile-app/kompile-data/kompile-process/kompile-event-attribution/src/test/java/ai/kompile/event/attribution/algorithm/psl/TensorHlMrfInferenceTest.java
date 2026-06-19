/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.psl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the ND4J-vectorized {@link TensorHlMrfInference} produces the same MAP solution as
 * the reference {@link ScalarHlMrfInference} (it is the same algorithm, expressed as matrix ops),
 * plus the size-based routing in {@link HlMrfMapInference#chooseSolver(int)}.
 *
 * <p>Tensor tests are skipped (not failed) if no ND4J backend is loadable; the test-scoped
 * {@code nd4j-native} dependency provides a CPU backend in this module's build.</p>
 */
class TensorHlMrfInferenceTest {

    private static final double TIGHT = 0.02;
    private static final double LOOSE = 0.05;

    /** Run both solvers on the same grounded program and assert agreement within {@code tol}. */
    private void assertParity(PslProgram program, double tol) {
        assumeTrue(TensorHlMrfInference.isAvailable(), "no ND4J backend available");
        List<GroundRule> ground = program.ground();
        HlMrfMapInference.Result scalar = new ScalarHlMrfInference().solve(program, ground);
        HlMrfMapInference.Result tensor = new TensorHlMrfInference().solve(program, ground);

        assertTrue(tensor.converged(), "tensor solver should converge");
        for (String key : program.atomKeys()) {
            assertEquals(scalar.values().get(key), tensor.values().get(key), tol,
                    "scalar/tensor mismatch at " + key);
            double v = tensor.values().get(key);
            assertTrue(v >= -1e-6 && v <= 1.0 + 1e-6, key + " out of [0,1]: " + v);
        }
    }

    @Test
    void tensorMatchesScalar_softEqualityAnchor() {
        PslProgram p = new PslProgram();
        p.observe("Prior", 0.3, "a");
        p.target("State", "a");
        p.addRule("2: Prior(a) -> State(a) ^2");
        p.addRule("2: State(a) -> Prior(a) ^2");
        assertParity(p, TIGHT);
    }

    @Test
    void tensorMatchesScalar_negativePrior() {
        PslProgram p = new PslProgram();
        p.target("State", "x");
        p.addRule("5: !State(x) ^2");
        assertParity(p, TIGHT);
    }

    @Test
    void tensorMatchesScalar_propagationWithPrior() {
        PslProgram p = new PslProgram();
        p.observe("State", 1.0, "a");
        p.observe("Link", 0.9, "a", "b");
        p.target("State", "b");
        p.addRule("10: State(a) & Link(a, b) -> State(b) ^2");
        p.addRule("1: !State(b) ^2");
        assertParity(p, TIGHT);
    }

    @Test
    void tensorMatchesScalar_hardConstraint() {
        PslProgram p = new PslProgram();
        p.observe("State", 1.0, "a");
        p.target("State", "b");
        p.addRule("State(a) -> State(b) .");
        assertParity(p, TIGHT);
    }

    @Test
    void tensorMatchesScalar_firstOrderChain() {
        PslProgram p = new PslProgram();
        p.observe("State", 1.0, "a");
        p.observe("Link", 0.9, "a", "b");
        p.observe("Link", 0.9, "b", "c");
        p.target("State", "b");
        p.target("State", "c");
        p.addRule("10: State(X) & Link(X, Y) -> State(Y) ^2");
        p.addRule("1: !State(Z) ^2"); // anchor so the optimum is unique
        assertParity(p, LOOSE);
    }

    @Test
    void router_picksScalarForSmallPrograms() {
        assertInstanceOf(ScalarHlMrfInference.class, HlMrfMapInference.chooseSolver(100, 50));
    }

    @Test
    void router_picksTensorForMidScaleDenseWhenBackendAvailable() {
        // Above the threshold but well within the dense-cell budget ⇒ dense tensor (if backend).
        HlMrfSolver solver = HlMrfMapInference.chooseSolver(HlMrfMapInference.DEFAULT_TENSOR_THRESHOLD + 1, 20);
        if (TensorHlMrfInference.isAvailable()) {
            assertInstanceOf(TensorHlMrfInference.class, solver);
        } else {
            assertInstanceOf(SgdHlMrfInference.class, solver);
        }
    }

    @Test
    void router_picksSgdWhenDenseMatrixWouldExceedBudget() {
        // R·A far exceeds the dense-cell budget ⇒ sparse SGD regardless of backend.
        assertInstanceOf(SgdHlMrfInference.class, HlMrfMapInference.chooseSolver(1_000_000, 1_000_000));
    }
}
