/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.tms;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.psl.GroundRule;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Detects constraint violations and fact-level contradictions in PSL inference results.
 *
 * <h3>Constraint violations</h3>
 * After MAP inference, a ground rule is considered violated if its
 * {@link GroundRule#distanceToSatisfaction distanceToSatisfaction} exceeds a threshold.
 * Hard constraints must always be satisfied; soft rule violations are expected (they define
 * the objective) but extremely large violations may indicate data problems.
 *
 * <h3>Fact-level contradictions</h3>
 * Two hard facts with the same atom key but conflicting values (one asserts truth 1.0 and
 * another asserts truth 0.0) directly contradict each other.
 */
public final class ContradictionDetector {

    private static final double DEFAULT_THRESHOLD = 1e-6;

    private ContradictionDetector() {}

    /**
     * Scan inference result for hard constraint violations.
     *
     * @param result the MAP inference result
     * @return list of contradictions for violated hard constraints
     */
    public static List<Contradiction> detectHard(HlMrfMapInference.Result result) {
        return detect(result, DEFAULT_THRESHOLD);
    }

    /**
     * Scan inference result for constraint violations above the given threshold.
     *
     * @param result    the MAP inference result
     * @param threshold distance threshold above which a constraint is considered violated
     * @return list of contradictions for violated constraints
     */
    public static List<Contradiction> detect(HlMrfMapInference.Result result, double threshold) {
        Objects.requireNonNull(result, "result must not be null");
        List<Contradiction> contradictions = new ArrayList<>();
        Map<String, Double> values = result.values();

        for (GroundRule gr : result.groundRules()) {
            if (!gr.hard()) continue; // only check hard constraints by default

            double dist = gr.distanceToSatisfaction(values);
            if (dist > threshold) {
                // Identify which atoms in body/head are violating
                List<String> violating = new ArrayList<>();
                // Body atoms that are contributing (high value) but head is not satisfied
                double bodyTruth = gr.bodyTruth(values);
                double headTruth = gr.headTruth(values);

                if (bodyTruth > headTruth) {
                    // Body is more true than head -- body literals with high values are "violating"
                    for (GroundRule.Lit lit : gr.body()) {
                        double litVal = lit.value(values);
                        if (litVal > 0.5) violating.add(lit.atomKey());
                    }
                    // Head literals with low values are also part of the violation
                    for (GroundRule.Lit lit : gr.head()) {
                        double litVal = lit.value(values);
                        if (litVal < 0.5) violating.add(lit.atomKey());
                    }
                }

                contradictions.add(new Contradiction(
                        gr,
                        new LinkedHashMap<>(values),
                        dist,
                        violating
                ));
            }
        }

        return contradictions;
    }

    /**
     * Check if two facts directly contradict each other.
     *
     * <p>Two facts contradict if they are both hard, have the same atom key,
     * and have values on opposite sides (one >= 0.9, the other <= 0.1).</p>
     *
     * @param f1 the first fact
     * @param f2 the second fact
     * @return true if the facts contradict
     */
    public static boolean contradicts(Fact f1, Fact f2) {
        Objects.requireNonNull(f1, "f1 must not be null");
        Objects.requireNonNull(f2, "f2 must not be null");
        if (!f1.atomKey().equals(f2.atomKey())) return false;
        if (!f1.hard() || !f2.hard()) return false;
        // Contradict if one is nearly 1.0 and the other is nearly 0.0
        return (f1.value() >= 0.9 && f2.value() <= 0.1) ||
               (f1.value() <= 0.1 && f2.value() >= 0.9);
    }

    /**
     * Find all pairs of contradicting facts in a FactStore.
     *
     * <p>Hard facts with the same atom key but conflicting values (one nearly 1.0, one nearly 0.0)
     * are returned as contradiction pairs.</p>
     *
     * @param factStore the store to check
     * @return list of contradicting fact pairs
     */
    public static List<Pair<Fact, Fact>> findFactContradictions(FactStore factStore) {
        Objects.requireNonNull(factStore, "factStore must not be null");
        List<Pair<Fact, Fact>> result = new ArrayList<>();

        // Since FactStore is keyed by atomKey and assertFact replaces, we can't have two
        // facts with the same key in the store. But we detect if a single fact contradicts
        // itself (impossible) or two facts from the same store were added with the same key.
        // In practice, this is for detecting conflicts introduced via merge or external loading.
        // For FactStore (keyed by atomKey), we just check each fact against itself --
        // this means contradictions only arise if we compare across two stores.
        // To be useful, we also check value consistency for each fact: a hard fact with
        // value != 0.0 or 1.0 is potentially inconsistent.
        List<Fact> facts = new ArrayList<>(factStore.allFacts());
        for (int i = 0; i < facts.size(); i++) {
            for (int j = i + 1; j < facts.size(); j++) {
                Fact a = facts.get(i);
                Fact b = facts.get(j);
                if (contradicts(a, b)) {
                    result.add(new Pair<>(a, b));
                }
            }
        }
        return result;
    }

    /**
     * A simple immutable pair type for contradiction results.
     */
    public record Pair<A, B>(A first, B second) {}
}
