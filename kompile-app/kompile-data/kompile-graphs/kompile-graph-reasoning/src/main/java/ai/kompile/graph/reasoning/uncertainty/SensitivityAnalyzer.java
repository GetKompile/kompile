/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.uncertainty;

import ai.kompile.graph.reasoning.bayesian.BayesianNetwork;
import ai.kompile.graph.reasoning.bayesian.BayesianNode;
import ai.kompile.graph.reasoning.bayesian.Factor;
import ai.kompile.graph.reasoning.bayesian.VariableElimination;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bayesian sensitivity analysis (§1.6): how much the posterior of a {@code target} shifts when a
 * {@code candidate} node is observed.
 *
 * <p>{@code sensitivity(candidate → target) = E_x[ |P(T=true | E, candidate=x) − P(T=true | E)| ]} —
 * the expected absolute posterior shift across the candidate's states, weighted by {@code P(x | E)}.
 * This is a <em>local gradient</em> measure of influence; it complements
 * {@link InformationGainEstimator} (expected entropy reduction). For a nearly-linear posterior the two
 * agree; sensitivity is cheaper to read off and directional, while EIG is the calibrated bit count.</p>
 */
public final class SensitivityAnalyzer {

    private SensitivityAnalyzer() {
    }

    /** Expected absolute shift in {@code P(target=true)} from observing {@code candidate}. */
    public static double sensitivity(BayesianNetwork net, Map<String, Integer> evidence,
                                     String candidate, String target) {
        Map<String, Integer> ev = evidence == null ? Map.of() : evidence;
        double pTarget = pTrue(VariableElimination.query(net, target, ev));
        double[] pCandidate = VariableElimination.query(net, candidate, ev).getValues();

        double sensitivity = 0.0;
        for (int x = 0; x < pCandidate.length; x++) {
            if (pCandidate[x] < 1e-12) {
                continue;
            }
            Map<String, Integer> extended = new HashMap<>(ev);
            extended.put(candidate, x);
            double pTargetGivenX = pTrue(VariableElimination.query(net, target, extended));
            sensitivity += pCandidate[x] * Math.abs(pTargetGivenX - pTarget);
        }
        return sensitivity;
    }

    /** Rank non-evidence, non-target nodes by sensitivity toward {@code target}, highest first. */
    public static List<Map.Entry<String, Double>> rankBySensitivity(
            BayesianNetwork net, Map<String, Integer> evidence, String target) {
        Map<String, Integer> ev = evidence == null ? Map.of() : evidence;
        List<Map.Entry<String, Double>> ranked = new ArrayList<>();
        for (BayesianNode node : net.getNodes()) {
            String var = node.getVariableName();
            if (ev.containsKey(var) || var.equals(target)) {
                continue;
            }
            ranked.add(Map.entry(var, sensitivity(net, ev, var, target)));
        }
        ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        return ranked;
    }

    /** P(X = TRUE) from a binary factor (index 1 = TRUE; falls back to index 0 for a single state). */
    private static double pTrue(Factor factor) {
        double[] values = factor.getValues();
        return values.length > 1 ? values[1] : values[0];
    }
}
