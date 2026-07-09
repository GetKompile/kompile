/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol;

import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Fix #9: MEBN soft evidence via Pearl's virtual-evidence method.
 *
 * <h3>Test scenario</h3>
 * A 2-node causal network: {@code cause → effect}.  Both are binary (FALSE/TRUE).
 * Theory: {@link MebnInferenceService#buildCausalTheory} with strength=0.8.
 * Prior on cause: 0.5 (uninformative).
 * Noisy-OR: P(effect=TRUE | cause=TRUE) = 0.8, P(effect=TRUE | cause=FALSE) = 0.0 (leak = 0).
 *
 * <p>Prior marginals (no evidence):
 * P(cause=T) ≈ 0.5 (root prior),
 * P(effect=T) = P(effect=T|cause=T)·P(cause=T) + P(effect=T|cause=F)·P(cause=F) = 0.8·0.5 = 0.4</p>
 *
 * <p>Hard evidence effect=TRUE: P(cause=TRUE|effect=TRUE) = 0.8·0.5 / 0.4 = 1.0 (exact Bayes).</p>
 *
 * <p>Soft evidence (4:1 likelihood favoring TRUE): likelihood=[0.2, 0.8] (equivalent to
 * seeing "effect is 4× more likely to be TRUE than FALSE").  After Jeffrey conditioning
 * via virtual evidence, the posterior of cause must be:
 * strictly between the no-evidence prior (0.5) and the hard-evidence posterior (~1.0).</p>
 *
 * <p>Uninformative soft evidence (1:1 likelihood=[1,1]):  Jeffrey conditioning with uniform
 * likelihood leaves the prior unchanged.  The argmax approach (old code) would have clamped
 * to TRUE (stateIndex=1), giving the hard-evidence posterior instead of the prior.
 * This regression test verifies the fix restores correct behavior.</p>
 */
class MebnSoftEvidenceTest {

    /** Cause entity and effect entity share the same type in the causal theory. */
    private static final String ENTITY_TYPE = "Node";
    private static final String CAUSE_RV   = "cause";
    private static final String EFFECT_RV  = "effect";
    private static final double STRENGTH   = 0.8;

    private MutableReasoningGraph graph;
    private MTheory theory;

    @BeforeEach
    void buildGraph() {
        // Single entity so the causal theory grounds one (cause(node0), effect(node0)) pair.
        graph = new MutableReasoningGraph();
        graph.addEntity(GraphEntity.builder("node0").type(ENTITY_TYPE).label("Node0").weight(0.5).build());

        // Build a causal theory: cause(X) -> effect(X) with learnable edge strength.
        theory = MebnInferenceService.buildCausalTheory(graph, ENTITY_TYPE, CAUSE_RV, EFFECT_RV, STRENGTH);
    }

    // ─── Helper: run MEBN inference and return posterior for a given variable ────

    private double posteriorOf(String rvName, FindingStore findings) {
        List<EntailmentRecord> records = EntailmentEngine.entailFromMebn(graph, findings, theory);
        return records.stream()
                .filter(r -> r.groundedRvOrAtomKey().startsWith(rvName + "("))
                .mapToDouble(EntailmentRecord::posterior)
                .findFirst()
                .orElse(Double.NaN);
    }

    // ─── Test 1: no evidence → prior ────────────────────────────────────────────

    @Test
    @DisplayName("No evidence: cause posterior equals prior (~0.5)")
    void noEvidence_causePriorIsHalf() {
        FindingStore empty = new FindingStore();
        double causePosterior = posteriorOf(CAUSE_RV, empty);
        assertFalse(Double.isNaN(causePosterior), "cause posterior must be present");
        // Root prior from SSBNGenerator = entity.weight() = 0.5
        assertEquals(0.5, causePosterior, 0.05,
                "With no evidence, cause posterior should equal its prior (≈0.5)");
    }

    // ─── Test 2: hard evidence on effect=TRUE → high cause posterior ─────────────

    @Test
    @DisplayName("Hard evidence effect=TRUE: cause posterior strictly > prior")
    void hardEvidence_causePosteriorAbovePrior() {
        FindingStore findings = new FindingStore();
        // effect=TRUE (state index 1)
        findings.assertFinding(Finding.hard(EFFECT_RV, List.of("node0"), 1, "hard-test"));

        double causePosterior = posteriorOf(CAUSE_RV, findings);
        double effectPosterior = posteriorOf(EFFECT_RV, findings);

        assertFalse(Double.isNaN(causePosterior), "cause posterior must be present with hard evidence");
        // effect is hard-clamped to TRUE, so its posterior should be 1.0
        assertEquals(1.0, effectPosterior, 1e-6, "effect is hard evidence=TRUE → posterior=1.0");
        // cause posterior should be strictly above 0.5 (prior) due to Bayesian update
        assertTrue(causePosterior > 0.5,
                "Hard evidence effect=TRUE should raise cause posterior above prior (0.5); got " + causePosterior);
    }

    // ─── Test 3: soft evidence 4:1 → cause posterior BETWEEN prior and hard-ev ──

    @Test
    @DisplayName("Soft evidence 4:1 on effect: cause posterior between prior and hard-evidence posterior")
    void softEvidence4to1_causePosteriorBetweenPriorAndHard() {
        // Run without evidence to get the prior.
        double prior = posteriorOf(CAUSE_RV, new FindingStore());
        assertFalse(Double.isNaN(prior));

        // Run with hard evidence effect=TRUE to get the upper bound.
        FindingStore hardStore = new FindingStore();
        hardStore.assertFinding(Finding.hard(EFFECT_RV, List.of("node0"), 1, "hard-test"));
        double hardPosterior = posteriorOf(CAUSE_RV, hardStore);
        assertFalse(Double.isNaN(hardPosterior));

        // Run with soft evidence favoring TRUE at 4:1 (L=[0.2, 0.8] → normalized to max=0.8: [0.25, 1.0]).
        FindingStore softStore = new FindingStore();
        softStore.assertFinding(Finding.soft(EFFECT_RV, List.of("node0"),
                new double[]{0.2, 0.8}, "soft-test"));
        double softPosterior = posteriorOf(CAUSE_RV, softStore);
        assertFalse(Double.isNaN(softPosterior),
                "cause posterior must be present with soft evidence");

        // Key assertion: soft evidence should move the posterior, but less than hard evidence.
        assertTrue(softPosterior > prior,
                String.format("Soft evidence (4:1) should raise cause posterior above prior; " +
                        "prior=%.4f soft=%.4f", prior, softPosterior));
        assertTrue(softPosterior < hardPosterior,
                String.format("Soft evidence (4:1) should produce lower cause posterior than hard evidence; " +
                        "hard=%.4f soft=%.4f", hardPosterior, softPosterior));
    }

    // ─── Test 4: uninformative soft evidence [1,1] → posterior equals prior ────

    @Test
    @DisplayName("Uninformative soft evidence [1,1]: cause posterior equals prior (regression vs argmax)")
    void softEvidenceUniform_posteriorEqualsPrior() {
        // Prior (no evidence)
        double prior = posteriorOf(CAUSE_RV, new FindingStore());
        assertFalse(Double.isNaN(prior));

        // Uninformative likelihood: all states equally likely → Jeffrey update is identity.
        FindingStore store = new FindingStore();
        store.assertFinding(Finding.soft(EFFECT_RV, List.of("node0"),
                new double[]{1.0, 1.0}, "uninformative-test"));
        double posterior = posteriorOf(CAUSE_RV, store);
        assertFalse(Double.isNaN(posterior),
                "cause posterior must be present with uninformative soft evidence");

        // REGRESSION TEST: the old argmax code would have clamped effect=TRUE (stateIndex=1),
        // yielding the hard-evidence posterior instead of the prior.
        // With virtual evidence, [1,1] → P(virtual=T|cause=s_i) = 1.0/1.0 = 1.0 for all states
        // → uniform CPT → virtual observation is non-discriminative → posterior ≈ prior.
        assertEquals(prior, posterior, 0.05,
                String.format("Uninformative soft evidence should leave cause posterior unchanged; " +
                        "prior=%.4f got=%.4f", prior, posterior));
    }

    // ─── Test 5: hard findings are unchanged vs old behavior ────────────────────

    @Test
    @DisplayName("Hard finding on cause: posterior = 1.0 (unchanged by virtual-evidence refactor)")
    void hardFindingOnCause_posteriorIsOne() {
        FindingStore findings = new FindingStore();
        findings.assertFinding(Finding.hard(CAUSE_RV, List.of("node0"), 1, "src"));

        double causePosterior = posteriorOf(CAUSE_RV, findings);
        assertFalse(Double.isNaN(causePosterior));
        // Hard evidence clamps cause=TRUE → posterior must be 1.0
        assertEquals(1.0, causePosterior, 1e-6,
                "Hard finding cause=TRUE should yield posterior 1.0");
    }

    // ─── Test 6: virtual nodes are absent from EntailmentRecords ────────────────

    @Test
    @DisplayName("Virtual-evidence nodes are excluded from EntailmentRecords")
    void virtualNodesNotInRecords() {
        FindingStore store = new FindingStore();
        store.assertFinding(Finding.soft(EFFECT_RV, List.of("node0"),
                new double[]{0.3, 0.7}, "src"));

        List<EntailmentRecord> records = EntailmentEngine.entailFromMebn(graph, store, theory);
        for (EntailmentRecord r : records) {
            assertFalse(r.groundedRvOrAtomKey().startsWith(EntailmentEngine.VIRTUAL_NODE_PREFIX),
                    "Virtual-evidence node leaked into EntailmentRecords: " + r.groundedRvOrAtomKey());
        }
        assertFalse(records.isEmpty(), "Should have at least one real entailment record");
    }
}
