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

import java.util.List;
import java.util.Map;

/**
 * A fully grounded {@link PslRule}: the body and head are lists of ground-atom
 * references (by {@link PslAtom#key() key}, with sign). This is one hinge-loss
 * potential of the Hinge-Loss Markov Random Field.
 *
 * <p>The <b>distance to satisfaction</b> uses the Łukasiewicz relaxation of logic:
 * the body conjunction is {@code max(0, Σ bodyTruth - (n-1))}, the head disjunction is
 * {@code min(1, Σ headTruth)}, and the distance is
 * {@code max(0, bodyTruth - headTruth)} — zero when the implication holds, positive in
 * proportion to its violation. A negated literal contributes {@code 1 - value}.</p>
 *
 * @param weight  rule weight (ignored when {@code hard})
 * @param hard    {@code true} for a hard constraint
 * @param squared {@code true} for a squared hinge, {@code false} for linear
 * @param body    body literals (conjunction); empty body ⇒ antecedent is always true (1.0)
 * @param head    head literals (disjunction); empty head ⇒ consequent is always false (0.0)
 * @param display human-readable rendering of this ground rule (for explainability)
 */
public record GroundRule(double weight, boolean hard, boolean squared,
                         List<Lit> body, List<Lit> head, String display) {

    /** A signed reference to a ground atom by its canonical {@link PslAtom#key() key}. */
    public record Lit(String atomKey, boolean negated) {
        public double value(Map<String, Double> truth) {
            double v = truth.getOrDefault(atomKey, 0.0);
            return negated ? 1.0 - v : v;
        }
    }

    /** Łukasiewicz body conjunction: {@code max(0, Σ truth - (n-1))}; empty body ⇒ 1.0. */
    public double bodyTruth(Map<String, Double> truth) {
        if (body.isEmpty()) return 1.0;
        double sum = 0.0;
        for (Lit l : body) sum += l.value(truth);
        return Math.max(0.0, sum - (body.size() - 1));
    }

    /** Łukasiewicz head disjunction: {@code min(1, Σ truth)}; empty head ⇒ 0.0. */
    public double headTruth(Map<String, Double> truth) {
        if (head.isEmpty()) return 0.0;
        double sum = 0.0;
        for (Lit l : head) sum += l.value(truth);
        return Math.min(1.0, sum);
    }

    /** Distance to satisfaction in {@code [0, 1]}: {@code max(0, bodyTruth - headTruth)}. */
    public double distanceToSatisfaction(Map<String, Double> truth) {
        return Math.max(0.0, bodyTruth(truth) - headTruth(truth));
    }

    /**
     * Weighted potential contributed to the total energy.
     *
     * @param truth      current atom truth assignment
     * @param hardWeight the penalty weight used in place of infinity for hard constraints
     */
    public double potential(Map<String, Double> truth, double hardWeight) {
        double d = distanceToSatisfaction(truth);
        if (d <= 0.0) return 0.0;
        double w = hard ? hardWeight : weight;
        return w * (squared ? d * d : d);
    }

    @Override
    public String toString() {
        return display;
    }
}
