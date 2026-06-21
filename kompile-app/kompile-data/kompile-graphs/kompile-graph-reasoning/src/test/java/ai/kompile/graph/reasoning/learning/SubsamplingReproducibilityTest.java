/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.learning;

import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for mini-batch subsampling in {@link StructuredPerceptronLearner} and
 * {@link PseudolikelihoodLearner}: reproducibility under a fixed seed, validity under a
 * different seed, and backward-compatibility of the default (full-batch) constructor path.
 *
 * <p>The program is built with enough Link/State facts to generate more ground rules than
 * {@code batchSize}, so subsampling actually kicks in.</p>
 */
class SubsamplingReproducibilityTest {

    private static final int BATCH_SIZE = 2;
    private static final long SEED_A = 42L;
    private static final long SEED_B = 99L;

    /**
     * Build a 3-node chain: alice→bob→carol, plus targets for all State atoms and
     * extra link/state observations to produce a richer ground-rule set.
     */
    static PslProgram buildRichProgram() {
        PslProgram p = new PslProgram();
        // Two rules: propagation + negative prior
        p.addRule("1.0: State(X) & Link(X, Y) -> State(Y) ^2");
        p.addRule("0.5: ~State(N) ^2");

        // Observed: alice is active; alice→bob and bob→carol links
        p.observe("State", 1.0, "alice");
        p.observe("Link", 1.0, "alice", "bob");
        p.observe("Link", 1.0, "bob", "carol");
        p.observe("Link", 0.5, "alice", "carol");

        // Targets
        p.target("State", "bob");
        p.target("State", "carol");
        p.target("State", "dave");
        return p;
    }

    static Map<String, Double> buildGroundTruth() {
        Map<String, Double> gt = new HashMap<>();
        gt.put("State(alice)", 1.0);
        gt.put("Link(alice, bob)", 1.0);
        gt.put("Link(bob, carol)", 1.0);
        gt.put("Link(alice, carol)", 0.5);
        gt.put("State(bob)", 1.0);
        gt.put("State(carol)", 0.8);
        gt.put("State(dave)", 0.0);
        return gt;
    }

    // ─── StructuredPerceptronLearner ─────────────────────────────────────────────

    @Test
    @DisplayName("StructuredPerceptronLearner: same seed → identical learned weights (reproducible)")
    void perceptronSameSeedIsReproducible() {
        PslProgram program = buildRichProgram();
        Map<String, Double> gt = buildGroundTruth();

        StructuredPerceptronLearner learnerA =
                new StructuredPerceptronLearner(0.1, 1e-4, BATCH_SIZE, SEED_A);
        StructuredPerceptronLearner learnerB =
                new StructuredPerceptronLearner(0.1, 1e-4, BATCH_SIZE, SEED_A);

        List<PslRule> rulesA = learnerA.learn(program, gt, 30);
        List<PslRule> rulesB = learnerB.learn(buildRichProgram(), gt, 30);

        assertEquals(rulesA.size(), rulesB.size(), "Rule counts must match");
        for (int i = 0; i < rulesA.size(); i++) {
            assertEquals(rulesA.get(i).weight(), rulesB.get(i).weight(), 1e-12,
                    "Weight at index " + i + " must be identical for the same seed");
        }
    }

    @Test
    @DisplayName("StructuredPerceptronLearner: different seed → valid (finite, non-negative) weights")
    void perceptronDifferentSeedProducesValidWeights() {
        PslProgram program = buildRichProgram();
        Map<String, Double> gt = buildGroundTruth();

        StructuredPerceptronLearner learner =
                new StructuredPerceptronLearner(0.1, 1e-4, BATCH_SIZE, SEED_B);
        List<PslRule> rules = learner.learn(program, gt, 30);

        assertNotNull(rules);
        assertFalse(rules.isEmpty());
        for (PslRule r : rules) {
            assertTrue(Double.isFinite(r.weight()), "Weight must be finite: " + r.weight());
            assertTrue(r.weight() >= 0.0, "Weight must be non-negative: " + r.weight());
        }
    }

