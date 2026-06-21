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
import ai.kompile.graph.reasoning.psl.PslMarginalInference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Unified, path-agnostic "what should we observe next?" selector. Ranks unobserved nodes by the
 * expected value of observing them toward a target, alongside how uncertain each candidate currently
 * is. The Bayesian path uses exact EIG ({@link InformationGainEstimator}); the PSL path uses the
 * sample-split approximation ({@link PslInformationGainApproximator}).
 */
public final class ValueOfInformation {

    private ValueOfInformation() {
    }

    /**
     * One candidate's value of information toward a target.
     *
     * @param variableKey             the candidate variable/atom
     * @param expectedInformationGain expected information gain in bits (exact for Bayesian, approximate for PSL)
     * @param currentEntropy          how uncertain the candidate currently is (bits)
     * @param path                    which inference path produced this
     */
    public record VoIResult(
            String variableKey,
            double expectedInformationGain,
            double currentEntropy,
            VariableUncertainty.InferencePath path) {
    }

    /** Rank candidates toward {@code targetVariable} on the Bayesian path (exact EIG), highest first. */
    public static List<VoIResult> forBayesianNetwork(
            BayesianNetwork net, Map<String, Integer> evidence, String targetVariable) {
        Map<String, Integer> ev = evidence == null ? Map.of() : evidence;
        Map<String, VariableUncertainty> uncertainties = BayesianUncertaintyEstimator.allUncertainties(net, ev);
        List<VoIResult> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : InformationGainEstimator.rankCandidatesByEIG(net, ev, targetVariable)) {
            VariableUncertainty u = uncertainties.get(e.getKey());
            out.add(new VoIResult(e.getKey(), e.getValue(),
                    u == null ? 0.0 : u.entropyBits(), VariableUncertainty.InferencePath.BAYESIAN));
        }
        return out; // already sorted by EIG descending
    }

    /** Rank candidate atoms toward {@code targetAtomKey} on the PSL path (sample-split EIG), highest first. */
    public static List<VoIResult> forPslResult(PslMarginalInference.Result result, String targetAtomKey) {
        Map<String, VariableUncertainty> uncertainties = PslUncertaintyAdapter.fromMarginals(result);
        List<VoIResult> out = new ArrayList<>();
        for (VariableUncertainty u : uncertainties.values()) {
            if (u.variableKey().equals(targetAtomKey)) {
                continue;
            }
            double eig = PslInformationGainApproximator.sampleSplitEIG(result, u.variableKey(), targetAtomKey);
            out.add(new VoIResult(u.variableKey(), eig, u.entropyBits(), VariableUncertainty.InferencePath.PSL));
        }
        out.sort(Comparator.comparingDouble(VoIResult::expectedInformationGain).reversed());
        return out;
    }
}
