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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        // The fresh program carries the default weights, so its rule displays differ from the
        // saved keys; every rule must still take its learned weight.
        List<PslRule> defaults = program().rules();
        boolean moved = false;
        for (int i = 0; i < learned.size(); i++) {
            moved |= learned.get(i).weight() != defaults.get(i).weight();
        }
        assertTrue(moved, "learning moved at least one weight off its default, so the reload is observable");
        PslProgram warm = PslWeightLearningService.applyWeights(program(), saved);
        assertEquals(learned.size(), warm.rules().size());
        for (int i = 0; i < learned.size(); i++) {
            assertEquals(learned.get(i).weight(), warm.rules().get(i).weight(),
                    "re-loaded weight round-trips exactly for " + learned.get(i));
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

    @Test
    void applyWeights_findsARuleWhateverWeightItWasSavedWith() {
        // A persisted key is the rule display at save time, weight prefix included, while the
        // program it is re-applied to was rebuilt with its default weight.
        PslRule propagation = program().rules().get(0);
        PslRule learnedPropagation = new PslRule(2.5, propagation.hard(), propagation.squared(),
                propagation.body(), propagation.head(), propagation.distinct());
        assertNotEquals(propagation.toString(), learnedPropagation.toString());

        PslProgram warm = PslWeightLearningService.applyWeights(program(),
                Map.of(learnedPropagation.toString(), 2.5));

        assertEquals(2.5, warm.rules().get(0).weight(), "the propagation rule takes its saved weight");
        assertEquals(0.5, warm.rules().get(1).weight(), "a rule with no saved entry keeps its weight");
    }

    @Test
    void applyWeights_matchesTheWholeStructure_notJustBodyAndHead() {
        PslRule soft = program().rules().get(0);
        PslRule hardTwin = PslRule.hard(soft.body(), soft.head());
        PslProgram program = program().withRules(List.of(soft, program().rules().get(1), hardTwin));

        // Same body and head with a linear hinge is a different rule: nothing changes.
        PslRule linearTwin = new PslRule(4.0, false, false, soft.body(), soft.head(), soft.distinct());
        PslProgram unchanged = PslWeightLearningService.applyWeights(program,
                Map.of(linearTwin.toString(), 4.0));
        assertEquals(displays(program), displays(unchanged));

        // The soft rule's own entry re-weights it and leaves its hard twin a hard constraint.
        PslRule reweighted = new PslRule(3.0, false, soft.squared(), soft.body(), soft.head(), soft.distinct());
        PslProgram warm = PslWeightLearningService.applyWeights(program,
                Map.of(reweighted.toString(), 3.0));
        assertEquals(3.0, warm.rules().get(0).weight());
        assertEquals(hardTwin.toString(), warm.rules().get(2).toString());
        assertTrue(warm.rules().get(2).hard());
    }

    @Test
    void withoutWeight_stripsOnlyANumericWeightPrefix() {
        assertEquals("A(X) -> B(X) ^2", PslRule.withoutWeight("2.5: A(X) -> B(X) ^2"));
        assertEquals("A(X) -> B(X) ^2", PslRule.withoutWeight("1.0E-4: A(X) -> B(X) ^2"),
                "a small learned weight renders in exponent form");
        assertEquals("A(X) -> B(X) .", PslRule.withoutWeight("A(X) -> B(X) ."),
                "a hard rule has no weight prefix");
        assertEquals("note: A(X) -> B(X) ^2", PslRule.withoutWeight("note: A(X) -> B(X) ^2"),
                "text before ':' that is not a number belongs to the rule");
        assertEquals(PslRule.parse("1.0: A(X) -> B(X) ^2").signature(),
                PslRule.parse("7.25: A(X) -> B(X) ^2").signature(),
                "the weight is not part of the signature");
    }

    private static List<String> displays(PslProgram program) {
        return program.rules().stream().map(PslRule::toString).toList();
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
