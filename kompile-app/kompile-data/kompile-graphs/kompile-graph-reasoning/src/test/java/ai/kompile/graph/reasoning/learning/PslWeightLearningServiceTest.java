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

import ai.kompile.graph.reasoning.fol.FolInferenceService;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the production weight-learning entry point: fitting rule weights to labeled ground truth,
 * applying them back onto a program ready for inference, and persisting them as JSON.
 */
class PslWeightLearningServiceTest {

    private PslProgram program() {
        PslProgram p = new PslProgram();
        p.addRule("1.0: State(X) & Link(X, Y) -> State(Y) ^2");
        p.addRule("0.5: ~State(N) ^2");
        p.observe("State", 1.0, "alice");
        p.observe("Link", 1.0, "alice", "bob");
        p.target("State", "bob");
        p.target("State", "carol");
        return p;
    }

    private Map<String, Double> groundTruth() {
        return Map.of("State(bob)", 1.0, "State(carol)", 0.0);
    }

    @Test
    void incremental_persistReloadUpdate_warmStartsFromSavedWeights() {
        PslWeightLearningService service = new PslWeightLearningService();

        // Learn on the full data, then persist the learned weights as JSON and parse them back.
        List<PslRule> learned = service.learn(program(), groundTruth());
        Map<String, Double> saved =
                PslWeightLearningService.parseWeights(PslWeightLearningService.weightsToJson(learned));
        assertFalse(saved.isEmpty(), "learned weights serialize to JSON and parse back");

        // Re-load the saved weights onto a FRESH program — the load counterpart to weightsToJson.
        PslProgram warm = PslWeightLearningService.applyWeights(program(), saved);
        for (PslRule r : warm.rules()) {
            if (saved.containsKey(r.toString())) {
                assertEquals(saved.get(r.toString()), r.weight(), 1e-9,
                        "re-loaded weight round-trips exactly for " + r);
            }
        }

        // An incremental mini-batch update continues from the warm-started weights: structure is
        // preserved and it does not reset to convergence.
        PslProgram updated = service.updateOnBatch(warm, Map.of("State(bob)", 1.0), 5);
        assertEquals(warm.rules().size(), updated.rules().size(),
                "incremental update preserves rule structure");
        assertTrue(updated.rules().stream().allMatch(r -> r.weight() >= 0.0),
                "incrementally-updated weights stay non-negative");
    }

    @Test
    void learn_fitsRuleWeightsToGroundTruth_nonNegative() {
        PslWeightLearningService service =
                new PslWeightLearningService(new StructuredPerceptronLearner(0.1, 1e-4), 50);
        PslProgram program = program();
        List<PslRule> before = List.copyOf(program.rules());

        List<PslRule> learned = service.learn(program, groundTruth());

        assertEquals(before.size(), learned.size(), "same rules, new weights");
        boolean changed = false;
        for (int i = 0; i < learned.size(); i++) {
            if (Math.abs(learned.get(i).weight() - before.get(i).weight()) > 1e-9) {
                changed = true;
            }
            assertTrue(learned.get(i).weight() >= 0.0, "weights stay non-negative");
        }
        assertTrue(changed, "learning adjusted at least one rule weight toward the labels");
    }

    @Test
    void learnAndApply_returnsProgramWithLearnedWeights_readyToInfer() {
        PslWeightLearningService service = new PslWeightLearningService();
        PslProgram learnedProgram = service.learnAndApply(program(), groundTruth());

        // Atom declarations preserved, rules carry learned weights, and it still solves.
        assertTrue(learnedProgram.targetKeys().contains("State(bob)"));
        assertTrue(learnedProgram.targetKeys().contains("State(carol)"));
        assertEquals(program().rules().size(), learnedProgram.rules().size());

        HlMrfMapInference.Result result = HlMrfMapInference.solve(learnedProgram);
        assertNotNull(result.values().get("State(bob)"), "rebuilt program infers the target");
    }

    @Test
    void weights_jsonRoundTrips() {
        List<PslRule> learned = new PslWeightLearningService().learn(program(), groundTruth());

        String json = PslWeightLearningService.weightsToJson(learned);
        Map<String, Double> parsed = PslWeightLearningService.parseWeights(json);

        assertEquals(learned.size(), parsed.size());
        for (PslRule rule : learned) {
            assertEquals(rule.weight(), parsed.get(rule.toString()), 1e-9,
                    "each learned weight survives the JSON round-trip");
        }
    }

    // ─── Phase 3: facts carry learned weights end-to-end ────────────────────────────

    @Test
    void inferFactsUnderLearnedWeights_factsCarryLearnedWeightProvenance() {
        PslWeightLearningService learning =
                new PslWeightLearningService(new StructuredPerceptronLearner(0.1, 1e-4), 50);
        PslProgram learnedProgram = learning.learnAndApply(program(), groundTruth());

        List<InferredFact> facts = new FolInferenceService().inferFacts(learnedProgram);

        assertFalse(facts.isEmpty(), "inference under learned weights still emits facts");
        boolean anyWeighted = facts.stream().anyMatch(f -> !f.ruleWeights().isEmpty());
        assertTrue(anyWeighted,
                "facts inferred under a learned-weight program expose the learned weights via ruleWeights()");
    }

    @Test
    void inferredFact_ruleWeights_parsesWeightPrefix_skipsHardRules() {
        InferredFact fact = InferredFact.of("State(b)", 0.8,
                List.of(),
                List.of("3.25: State(a) & Link(a,b) -> State(b) ^2", "hard: Foo(x) -> Bar(x)"),
                "run-1", 1L);

        Map<String, Double> weights = fact.ruleWeights();
        assertEquals(1, weights.size(), "only the weight-prefixed rule contributes");
        assertEquals(3.25, weights.get("3.25: State(a) & Link(a,b) -> State(b) ^2"), 1e-9);
    }
}
