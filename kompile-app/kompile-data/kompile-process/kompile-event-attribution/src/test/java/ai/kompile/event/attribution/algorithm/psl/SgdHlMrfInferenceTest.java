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

import ai.kompile.graph.reasoning.psl.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the sparse mini-batch {@link SgdHlMrfInference}: it should reach the same convex
 * optimum as the reference {@link ScalarHlMrfInference} (within SGD tolerance), be deterministic
 * (fixed-seed shuffling), and keep all truth values in {@code [0,1]}. Pure Java — no backend.
 */
class SgdHlMrfInferenceTest {

    private static final double TOL = 0.06;

    /** SGD converges to the scalar solver's optimum within SGD tolerance. */
    private void assertConvergesToScalar(PslProgram program, double tol) {
        List<GroundRule> ground = program.ground();
        HlMrfMapInference.Result scalar = new ScalarHlMrfInference().solve(program, ground);
        HlMrfMapInference.Result sgd = new SgdHlMrfInference().solve(program, ground);

        for (String key : program.atomKeys()) {
            assertEquals(scalar.values().get(key), sgd.values().get(key), tol, "mismatch at " + key);
            double v = sgd.values().get(key);
            assertTrue(v >= -1e-9 && v <= 1.0 + 1e-9, key + " out of [0,1]: " + v);
        }
    }

    @Test
    void sgdMatchesScalar_softEqualityAnchor() {
        PslProgram p = new PslProgram();
        p.observe("Prior", 0.3, "a");
        p.target("State", "a");
        p.addRule("2: Prior(a) -> State(a) ^2");
        p.addRule("2: State(a) -> Prior(a) ^2");
        assertConvergesToScalar(p, TOL);
    }

    @Test
    void sgdMatchesScalar_negativePrior() {
        PslProgram p = new PslProgram();
        p.target("State", "x");
        p.addRule("5: !State(x) ^2");
        assertConvergesToScalar(p, TOL);
    }

    @Test
    void sgdMatchesScalar_propagationWithPrior() {
        PslProgram p = new PslProgram();
        p.observe("State", 1.0, "a");
        p.observe("Link", 0.9, "a", "b");
        p.target("State", "b");
        p.addRule("10: State(a) & Link(a, b) -> State(b) ^2");
        p.addRule("1: !State(b) ^2");
        assertConvergesToScalar(p, TOL);
    }

    @Test
    void sgdMatchesScalar_firstOrderChain() {
        PslProgram p = new PslProgram();
        p.observe("State", 1.0, "a");
        p.observe("Link", 0.9, "a", "b");
        p.observe("Link", 0.9, "b", "c");
        p.target("State", "b");
        p.target("State", "c");
        p.addRule("10: State(X) & Link(X, Y) -> State(Y) ^2");
        p.addRule("1: !State(Z) ^2"); // anchor so the optimum is unique
        assertConvergesToScalar(p, TOL);
    }

    @Test
    void sgd_isDeterministic() {
        PslProgram p = new PslProgram();
        p.observe("State", 1.0, "a");
        p.observe("Link", 0.7, "a", "b");
        p.observe("Link", 0.6, "b", "c");
        p.target("State", "b");
        p.target("State", "c");
        p.addRule("4: State(X) & Link(X, Y) -> State(Y) ^2");
        p.addRule("1: !State(Z) ^2");

        List<GroundRule> ground = p.ground();
        HlMrfMapInference.Result first = new SgdHlMrfInference().solve(p, ground);
        HlMrfMapInference.Result second = new SgdHlMrfInference().solve(p, ground);

        for (String key : p.atomKeys()) {
            assertEquals(first.values().get(key), second.values().get(key), 1e-12,
                    "non-deterministic at " + key);
        }
    }
}
