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
import ai.kompile.graph.reasoning.bayesian.VariableElimination;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-variable uncertainty for the Bayesian path. Reuses {@link VariableElimination#queryAll} (one
 * pass over the network) to get every variable's posterior marginal {@code p}, then reports
 * {@code Var(X) = p(1-p)} and binary Shannon entropy {@code H(X)} per node. Observed (evidence)
 * variables have zero uncertainty.
 */
public final class BayesianUncertaintyEstimator {

    private BayesianUncertaintyEstimator() {
    }

    /**
     * Compute per-variable uncertainty for all variables, using a single {@code queryAll} pass.
     * Variables present in {@code evidence} are reported with zero variance/entropy.
     */
    public static Map<String, VariableUncertainty> allUncertainties(
            BayesianNetwork net, Map<String, Integer> evidence) {
        Map<String, Integer> ev = evidence == null ? Map.of() : evidence;
        Map<String, Double> marginals = VariableElimination.queryAll(net, ev);
        Map<String, VariableUncertainty> result = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : marginals.entrySet()) {
            String var = entry.getKey();
            double p = entry.getValue();
            boolean observed = ev.containsKey(var);
            double variance = observed ? 0.0 : p * (1 - p);
            double entropy = observed ? 0.0 : VariableUncertainty.binaryEntropy(p);
            result.put(var, new VariableUncertainty(
                    var, p, variance, entropy, 1, VariableUncertainty.InferencePath.BAYESIAN));
        }
        return result;
    }

    /**
     * Rank non-evidence variables by entropy, highest first — i.e. the most uncertain nodes, the
     * natural candidates for verification/observation in active KB maintenance.
     */
    public static List<VariableUncertainty> rankByUncertainty(
            BayesianNetwork net, Map<String, Integer> evidence) {
        Map<String, Integer> ev = evidence == null ? Map.of() : evidence;
        return allUncertainties(net, ev).values().stream()
                .filter(u -> !ev.containsKey(u.variableKey()))
                .sorted(Comparator.comparingDouble(VariableUncertainty::entropyBits).reversed())
                .toList();
    }
}
