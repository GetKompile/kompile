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

import ai.kompile.graph.reasoning.fol.FindingStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.MebnInferenceService;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests MEBN noisy-OR edge-strength learning — the MEBN counterpart to PSL weight learning, closing
 * the gap where MEBN CPT strengths were hand-set.
 */
class MebnWeightLearnerTest {

    private ReasoningGraph people() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(GraphEntity.builder("alice").type("Person").label("Alice").build());
        g.addEntity(GraphEntity.builder("bob").type("Person").label("Bob").build());
        g.addEntity(GraphEntity.builder("carol").type("Person").label("Carol").build());
        return g;
    }

    /** The causal strength of the {@code parent → child} edge, read from whichever MFrag owns it. */
    private static double edgeStrength(MTheory theory, String parent, String child) {
        String key = parent + "->" + child;
        for (MFrag m : theory.getMFrags()) {
            if (m.getEdgeStrengths().containsKey(key)) {
                return m.getEdgeStrength(parent, child);
            }
        }
        return Double.NaN;
    }

    private static double sse(Map<String, Double> predicted, Map<String, Double> target) {
        double sum = 0.0;
        for (Map.Entry<String, Double> t : target.entrySet()) {
            Double p = predicted.get(t.getKey());
            if (p != null) {
                double d = p - t.getValue();
                sum += d * d;
            }
        }
        return sum;
    }

    @Test
    void learn_fitsEdgeStrengths_reducesErrorTowardObservations() {
        ReasoningGraph graph = people();
        // effect(X) depends on cause(X) via one learnable noisy-OR edge, initially weak (0.1).
        MTheory theory = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.1);
        MebnInferenceService svc = new MebnInferenceService();

        Map<String, Double> before = svc.infer(graph, theory, Map.of());
        // Observe every effect(X) as active — the noisy-OR strength must rise to fit it.
        Map<String, Double> observations = new LinkedHashMap<>();
        before.keySet().stream().filter(v -> v.startsWith("effect")).forEach(v -> observations.put(v, 1.0));
        assertFalse(observations.isEmpty(), "effect RVs are surfaced and targetable");
        double lossBefore = sse(before, observations);
        double strengthBefore = edgeStrength(theory, "cause", "effect");

        new MebnWeightLearner(0.5, 1e-3).learn(theory, graph, observations, 40);

        double lossAfter = sse(svc.infer(graph, theory, Map.of()), observations);
        double strengthAfter = edgeStrength(theory, "cause", "effect");
        assertTrue(lossAfter < lossBefore,
                "noisy-OR edge-strength learning reduces the error toward observed targets ("
                        + lossBefore + " -> " + lossAfter + ")");
        assertTrue(strengthAfter > strengthBefore,
                "fitting effect=TRUE raises the cause->effect strength ("
                        + strengthBefore + " -> " + strengthAfter + ")");
    }

    @Test
    void learnedStrengths_travelOntoMebnFacts_asRuleWeights() {
        ReasoningGraph graph = people();
        MTheory theory = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.1);
        MebnInferenceService svc = new MebnInferenceService();

        // Observe every effect(X) active and learn the noisy-OR strength up from its weak prior.
        Map<String, Double> observations = new LinkedHashMap<>();
        svc.infer(graph, theory, Map.of()).keySet().stream()
                .filter(v -> v.startsWith("effect")).forEach(v -> observations.put(v, 1.0));
        new MebnWeightLearner(0.5, 1e-3).learn(theory, graph, observations, 40);
        double learned = edgeStrength(theory, "cause", "effect");

        // A MEBN run under the learned theory emits probabilistic facts; effect facts must carry the
        // learned cause->effect strength as a rule weight — exactly as PSL facts carry learned weights.
        List<InferredFact> facts = svc.inferFacts(graph, theory, new FindingStore());
        InferredFact effectFact = facts.stream()
                .filter(f -> f.atomKey().startsWith("effect"))
                .findFirst().orElseThrow();
        Map<String, Double> weights = effectFact.ruleWeights();
        assertFalse(weights.isEmpty(),
                "effect facts carry the learned cause->effect strength as a rule weight");
        assertEquals(learned, weights.values().iterator().next(), 0.01,
                "the fact's learned weight matches the fitted edge strength");

        // Prior-only cause facts have no incoming edge → no learned weight.
        InferredFact causeFact = facts.stream()
                .filter(f -> f.atomKey().startsWith("cause"))
                .findFirst().orElseThrow();
        assertTrue(causeFact.ruleWeights().isEmpty(), "prior RVs carry no learned weight");
    }

    @Test
    void learn_noParentEdges_returnsTheoryUnchanged() {
        ReasoningGraph graph = people();
        // A unary "simple" theory has no parent edges to learn.
        MTheory simple = MebnInferenceService.buildSimpleTheory(graph, "Person", "isActive");

        MTheory result = new MebnWeightLearner().learn(simple, graph, Map.of("isActive(alice)", 1.0), 10);

        assertSame(simple, result, "a theory with no learnable edges is returned unchanged");
    }
}
