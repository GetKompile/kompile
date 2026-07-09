/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.psl;

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
 * @param templateIndex index of the template {@link PslRule} in the program's rule list that
 *                      produced this grounding, or {@code -1} when unknown (hand-built ground
 *                      rules). Weight learning uses this for exact per-rule gradient attribution —
 *                      signature-based matching cannot distinguish rules that share the same
 *                      (hard, squared, weight) triple, e.g. the common all-weights-1.0 init.
 */
public record GroundRule(double weight, boolean hard, boolean squared,
                         List<Lit> body, List<Lit> head, String display, int templateIndex) {

    public GroundRule {
        if (Double.isNaN(weight) || weight < 0.0) {
            throw new IllegalArgumentException("Rule weight must be non-negative, got: " + weight);
        }
        if (weight == Double.POSITIVE_INFINITY) {
            hard = true;
            squared = true;
        } else if (hard) {
            weight = Double.POSITIVE_INFINITY;
            squared = true;
        }
        body = List.copyOf(body);
        head = List.copyOf(head);
        if (templateIndex < -1) templateIndex = -1;
    }

    /** Back-compat constructor: unknown template rule ({@code templateIndex = -1}). */
    public GroundRule(double weight, boolean hard, boolean squared,
                      List<Lit> body, List<Lit> head, String display) {
        this(weight, hard, squared, body, head, display, -1);
    }

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
