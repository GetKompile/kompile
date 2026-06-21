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

import ai.kompile.graph.reasoning.psl.AtomMarginal;
import ai.kompile.graph.reasoning.psl.PslMarginalInference;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapts {@link PslMarginalInference} output (per-atom mean/variance from the perturb-and-MAP
 * sampler) to {@link VariableUncertainty}.
 *
 * <p>For PSL the {@code variance} is the genuine sample variance across MAP replicas — the natural
 * uncertainty measure on this path. The {@code entropyBits} is a <em>heuristic proxy</em> that treats
 * the soft-truth mean as a Bernoulli {@code p}; PSL soft-truth is not a probability, so prefer ranking
 * by variance ({@link #rankByVariance}) on this path.</p>
 */
public final class PslUncertaintyAdapter {

    private PslUncertaintyAdapter() {
    }

    /** Convert per-atom marginals to {@link VariableUncertainty} keyed by atom key. */
    public static Map<String, VariableUncertainty> fromMarginals(PslMarginalInference.Result result) {
        Map<String, VariableUncertainty> out = new LinkedHashMap<>();
        for (AtomMarginal am : result.marginals().values()) {
            double p = am.mean();
            double entropy = VariableUncertainty.binaryEntropy(p);
            out.put(am.atomKey(), new VariableUncertainty(
                    am.atomKey(), p, am.variance(), entropy, am.samples(),
                    VariableUncertainty.InferencePath.PSL));
        }
        return out;
    }

    /** Rank atoms by sample variance, highest first (most uncertain under PSL sampling). */
    public static List<VariableUncertainty> rankByVariance(PslMarginalInference.Result result) {
        return fromMarginals(result).values().stream()
                .sorted(Comparator.comparingDouble(VariableUncertainty::variance).reversed())
                .toList();
    }

    /**
     * BALD (Bayesian Active Learning by Disagreement) epistemic uncertainty for a PSL atom, using the
     * perturb-and-MAP raw samples: {@code H(mean) - mean_k H(sample_k)} with the binary-entropy proxy.
     *
     * <p>High when the model is confident <em>within</em> each sample but the samples disagree
     * (epistemic / model uncertainty — the useful signal for active learning); near zero when each
     * sample is itself uncertain (aleatoric noise). Returns {@code 0} if no raw samples are present.</p>
     */
    public static double bald(PslMarginalInference.Result result, String atomKey) {
        List<Map<String, Double>> samples = result.rawSamples();
        if (samples == null || samples.isEmpty()) {
            return 0.0;
        }
        double sumMean = 0.0;
        double sumPerSampleEntropy = 0.0;
        for (Map<String, Double> sample : samples) {
            double v = sample.getOrDefault(atomKey, 0.0);
            sumMean += v;
            sumPerSampleEntropy += VariableUncertainty.binaryEntropy(v);
        }
        int n = samples.size();
        double entropyOfMean = VariableUncertainty.binaryEntropy(sumMean / n);
        return Math.max(0.0, entropyOfMean - sumPerSampleEntropy / n);
    }
}
