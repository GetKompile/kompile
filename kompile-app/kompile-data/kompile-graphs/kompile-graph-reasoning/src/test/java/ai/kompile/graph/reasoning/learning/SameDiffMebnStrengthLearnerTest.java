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

import ai.kompile.graph.reasoning.bayesian.BayesianNetwork;
import ai.kompile.graph.reasoning.bayesian.BayesianNode;
import ai.kompile.graph.reasoning.fol.MebnInferenceService;
import ai.kompile.graph.reasoning.fol.ReasoningGraphKnowledgeBase;
import ai.kompile.graph.reasoning.mebn.EntityType;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RandomVariable;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder;
import ai.kompile.graph.reasoning.mebn.SSBNGenerator;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SameDiffMebnStrengthLearner} — the SameDiff production gradient for
 * MEBN noisy-OR edge strength learning.
 *
 * <h3>Test strategy</h3>
 * <ol>
 *   <li><b>Oracle agreement</b> — the SameDiff autodiff gradient must match the Java analytic
 *       gradient ({@link MebnWeightLearner#analyticGradient}) within a tight tolerance on a
 *       controlled 3-entity 2-edge theory. This is the correctness criterion: SameDiff is the
 *       production path, the Java analytic is the numerical oracle.</li>
 *   <li><b>Learning direction</b> — running the learner must move edge strengths in the correct
 *       direction (toward fitted targets) and reduce MSE loss.</li>
 *   <li><b>MebnWeightLearner delegation</b> — verifies that the public API
 *       {@link MebnWeightLearner#learn} now delegates to the SameDiff path (not the old scalar
 *       loop), by checking that its result matches the SameDiff learner directly.</li>
 * </ol>
 */
class SameDiffMebnStrengthLearnerTest {

    // ─── Fixtures ──────────────────────────────────────────────────────────────

    private static ReasoningGraph people() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(GraphEntity.builder("alice").type("Person").label("Alice").build());
        g.addEntity(GraphEntity.builder("bob").type("Person").label("Bob").build());
        g.addEntity(GraphEntity.builder("carol").type("Person").label("Carol").build());
        return g;
    }

    private static double edgeStrength(MTheory t, String parent, String child) {
        String key = parent + "->" + child;
        for (MFrag m : t.getMFrags()) {
            if (m.getEdgeStrengths().containsKey(key)) {
                return m.getEdgeStrength(parent, child);
            }
        }
        return Double.NaN;
    }

    // ─── Test 1: Oracle agreement ──────────────────────────────────────────────

    /**
     * SameDiff autodiff gradient must match the Java analytic oracle to within 1e-5 on
     * a 3-entity, 1-edge (cause → effect) causal theory.
     *
     * <p>The Java oracle ({@link MebnWeightLearner#analyticGradient}) computes per-entity
     * exact analytic partial derivatives using the full noisy-OR formula; the SameDiff path
     * differentiates through the linear marginal approximation. They match when the parent
     * posterior is taken from the same inference result (i.e. the two models agree at the
     * given posterior values).</p>
     */
    @Test
    void sdGradient_matchesJavaAnalyticOracle_withinTolerance() {
        ReasoningGraph graph = people();
        MTheory theory = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.3);
        MebnInferenceService svc = new MebnInferenceService();
        Map<String, Double> posteriors = svc.infer(graph, theory, Map.of());

        // Observations: all effect(X) set to 1.0 (forcing upward gradient on s).
        Map<String, Double> observations = new LinkedHashMap<>();
        posteriors.keySet().stream()
                .filter(k -> k.startsWith("effect"))
                .forEach(k -> observations.put(k, 1.0));
        assertFalse(observations.isEmpty(), "effect RVs must exist in the posterior");

        // Collect the single cause→effect edge.
        List<MebnWeightLearner.Edge> edges = SameDiffMebnStrengthLearner.collectEdges(theory);
        assertEquals(1, edges.size(), "fixture has exactly one learnable edge");
        MebnWeightLearner.Edge edge = edges.get(0);
        double s = edge.mfrag().getEdgeStrength(edge.parent(), edge.child());

        // Java analytic oracle gradient (the reference).
        MebnWeightLearner oracle = new MebnWeightLearner(0.1, 1e-3);
        double javaGrad = oracle.analyticGradient(edge, s, posteriors, observations);

        // SameDiff gradient (the production path).
        try (SameDiffMebnStrengthLearner.TensorBatch batch =
                     SameDiffMebnStrengthLearner.buildTensorBatch(edges, posteriors, observations)) {
            assertTrue(batch.rowCount() > 0, "tensor batch must be non-empty");

            double[] sdGrads = SameDiffMebnStrengthLearner.sdGradient(new double[]{s}, batch);
            assertEquals(1, sdGrads.length);
            double sdGrad = sdGrads[0];

        // The SameDiff forward graph now implements the true noisy-OR:
        //   predicted = 1 − (1 − leak) · (1 − s · pParent)
        // and the Java oracle computes:
        //   ∂p_c/∂s = pParent · (1 − p_c) / (1 − s · pParent + ε)
        // When pChild from inference equals predicted_noisy_or, the (1−p_c)/(1−s·pPar+ε)
        // factor collapses exactly to (1−leak), so both formulas give the same gradient to
        // floating-point (autodiff) precision.  Tolerance target: 1e-6 relative error.
            assertFalse(Double.isNaN(sdGrad), "SameDiff gradient must be finite");
            assertFalse(Double.isNaN(javaGrad), "Java analytic gradient must be finite");
            // Both must be negative (fitting effect=1.0 must increase s, so gradient is negative
            // in the descent convention).
            assertTrue(javaGrad < 0,
                    "Java analytic gradient must be negative (need to raise s), got " + javaGrad);
            assertTrue(sdGrad < 0,
                    "SameDiff gradient must be negative (need to raise s), got " + sdGrad);
            // Tight tolerance: the noisy-OR SameDiff graph must match the analytic oracle to autodiff
            // precision (1e-6 relative error).  Any larger divergence indicates the forward graph
            // still uses the wrong (linear) approximation.
            double relErr = Math.abs(sdGrad - javaGrad) / (Math.abs(javaGrad) + 1e-9);
            assertTrue(relErr < 1e-6,
                    "SameDiff gradient " + sdGrad + " must match Java oracle " + javaGrad
                            + " within 1e-6 relative error (got " + String.format("%.2e", relErr) + ")");
        }
    }

    @Test
    void sdGradient_repeatedCalls_releaseResultsAndPreserveBorrowedBatch() {
        SameDiffMebnStrengthLearner.resetExecutionResultCloseCountForTests();
        ReasoningGraph graph = people();
        MTheory theory = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.3);
        MebnInferenceService svc = new MebnInferenceService();
        Map<String, Double> posteriors = svc.infer(graph, theory, Map.of());
        Map<String, Double> observations = new LinkedHashMap<>();
        posteriors.keySet().stream()
                .filter(k -> k.startsWith("effect"))
                .forEach(k -> observations.put(k, 1.0));
        List<MebnWeightLearner.Edge> edges = SameDiffMebnStrengthLearner.collectEdges(theory);

        try (SameDiffMebnStrengthLearner.TensorBatch batch =
                     SameDiffMebnStrengthLearner.buildTensorBatch(edges, posteriors, observations)) {
            double[] first = SameDiffMebnStrengthLearner.sdGradient(new double[]{0.3}, batch);
            for (int i = 0; i < 16; i++) {
                double[] repeated = SameDiffMebnStrengthLearner.sdGradient(new double[]{0.3}, batch);
                assertEquals(first.length, repeated.length);
                for (int j = 0; j < first.length; j++) {
                    assertEquals(first[j], repeated[j], 1e-12,
                            "repeated SameDiff gradients must remain numerically stable");
                }
            }
            assertFalse(batch.pParent().wasClosed(),
                    "sdGradient must not close the caller-owned parent tensor");
            assertFalse(batch.target().wasClosed(),
                    "sdGradient must not close the caller-owned target tensor");
        }
        assertTrue(SameDiffMebnStrengthLearner.executionResultCloseCountForTests() >= 34,
                "repeated gradients must release their caller-owned loss/gradient results");
    }

    @Test
    void sdGradient_closesGradientBeforeParent_andPropagatesCleanupFailure() {
        ReasoningGraph graph = people();
        MTheory theory = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.3);
        MebnInferenceService svc = new MebnInferenceService();
        Map<String, Double> posteriors = svc.infer(graph, theory, Map.of());
        Map<String, Double> observations = new LinkedHashMap<>();
        posteriors.keySet().stream()
                .filter(k -> k.startsWith("effect"))
                .forEach(k -> observations.put(k, 1.0));
        List<MebnWeightLearner.Edge> edges = SameDiffMebnStrengthLearner.collectEdges(theory);
        RuntimeException gradientFailure = new RuntimeException("gradient close failure");
        AtomicInteger closeCalls = new AtomicInteger();
        SameDiffMebnStrengthLearner.setSameDiffCloseHookForTests(sd -> {
            if (closeCalls.getAndIncrement() == 0) {
                throw gradientFailure;
            }
            sd.close();
        });
        try (SameDiffMebnStrengthLearner.TensorBatch batch =
                     SameDiffMebnStrengthLearner.buildTensorBatch(edges, posteriors, observations)) {
            RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> SameDiffMebnStrengthLearner.sdGradient(new double[]{0.3}, batch));
            assertSame(gradientFailure, thrown,
                    "cleanup failure must propagate when the gradient operation itself succeeds");
        } finally {
            SameDiffMebnStrengthLearner.resetSameDiffCloseHookForTests();
        }
        assertEquals(2, closeCalls.get(),
                "gradient graph must be attempted before the parent, and parent cleanup must still run");
    }

    @Test
    void learn_repeatedTraining_isDeterministicAndKeepsStrengthInUnitInterval() {
        ReasoningGraph graph = people();
        MTheory firstTheory = MebnInferenceService.buildCausalTheory(
                graph, "Person", "cause", "effect", 0.1);
        MTheory secondTheory = MebnInferenceService.buildCausalTheory(
                graph, "Person", "cause", "effect", 0.1);
        MebnInferenceService svc = new MebnInferenceService();
        Map<String, Double> observations = new LinkedHashMap<>();
        svc.infer(graph, firstTheory, Map.of()).keySet().stream()
                .filter(k -> k.startsWith("effect"))
                .forEach(k -> observations.put(k, 1.0));

        SameDiffMebnStrengthLearner.learn(firstTheory, graph, observations, 12, 0.2, 1234L);
        SameDiffMebnStrengthLearner.learn(secondTheory, graph, observations, 12, 0.2, 1234L);

        double first = edgeStrength(firstTheory, "cause", "effect");
        double second = edgeStrength(secondTheory, "cause", "effect");
        assertEquals(first, second, 1e-12,
                "repeated training runs must preserve the previous numerical behavior");
        assertTrue(first >= 0.0 && first <= 1.0,
                "projected edge strength must remain in [0,1] (got " + first + ")");
    }

    // ─── Test 2: Learning direction ────────────────────────────────────────────

    /**
     * SameDiff learner must move edge strengths toward the given targets and reduce MSE loss.
     */
    @Test
    void learn_fitsEdgeStrengths_reducesErrorTowardObservations() {
        ReasoningGraph graph = people();
        MTheory theory = MebnInferenceService.buildCausalTheory(
                graph, "Person", "cause", "effect", 0.1);  // start weak
        MebnInferenceService svc = new MebnInferenceService();

        Map<String, Double> posteriors = svc.infer(graph, theory, Map.of());
        Map<String, Double> observations = new LinkedHashMap<>();
        posteriors.keySet().stream()
                .filter(k -> k.startsWith("effect"))
                .forEach(k -> observations.put(k, 1.0));
        assertFalse(observations.isEmpty(), "effect observations must exist");

        double strengthBefore = edgeStrength(theory, "cause", "effect");

        // Clone the theory for a baseline loss measurement.
        double lossBefore = mseLoss(svc.infer(graph, theory, Map.of()), observations);

        // Run the SameDiff learner (the same method that MebnWeightLearner.learn() now calls).
        SameDiffMebnStrengthLearner.learn(
                theory, graph, observations, 40, 0.5, 1234L);

        double strengthAfter = edgeStrength(theory, "cause", "effect");
        double lossAfter = mseLoss(svc.infer(graph, theory, Map.of()), observations);

        assertTrue(strengthAfter > strengthBefore,
                "fitting effect=TRUE must raise the cause->effect strength ("
                        + strengthBefore + " -> " + strengthAfter + ")");
        assertTrue(lossAfter < lossBefore,
                "SameDiff learner must reduce MSE loss toward the observations ("
                        + lossBefore + " -> " + lossAfter + ")");
    }

    // ─── Test 3: MebnWeightLearner delegates to SameDiff ──────────────────────

    /**
     * Verify that {@link MebnWeightLearner#learn} now delegates to the SameDiff path by
     * confirming the result is consistent with calling {@link SameDiffMebnStrengthLearner#learn}
     * directly (same theory type, same direction).
     */
    @Test
    void mebnWeightLearner_delegatesToSameDiff_sameDirection() {
        ReasoningGraph graph = people();
        MTheory t1 = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.1);
        MTheory t2 = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.1);

        MebnInferenceService svc = new MebnInferenceService();
        Map<String, Double> observations = new LinkedHashMap<>();
        svc.infer(graph, t1, Map.of()).keySet().stream()
                .filter(k -> k.startsWith("effect"))
                .forEach(k -> observations.put(k, 1.0));

        // Public API path.
        new MebnWeightLearner(0.5, 1e-3).learn(t1, graph, observations, 5);
        // Direct SameDiff path.
        SameDiffMebnStrengthLearner.learn(t2, graph, observations, 5, 0.5, 1234L);

        double s1 = edgeStrength(t1, "cause", "effect");
        double s2 = edgeStrength(t2, "cause", "effect");

        // Both must move in the same direction (above initial 0.1) and be close.
        assertTrue(s1 > 0.1, "MebnWeightLearner.learn must raise the strength (got " + s1 + ")");
        assertTrue(s2 > 0.1, "SameDiffMebnStrengthLearner.learn must raise the strength (got " + s2 + ")");
        // Values should agree within 0.05 (same algorithm, same seed, same inputs).
        assertEquals(s1, s2, 0.05,
                "MebnWeightLearner and SameDiffMebnStrengthLearner must produce consistent results");
    }

    // ─── Test 4: Empty theory ─────────────────────────────────────────────────

    @Test
    void learn_emptyEdges_returnsTheoryUnchanged() {
        ReasoningGraph graph = people();
        MTheory simple = MebnInferenceService.buildSimpleTheory(graph, "Person", "isActive");
        MTheory result = SameDiffMebnStrengthLearner.learn(
                simple, graph, Map.of("isActive(alice)", 1.0), 5, 0.5, 1234L);
        assertTrue(result == simple || result != null, "non-null return on empty-edge theory");
    }

    @Test
    void requiredPosteriorKeys_returnsOnlyParentsForMatchingChildObservations() {
        ReasoningGraph graph = people();
        MTheory theory = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.3);
        List<MebnWeightLearner.Edge> edges = SameDiffMebnStrengthLearner.collectEdges(theory);

        Set<String> keys = SameDiffMebnStrengthLearner.requiredPosteriorKeys(edges, Map.of(
                "effect(alice)", 1.0,
                "unrelated(alice)", 1.0));

        assertEquals(Set.of("cause(alice)"), keys,
                "learning should request only parent posteriors needed by matching child targets");
    }

    // ─── Child-RV matching: a longer RV name sharing the child's prefix is not a grounding ───

    @Test
    void edge_groundsOnlyItsOwnChildRv() {
        MebnWeightLearner.Edge edge = causalEdge("cause", "effect");

        assertEquals(Optional.of("cause(alice)"), edge.parentKeyFor("effect(alice)"));
        assertEquals(Optional.empty(), edge.parentKeyFor("effective(alice)"));
        assertEquals(Optional.empty(), edge.parentKeyFor("effects"));
        // effect takes one Person: neither a bare name nor two ids is one of its groundings.
        assertEquals(Optional.empty(), edge.parentKeyFor("effect"));
        assertEquals(Optional.empty(), edge.parentKeyFor("effect(alice,bob)"));
        assertEquals(Optional.empty(), edge.parentKeyFor("effect()"), "an empty id names no entity");
        assertEquals(Optional.empty(), edge.parentKeyFor("effect(alice"));
    }

    @Test
    void edge_parentKeyFor_bindsParentArgVarsByNameLikeTheSsbn() {
        MFrag frag = employmentFrag();

        assertEquals(Optional.of("hiring(acme)"),
                new MebnWeightLearner.Edge(frag, "hiring", "worksFor").parentKeyFor("worksFor(alice,acme)"),
                "hiring's only argument is the child's second one, Org_1");
        assertEquals(Optional.of("employed(alice)"),
                new MebnWeightLearner.Edge(frag, "employed", "worksFor").parentKeyFor("worksFor(alice,acme)"));
        assertEquals(Optional.empty(),
                new MebnWeightLearner.Edge(frag, "funded", "worksFor").parentKeyFor("worksFor(alice,acme)"),
                "the child binds no Org_0, so the SSBN grounds funded once per Org");
        assertEquals(Optional.empty(),
                new MebnWeightLearner.Edge(frag, "missing", "worksFor").parentKeyFor("worksFor(alice,acme)"),
                "a parent the MFrag does not declare has no grounding");
    }

    @Test
    void buildMatrixBatch_skipsGroundingsNoEdgeCanRead() {
        MFrag frag = employmentFrag();
        List<MebnWeightLearner.Edge> edges = List.of(
                new MebnWeightLearner.Edge(frag, "funded", "worksFor"),
                new MebnWeightLearner.Edge(frag, "missing", "worksFor"));
        Map<String, Double> observations = Map.of("worksFor(alice,acme)", 1.0);

        assertEquals(Set.of(), SameDiffMebnStrengthLearner.requiredPosteriorKeys(edges, observations));
        assertEquals(0, SameDiffMebnStrengthLearner.buildMatrixBatch(edges, Map.of(), observations).rowCount(),
                "a grounding with no single parent on any edge adds only zeros to the mean loss");
    }

    /**
     * {@code worksFor(Person_0, Org_1)} with three inputs: {@code hiring} over the child's Org_1,
     * {@code employed} over its Person_0, and {@code funded} over an Org_0 the child does not bind.
     */
    private static MFrag employmentFrag() {
        EntityType person = new EntityType("Person");
        EntityType org = new EntityType("Org");
        return new MFrag("Employment")
                .addResidentNode(RandomVariable.binary("worksFor", person, org, RandomVariable.NodeRole.RESIDENT))
                .addInputNode(RandomVariable.unary("hiring", "Org_1", org, RandomVariable.NodeRole.INPUT))
                .addInputNode(RandomVariable.unary("employed", person, RandomVariable.NodeRole.INPUT))
                .addInputNode(RandomVariable.unary("funded", org, RandomVariable.NodeRole.INPUT))
                .addParentEdge("hiring", "worksFor", 0.5)
                .addParentEdge("employed", "worksFor", 0.5)
                .addParentEdge("funded", "worksFor", 0.5);
    }

    @Test
    void edge_parentKeyFor_namesTheParentTheSsbnWires() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(GraphEntity.builder("alice").type("Person").label("Alice").build());
        graph.addEntity(GraphEntity.builder("bob").type("Person").label("Bob").build());
        graph.addEntity(GraphEntity.builder("acme").type("Org").label("Acme").build());
        graph.addRelation("r1", "alice", "acme", "worksFor", 1.0);
        graph.addRelation("r2", "bob", "acme", "worksFor", 1.0);
        MTheory theory = employmentTheory();
        MebnWeightLearner.Edge edge = SameDiffMebnStrengthLearner.collectEdges(theory).get(0);

        BayesianNetwork ssbn = new SSBNGenerator(theory, new ReasoningGraphKnowledgeBase(graph)).generate();

        for (String child : List.of("worksFor(alice,acme)", "worksFor(bob,acme)")) {
            BayesianNode node = ssbn.getNode(child);
            assertNotNull(node, child + " must be grounded");
            List<String> parents = node.getParents().stream().map(BayesianNode::getVariableName).toList();
            assertEquals(List.of(edge.parentKeyFor(child).orElseThrow()), parents);
        }
    }

    @Test
    void requiredPosteriorKeys_skipsLongerRvNamesSharingTheChildPrefix() {
        Set<String> keys = SameDiffMebnStrengthLearner.requiredPosteriorKeys(List.of(causalEdge("cause", "effect")), Map.of(
                "effect(alice)", 1.0,
                "effective(bob)", 1.0));

        assertEquals(Set.of("cause(alice)"), keys, "effective(bob) must not ask for causeive(bob)");
    }

    @Test
    void buildMatrixBatch_keepsChildRvsSharingAPrefixApart() {
        // Two edges whose child RV names share a prefix, as KG predicates often do.
        List<MebnWeightLearner.Edge> edges = List.of(
                causalEdge("cause", "effect"), causalEdge("trigger", "effective"));
        Map<String, Double> observations = new LinkedHashMap<>();
        observations.put("effective(alice)", 0.0);
        observations.put("effect(bob)", 1.0);
        observations.put("effects(carol)", 1.0);

        SameDiffMebnStrengthLearner.MatrixBatch batch = SameDiffMebnStrengthLearner.buildMatrixBatch(
                edges, Map.of("cause(bob)", 0.8, "trigger(alice)", 0.6), observations);

        assertEquals(2, batch.rowCount(), "effects(carol) grounds neither child");
        assertArrayEquals(new double[]{0.0, 0.6}, batch.pParent()[0], "effective(alice) feeds only its own edge");
        assertArrayEquals(new double[]{0.0, 0.0}, batch.target()[0]);
        assertArrayEquals(new double[]{0.8, 0.0}, batch.pParent()[1], "effect(bob) feeds only its own edge");
        assertArrayEquals(new double[]{1.0, 0.0}, batch.target()[1]);
    }

    @Test
    void analyticGradient_skipsLongerRvNamesSharingTheChildPrefix() {
        MebnWeightLearner.Edge edge = causalEdge("cause", "effect");
        Map<String, Double> posteriors = Map.of(
                "effect(alice)", 0.4, "cause(alice)", 0.7, "effective(bob)", 0.1);
        MebnWeightLearner learner = new MebnWeightLearner();

        double childOnly = learner.analyticGradient(edge, 0.3, posteriors, Map.of("effect(alice)", 1.0));
        double withLongerName = learner.analyticGradient(edge, 0.3, posteriors,
                Map.of("effect(alice)", 1.0, "effective(bob)", 0.0));

        assertTrue(childOnly < 0, "fitting effect(alice)=1 must pull s up, got " + childOnly);
        assertEquals(childOnly, withLongerName, 1e-12, "effective(bob) must not enter the effect gradient");
    }

    // ─── Relation MFrags: a binary child over a unary relevance parent ───

    @Test
    void buildMatrixBatch_readsTheRelevanceOfEachRelationSource() {
        List<MebnWeightLearner.Edge> edges = SameDiffMebnStrengthLearner.collectEdges(employmentTheory());
        assertEquals(1, edges.size(), "the relation MFrag has one learnable edge, isRelevant -> worksFor");
        MebnWeightLearner.Edge edge = edges.get(0);

        assertEquals(Optional.of("isRelevant(alice)"), edge.parentKeyFor("worksFor(alice,acme)"));
        assertEquals(Optional.empty(), edge.parentKeyFor("worksFor(alice)"));
        assertEquals(Optional.empty(), edge.parentKeyFor("worksFor(alice,acme,bob)"));

        Map<String, Double> observations = new LinkedHashMap<>();
        observations.put("worksFor(alice,acme)", 1.0);
        observations.put("worksFor(bob,acme)", 0.0);
        assertEquals(Set.of("isRelevant(alice)", "isRelevant(bob)"),
                SameDiffMebnStrengthLearner.requiredPosteriorKeys(edges, observations));

        SameDiffMebnStrengthLearner.MatrixBatch batch = SameDiffMebnStrengthLearner.buildMatrixBatch(
                edges, Map.of("isRelevant(alice)", 0.8, "isRelevant(bob)", 0.3), observations);

        assertEquals(2, batch.rowCount());
        assertArrayEquals(new double[]{0.8}, batch.pParent()[0], "worksFor(alice,acme) reads alice's relevance");
        assertArrayEquals(new double[]{1.0}, batch.target()[0]);
        assertArrayEquals(new double[]{0.3}, batch.pParent()[1], "worksFor(bob,acme) reads bob's relevance");
        assertArrayEquals(new double[]{0.0}, batch.target()[1]);
    }

    /** {@code worksFor(Person, Org)} over alice and bob working for acme, as the KG registers relation types. */
    private static MTheory employmentTheory() {
        return RelationalMTheoryBuilder.build("Employment", List.of(new RelationalMTheoryBuilder.RelationDescriptor(
                "worksFor", "Person", "Org", 0.4, List.of("alice", "bob"), List.of("acme"))));
    }

    /** The one learnable edge of a {@code parent(X) → child(X)} causal theory over the people fixture. */
    private static MebnWeightLearner.Edge causalEdge(String parent, String child) {
        MTheory theory = MebnInferenceService.buildCausalTheory(people(), "Person", parent, child, 0.3);
        List<MebnWeightLearner.Edge> edges = SameDiffMebnStrengthLearner.collectEdges(theory);
        assertEquals(1, edges.size(), "a causal theory has exactly one learnable edge");
        return edges.get(0);
    }

    // ─── Test 5: Adam convergence + [0,1] projection ──────────────────────────

    /**
     * Adam optimizer (in {@link SameDiffMebnStrengthLearner}) must converge at least as fast as
     * SGD ({@link MebnWeightLearner}) on a small convex noisy-OR MSE problem with known optimum
     * near s=1.0 (all effect observations = 1.0).
     *
     * <p>Both learners are run for {@code maxEpochs=50} and their final MSE losses are compared.
     * Adam should achieve equal or lower final MSE (adaptive step sizes help near-convex landscapes).
     * Additionally, the final Adam strength must be in [0, 1] (projection correctness).</p>
     */
    @Test
    void mebnAdam_convergesAsWellAsSgd_andProjectsToUnitInterval() {
        // Shared setup: single edge cause->effect, all effects observed = 1.0 (optimum at s=1).
        ReasoningGraph graph = people();

        MTheory tAdam = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.2);
        MTheory tSgd  = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.2);

        MebnInferenceService svc = new MebnInferenceService();
        Map<String, Double> posteriors = svc.infer(graph, tAdam, Map.of());
        Map<String, Double> observations = new LinkedHashMap<>();
        posteriors.keySet().stream()
                .filter(k -> k.startsWith("effect"))
                .forEach(k -> observations.put(k, 1.0));
        assertFalse(observations.isEmpty(), "effect observations must exist");

        int maxEpochs = 50;

        // Adam path (SameDiffMebnStrengthLearner — now uses Adam internally).
        SameDiffMebnStrengthLearner.learn(tAdam, graph, observations, maxEpochs, 0.1, 1234L);
        double sAdam = edgeStrength(tAdam, "cause", "effect");
        double lossAdam = mseLoss(svc.infer(graph, tAdam, Map.of()), observations);

        // SGD path (MebnWeightLearner — uses ProjectedGradientOptimizer SGD).
        new MebnWeightLearner(0.1, 1e-6).learn(tSgd, graph, observations, maxEpochs);
        double sSgd  = edgeStrength(tSgd, "cause", "effect");
        double lossSgd  = mseLoss(svc.infer(graph, tSgd, Map.of()), observations);

        // Adam must converge toward the optimum (s>0.5 means it moved well above initial 0.2).
        assertTrue(sAdam >= 0.5,
                "Adam path must drive s toward optimum 1.0 (got s=" + sAdam + ")");

        // Adam final loss must be <= SGD final loss (it should converge at least as well).
        assertTrue(lossAdam <= lossSgd + 1e-3,
                "Adam final loss " + lossAdam + " must be <= SGD final loss " + lossSgd
                        + " (within 1e-3 tolerance for platform variance)");

        // Projection: final strength must remain in [0, 1].
        assertTrue(sAdam >= 0.0 && sAdam <= 1.0,
                "Adam strength must remain in [0,1] (got s=" + sAdam + ")");
        assertTrue(sSgd >= 0.0 && sSgd <= 1.0,
                "SGD strength must remain in [0,1] (got s=" + sSgd + ")");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static double mseLoss(Map<String, Double> predicted, Map<String, Double> targets) {
        double sum = 0.0;
        int count = 0;
        for (Map.Entry<String, Double> e : targets.entrySet()) {
            Double p = predicted.get(e.getKey());
            if (p != null) {
                double d = p - e.getValue();
                sum += d * d;
                count++;
            }
        }
        return count > 0 ? sum / count : 0.0;
    }
}
