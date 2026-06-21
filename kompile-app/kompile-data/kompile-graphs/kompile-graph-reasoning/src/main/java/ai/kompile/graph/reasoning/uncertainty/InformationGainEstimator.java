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
 * Expected Information Gain (EIG) on the Bayesian path — the "next best node to observe" acquisition
 * function for active KB maintenance.
 *
 * <p>{@code EIG(candidate → target) = H(target | evidence) − E_c[H(target | evidence, candidate=c)]},
 * the mutual information {@code I(candidate; target | evidence)}. It is non-negative, symmetric, and
 * unit-consistent (bits). Exact via variable elimination: {@code 1 + cardinality(candidate)} extra
 * queries per (candidate, target) pair.</p>
 */
public final class InformationGainEstimator {

    private InformationGainEstimator() {
    }

    /**
     * Expected information gain (bits, {@code >= 0}) about {@code target} from observing
     * {@code candidate}, given current {@code evidence}.
     */
    public static double expectedInformationGain(
            BayesianNetwork net, Map<String, Integer> evidence, String candidate, String target) {
        Map<String, Integer> ev = evidence == null ? Map.of() : evidence;

        // 1. Baseline entropy of the target under current evidence.
        Factor targetMarginal = VariableElimination.query(net, target, ev);
        double baselineEntropy = shannonEntropyBits(targetMarginal.getValues());

        // 2. Distribution of the candidate under current evidence.
        Factor candidateMarginal = VariableElimination.query(net, candidate, ev);
        double[] pCandidate = candidateMarginal.getValues();

        // 3. Expected posterior entropy of the target across the candidate's states.
        double conditionalEntropy = 0.0;
        for (int c = 0; c < pCandidate.length; c++) {
            if (pCandidate[c] < 1e-12) {
                continue;
            }
            Map<String, Integer> extended = new HashMap<>(ev);
            extended.put(candidate, c);
            Factor targetGivenC = VariableElimination.query(net, target, extended);
            conditionalEntropy += pCandidate[c] * shannonEntropyBits(targetGivenC.getValues());
        }
        return Math.max(0.0, baselineEntropy - conditionalEntropy);
    }

    /**
     * EIG of every non-evidence, non-target variable toward {@code target}, sorted descending —
     * the greedy max-mutual-information ranking of which node to observe next.
     */
    public static List<Map.Entry<String, Double>> rankCandidatesByEIG(
            BayesianNetwork net, Map<String, Integer> evidence, String target) {
        Map<String, Integer> ev = evidence == null ? Map.of() : evidence;
        List<Map.Entry<String, Double>> ranked = new ArrayList<>();
        for (BayesianNode node : net.getNodes()) {
            String var = node.getVariableName();
            if (ev.containsKey(var) || var.equals(target)) {
                continue;
            }
            ranked.add(Map.entry(var, expectedInformationGain(net, ev, var, target)));
        }
        ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        return ranked;
    }

    /** Shannon entropy (bits) of a discrete distribution. */
    public static double shannonEntropyBits(double[] probs) {
        double h = 0.0;
        for (double p : probs) {
            if (p > 1e-12) {
                h -= p * (Math.log(p) / Math.log(2));
            }
        }
        return h;
    }
}