    @Test
    @DisplayName("StructuredPerceptronLearner: default constructor (full-batch) is unaffected by batch fields")
    void perceptronDefaultConstructorIsFullBatch() {
        PslProgram program = buildRichProgram();
        Map<String, Double> gt = buildGroundTruth();

        // Default constructor — must behave identically to the pre-change full-batch path.
        StructuredPerceptronLearner defaultLearner = new StructuredPerceptronLearner();
        List<PslRule> defaultRules = defaultLearner.learn(program, gt, 50);

        assertNotNull(defaultRules);
        assertEquals(program.rules().size(), defaultRules.size());
        for (PslRule r : defaultRules) {
            assertTrue(r.weight() >= 0.0, "Default path weight must be non-negative");
        }

        // Explicit full-batch via (lr, tol) constructor must agree with default (same lr/tol).
        StructuredPerceptronLearner explicitFull =
                new StructuredPerceptronLearner(0.1, 1e-4);
        List<PslRule> fullRules = explicitFull.learn(buildRichProgram(), gt, 50);
        assertEquals(defaultRules.size(), fullRules.size());
        for (int i = 0; i < defaultRules.size(); i++) {
            assertEquals(defaultRules.get(i).weight(), fullRules.get(i).weight(), 1e-12,
                    "Full-batch paths must agree at index " + i);
        }
    }

    // ─── PseudolikelihoodLearner ──────────────────────────────────────────────────

    @Test
    @DisplayName("PseudolikelihoodLearner: same seed → identical learned weights (reproducible)")
    void pseudolikelihoodSameSeedIsReproducible() {
        PslProgram program = buildRichProgram();
        Map<String, Double> gt = buildGroundTruth();

        PseudolikelihoodLearner learnerA =
                new PseudolikelihoodLearner(0.05, 1e-4, BATCH_SIZE, SEED_A);
        PseudolikelihoodLearner learnerB =
                new PseudolikelihoodLearner(0.05, 1e-4, BATCH_SIZE, SEED_A);

        List<PslRule> rulesA = learnerA.learn(program, gt, 30);
        List<PslRule> rulesB = learnerB.learn(buildRichProgram(), gt, 30);

        assertEquals(rulesA.size(), rulesB.size(), "Rule counts must match");
        for (int i = 0; i < rulesA.size(); i++) {
            assertEquals(rulesA.get(i).weight(), rulesB.get(i).weight(), 1e-12,
                    "Weight at index " + i + " must be identical for the same seed");
        }
    }

    @Test
    @DisplayName("PseudolikelihoodLearner: default constructor (full-batch) is unaffected by batch fields")
    void pseudolikelihoodDefaultConstructorIsFullBatch() {
        PslProgram program = buildRichProgram();
        Map<String, Double> gt = buildGroundTruth();

        PseudolikelihoodLearner defaultLearner = new PseudolikelihoodLearner();
        List<PslRule> defaultRules = defaultLearner.learn(program, gt, 50);

        assertNotNull(defaultRules);
        assertEquals(program.rules().size(), defaultRules.size());
        for (PslRule r : defaultRules) {
            assertTrue(r.weight() >= 0.0, "Default path weight must be non-negative");
        }

        // Explicit two-arg constructor must also be full-batch and agree.
        PseudolikelihoodLearner explicitFull =
                new PseudolikelihoodLearner(0.05, 1e-4);
        List<PslRule> fullRules = explicitFull.learn(buildRichProgram(), gt, 50);
        assertEquals(defaultRules.size(), fullRules.size());
        for (int i = 0; i < defaultRules.size(); i++) {
            assertEquals(defaultRules.get(i).weight(), fullRules.get(i).weight(), 1e-12,
                    "Full-batch paths must agree at index " + i);
        }
    }
}
