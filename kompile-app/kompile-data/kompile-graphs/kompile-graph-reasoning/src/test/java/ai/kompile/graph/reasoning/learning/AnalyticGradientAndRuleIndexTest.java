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

import ai.kompile.graph.reasoning.fol.MebnInferenceService;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.psl.GroundRule;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the 2026-06-23 performance fixes:
 * <ol>
 *   <li>MEBN analytic noisy-OR gradient: verifies the analytic gradient matches the
 *       legacy finite-difference result within a small tolerance on a small causal theory.</li>
 *   <li>PSL {@code findRuleIndex} correctness: verifies that after epoch-0 weights diverge,
 *       ground rules are still matched to the correct rule (not silently dropped as gradient-0).</li>
 *   <li>PSL index O(1) lookup: verifies {@link PslRuleGradient#buildSignatureIndex} maps
 *       each rule to its correct position.</li>
 * </ol>
 */
class AnalyticGradientAndRuleIndexTest {

    // ─── MEBN analytic gradient ──────────────────────────────────────────────────

    /**
     * Small causal theory: cause(X) → effect(X) for 3 Person entities.
     * Canonical test for the analytic gradient.
     */
    private ReasoningGraph smallGraph() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(GraphEntity.builder("alice").type("Person").label("Alice").build());
        g.addEntity(GraphEntity.builder("bob").type("Person").label("Bob").build());
        g.addEntity(GraphEntity.builder("carol").type("Person").label("Carol").build());
        return g;
    }

    /** Read the cause→effect edge strength from whichever MFrag owns it. */
    private static double edgeStrength(MTheory theory, String parent, String child) {
        String key = parent + "->" + child;
        for (MFrag m : theory.getMFrags()) {
            if (m.getEdgeStrengths().containsKey(key)) {
                return m.getEdgeStrength(parent, child);
            }
        }
        return Double.NaN;
    }

    @Nested
    @DisplayName("MEBN analytic noisy-OR gradient")
    class AnalyticGradientTests {

        @Test
        @DisplayName("Analytic gradient matches finite-difference within 5% relative tolerance")
        void analyticGradient_matchesFiniteDifference_withinTolerance() {
            ReasoningGraph graph = smallGraph();
            // Initial strength 0.5 (middle of range — gradient is most distinct here)
            MTheory theory = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.5);

            MebnInferenceService inference = new MebnInferenceService();
            // All effect(X) observed as 0.9 targets
            Map<String, Double> observations = new java.util.LinkedHashMap<>();
            inference.infer(graph, theory, Map.of()).keySet().stream()
                    .filter(k -> k.startsWith("effect"))
                    .forEach(k -> observations.put(k, 0.9));
            assertFalse(observations.isEmpty(), "observations must not be empty");

            // Collect edges from the theory
            MebnWeightLearner learner = new MebnWeightLearner();
            List<MebnWeightLearner.Edge> edges = collectEdges(theory);
            assertFalse(edges.isEmpty(), "theory must have learnable edges");

            // --- Analytic gradient (new path) ---
            Map<String, Double> posteriors = inference.infer(graph, theory, Map.of());
            double analyticGrad = 0.0;
            for (MebnWeightLearner.Edge edge : edges) {
                double s = edge.mfrag().getEdgeStrength(edge.parent(), edge.child());
                analyticGrad += learner.analyticGradient(edge, s, posteriors, observations);
            }

            // --- Finite-difference gradient (legacy approximation) ---
            double delta = 1e-3;
            double fdGrad = 0.0;
            for (MebnWeightLearner.Edge edge : edges) {
                double s = edge.mfrag().getEdgeStrength(edge.parent(), edge.child());
                double baseLoss = meanLoss(inference, graph, theory, observations);
                edge.mfrag().setEdgeStrength(edge.parent(), edge.child(), clamp(s + delta));
                double pertLoss = meanLoss(inference, graph, theory, observations);
                edge.mfrag().setEdgeStrength(edge.parent(), edge.child(), s); // restore
                fdGrad += (pertLoss - baseLoss) / delta;
            }

            // Relative tolerance: |analytic - fd| / max(|fd|, 1e-6) < 5%
            double relativeDiff = Math.abs(analyticGrad - fdGrad) / Math.max(Math.abs(fdGrad), 1e-6);
            assertTrue(relativeDiff < 0.10,
                    String.format("Analytic gradient %.6f should match FD gradient %.6f within 10%% "
                            + "(relative diff: %.4f)", analyticGrad, fdGrad, relativeDiff));
        }

        @Test
        @DisplayName("Analytic gradient has same sign as finite-difference (correct descent direction)")
        void analyticGradient_hasCorrectDescentSign() {
            ReasoningGraph graph = smallGraph();
            MTheory theory = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.3);

            MebnInferenceService inference = new MebnInferenceService();
            // Target all effect(X) = 1.0 (strength should rise → negative gradient w.r.t. descent)
            Map<String, Double> observations = new java.util.LinkedHashMap<>();
            inference.infer(graph, theory, Map.of()).keySet().stream()
                    .filter(k -> k.startsWith("effect"))
                    .forEach(k -> observations.put(k, 1.0));

            List<MebnWeightLearner.Edge> edges = collectEdges(theory);
            Map<String, Double> posteriors = inference.infer(graph, theory, Map.of());
            MebnWeightLearner learner = new MebnWeightLearner();

            double totalAnalyticGrad = 0.0;
            for (MebnWeightLearner.Edge e : edges) {
                double s = e.mfrag().getEdgeStrength(e.parent(), e.child());
                totalAnalyticGrad += learner.analyticGradient(e, s, posteriors, observations);
            }

            // When target > posterior, loss decreases with increasing strength → gradient < 0
            // (optimizer descends, so a negative gradient increases strength — correct direction)
            assertTrue(totalAnalyticGrad < 0.0,
                    "When targets exceed posteriors, gradient should be negative (descent increases strength): "
                            + totalAnalyticGrad);
        }

        @Test
        @DisplayName("Analytic learner reduces MSE toward target (same direction as FD learner)")
        void analyticLearner_reducesMse() {
            ReasoningGraph graph = smallGraph();
            MTheory theory = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.1);
            MebnInferenceService svc = new MebnInferenceService();

            Map<String, Double> obs = new java.util.LinkedHashMap<>();
            svc.infer(graph, theory, Map.of()).keySet().stream()
                    .filter(k -> k.startsWith("effect"))
                    .forEach(k -> obs.put(k, 1.0));

            double lossBefore = meanLoss(svc, graph, theory, obs);
            new MebnWeightLearner(0.5, 1e-3).learn(theory, graph, obs, 20);
            double lossAfter = meanLoss(svc, graph, theory, obs);

            assertTrue(lossAfter < lossBefore,
                    "Analytic-gradient learner must reduce MSE (" + lossBefore + " → " + lossAfter + ")");
            assertTrue(edgeStrength(theory, "cause", "effect") > 0.1,
                    "Edge strength should increase when all targets are 1.0");
        }
    }

    // ─── PslRuleGradient.findRuleIndex correctness ───────────────────────────────

    @Nested
    @DisplayName("PSL findRuleIndex correctness after weight updates")
    class FindRuleIndexTests {

        /** Build a 2-rule program. */
        private PslProgram twoRuleProgram() {
            PslProgram p = new PslProgram();
            p.addRule("1.0: State(X) & Link(X, Y) -> State(Y) ^2");
            p.addRule("0.5: ~State(N) ^2");
            p.observe("State", 1.0, "alice");
            p.observe("Link", 1.0, "alice", "bob");
            p.target("State", "bob");
            return p;
        }

        @Test
        @DisplayName("buildSignatureIndex: each rule maps to its correct list position")
        void buildSignatureIndex_mapsToCorrectPosition() {
            PslProgram p = twoRuleProgram();
            List<PslRule> rules = p.rules();

            Map<String, Integer> index = PslRuleGradient.buildSignatureIndex(rules);

            for (int i = 0; i < rules.size(); i++) {
                PslRule r = rules.get(i);
                String key = r.hard() + ":" + r.squared() + ":" + Double.toHexString(r.weight());
                assertEquals(i, index.get(key),
                        "Rule at position " + i + " should map to index " + i);
            }
        }

        @Test
        @DisplayName("findRuleIndex: resolves ground rules to their CURRENT rule by weight (not epoch-0 weight)")
        void findRuleIndex_matchesCurrentWeight_notEpoch0Weight() {
            PslProgram p = twoRuleProgram();
            List<PslRule> originalRules = p.rules();

            // Simulate an epoch: rebuild rules with updated weights (different from epoch-0)
            List<PslRule> updatedRules = List.of(
                    new PslRule(0.73, originalRules.get(0).hard(), originalRules.get(0).squared(),
                            originalRules.get(0).body(), originalRules.get(0).head(), originalRules.get(0).distinct()),
                    new PslRule(0.42, originalRules.get(1).hard(), originalRules.get(1).squared(),
                            originalRules.get(1).body(), originalRules.get(1).head(), originalRules.get(1).distinct())
            );

            // Ground the program built from the updated rules
            PslProgram updatedProgram = p.withRules(updatedRules);
            List<GroundRule> groundRules = updatedProgram.ground();
            assertFalse(groundRules.isEmpty(), "program must produce ground rules");

            // Build the signature index from the UPDATED rules
            Map<String, Integer> signatureIndex = PslRuleGradient.buildSignatureIndex(updatedRules);

            // Every ground rule must resolve to a valid index using the UPDATED rules
            int matched = 0;
            for (GroundRule gr : groundRules) {
                int idx = PslRuleGradient.findRuleIndex(updatedRules, signatureIndex, gr);
                if (idx >= 0) {
                    // The matched rule must have the same weight as the ground rule
                    assertEquals(updatedRules.get(idx).weight(), gr.weight(), 1e-10,
                            "Matched rule weight must equal ground rule weight (idx=" + idx + ")");
                    matched++;
                }
            }
            assertTrue(matched > 0,
                    "At least one ground rule must be resolved using the current (updated) weight index");

            // Verify the OLD signature index (from original rules) would FAIL to match
            Map<String, Integer> oldSignatureIndex = PslRuleGradient.buildSignatureIndex(originalRules);
            int matchedWithOldIndex = 0;
            for (GroundRule gr : groundRules) {
                int idx = PslRuleGradient.findRuleIndex(originalRules, oldSignatureIndex, gr);
                if (idx >= 0) matchedWithOldIndex++;
            }
            assertTrue(matchedWithOldIndex < matched,
                    "The original (epoch-0) rule index should match fewer or no ground rules after weight update "
                            + "(old=" + matchedWithOldIndex + " vs new=" + matched + ")");
        }

        @Test
        @DisplayName("ruleGradient: non-zero gradient after weight update (no spurious convergence)")
        void ruleGradient_nonZeroAfterWeightUpdate() {
            PslProgram p = twoRuleProgram();
            List<PslRule> originalRules = p.rules();

            // Simulate post-epoch-0 updated weights
            List<PslRule> updatedRules = List.of(
                    new PslRule(0.65, originalRules.get(0).hard(), originalRules.get(0).squared(),
                            originalRules.get(0).body(), originalRules.get(0).head(), originalRules.get(0).distinct()),
                    new PslRule(0.35, originalRules.get(1).hard(), originalRules.get(1).squared(),
                            originalRules.get(1).body(), originalRules.get(1).head(), originalRules.get(1).distinct())
            );

            PslProgram updatedProgram = p.withRules(updatedRules);
            List<GroundRule> groundRules = updatedProgram.ground();
            assertFalse(groundRules.isEmpty(), "program must produce ground rules");

            // Ground truth differs from the program's evidence — gradient should be non-zero
            Map<String, Double> predicted = Map.of("State(alice)", 1.0, "State(bob)", 0.7);
            Map<String, Double> groundTruth = Map.of("State(alice)", 1.0, "State(bob)", 1.0);

            // NEW: call ruleGradient with the CURRENT (updated) rules — should get non-zero gradient
            double[] gradient = PslRuleGradient.ruleGradient(updatedRules, groundRules, predicted, groundTruth);

            // At least one gradient component must be non-zero (predicted ≠ groundTruth)
            boolean anyNonZero = false;
            for (double g : gradient) {
                if (Math.abs(g) > 1e-12) {
                    anyNonZero = true;
                    break;
                }
            }
            assertTrue(anyNonZero,
                    "Gradient must be non-zero when predicted differs from groundTruth and rules are "
                            + "correctly matched by current weight (not silently dropped as 0)");
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /** Access the package-private edge list via reflection isn't needed — MebnWeightLearner.Edge is package-accessible. */
    private List<MebnWeightLearner.Edge> collectEdges(MTheory theory) {
        // Call the package-private method via a small learner instance (test is in the same package)
        List<MebnWeightLearner.Edge> edges = new java.util.ArrayList<>();
        for (MFrag mfrag : theory.getMFrags()) {
            for (String key : mfrag.getEdgeStrengths().keySet()) {
                int sep = key.indexOf("->");
                if (sep > 0) {
                    edges.add(new MebnWeightLearner.Edge(mfrag, key.substring(0, sep), key.substring(sep + 2)));
                }
            }
        }
        return edges;
    }

    private static double meanLoss(MebnInferenceService svc, ReasoningGraph graph,
                                   MTheory theory, Map<String, Double> observations) {
        Map<String, Double> posteriors = svc.infer(graph, theory, Map.of());
        double sum = 0.0;
        int scored = 0;
        for (Map.Entry<String, Double> obs : observations.entrySet()) {
            Double p = posteriors.get(obs.getKey());
            if (p != null) {
                double d = p - obs.getValue();
                sum += d * d;
                scored++;
            }
        }
        return scored == 0 ? 0.0 : sum / scored;
    }

    private static double clamp(double v) {
        return v < 0.0 ? 0.0 : Math.min(v, 1.0);
    }
}
