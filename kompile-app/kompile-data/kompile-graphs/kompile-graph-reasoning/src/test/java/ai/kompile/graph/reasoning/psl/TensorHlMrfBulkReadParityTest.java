/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.psl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Parity test for the bulk-read fix in {@link TensorHlMrfInference#solve}: the solution vector
 * is now extracted once via {@code v.toDoubleVector()} instead of per-element {@code getDouble(i,0)}.
 *
 * <p>The check: run the same small PSL program through both {@link TensorHlMrfInference} (bulk read
 * via {@code toDoubleVector}) and {@link ScalarHlMrfInference} (reference scalar solver), and assert
 * that every atom value matches within float precision.  Both solvers optimize the identical convex
 * HL-MRF energy, so they must return the same MAP solution.</p>
 */
class TensorHlMrfBulkReadParityTest {

    private static final double PARITY_EPS = 1e-4;

    /** Two-atom propagation program identical to the one used in AdmmHlMrfInferenceTest. */
    private static PslProgram propagationProgram() {
        PslProgram p = new PslProgram();
        p.addRule("2.0: State(X) & Link(X, Y) -> State(Y) ^2");
        p.observe("State", 1.0, "alice");
        p.observe("Link", 0.9, "alice", "bob");
        p.target("State", "bob");
        return p;
    }

    /** Three-atom chain: alice → bob → carol, with a strong push rule. */
    private static PslProgram chainProgram() {
        PslProgram p = new PslProgram();
        p.addRule("3.0: State(X) & Link(X, Y) -> State(Y) ^2");
        p.observe("State", 0.8, "alice");
        p.observe("Link", 1.0, "alice", "bob");
        p.observe("Link", 0.6, "bob", "carol");
        p.target("State", "bob");
        p.target("State", "carol");
        return p;
    }

    @Test
    void tensorSolverMatchesScalarSolverTwoAtoms() {
        assumeTrue(TensorHlMrfInference.isAvailable(),
                "ND4J backend not available — TensorHlMrfInference not testable in this environment");

        PslProgram prog = propagationProgram();
        List<GroundRule> ground = prog.ground();

        Map<String, Double> scalar = new ScalarHlMrfInference().solve(prog, ground).values();
        Map<String, Double> tensor = new TensorHlMrfInference().solve(prog, ground).values();

        assertEquals(scalar.keySet(), tensor.keySet(),
                "Tensor solver returned different atom keys than scalar solver");
        for (String key : scalar.keySet()) {
            assertEquals(scalar.get(key), tensor.get(key), PARITY_EPS,
                    "Atom '" + key + "': scalar=" + scalar.get(key) + " tensor=" + tensor.get(key));
        }
    }

    @Test
    void tensorSolverMatchesScalarSolverThreeAtomChain() {
        assumeTrue(TensorHlMrfInference.isAvailable(),
                "ND4J backend not available — TensorHlMrfInference not testable in this environment");

        PslProgram prog = chainProgram();
        List<GroundRule> ground = prog.ground();

        Map<String, Double> scalar = new ScalarHlMrfInference().solve(prog, ground).values();
        Map<String, Double> tensor = new TensorHlMrfInference().solve(prog, ground).values();

        assertEquals(scalar.keySet(), tensor.keySet());
        for (String key : scalar.keySet()) {
            assertEquals(scalar.get(key), tensor.get(key), PARITY_EPS,
                    "Atom '" + key + "' mismatch");
        }
    }

    /**
     * Directly exercises the ND4J bulk-read path: builds an INDArray column vector,
     * compares {@code toDoubleVector()} against element-wise {@code getDouble(i, 0)}.
     * This is the exact read that was changed in TensorHlMrfInference.
     */
    @Test
    void toDoubleVectorMatchesPerElementGetDoubleForColumnVector() {
        assumeTrue(TensorHlMrfInference.isAvailable(),
                "ND4J backend not available");

        int a = 5;
        double[] expected = {0.1, 0.4, 0.9, 0.2, 0.7};
        float[][] v0 = new float[a][1];
        for (int i = 0; i < a; i++) v0[i][0] = (float) expected[i];

        org.nd4j.linalg.api.ndarray.INDArray v =
                org.nd4j.linalg.factory.Nd4j.create(v0);  // shape (a, 1)

        // Bulk path (what TensorHlMrfInference now uses)
        double[] bulk = v.toDoubleVector();
        // Per-element reference
        double[] perElem = new double[a];
        for (int i = 0; i < a; i++) perElem[i] = v.getDouble(i, 0);

        assertArrayEquals(perElem, bulk, 1e-6,
                "toDoubleVector() must produce the same values as per-element getDouble(i,0)");
    }
}
