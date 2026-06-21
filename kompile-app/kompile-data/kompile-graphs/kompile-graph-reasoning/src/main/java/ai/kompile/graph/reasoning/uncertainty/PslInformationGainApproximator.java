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

import ai.kompile.graph.reasoning.psl.PslMarginalInference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Approximate Expected Information Gain on the PSL path, where exact variable elimination is not
 * available. It uses the raw perturb-and-MAP samples exposed by
 * {@link PslMarginalInference.Result#rawSamples()}:
 *
 * <ul>
 *   <li>{@link #sampleSplitEIG} — bin the K samples by whether the candidate atom is {@code > 0.5},
 *       compute the binary-entropy proxy of the target within each bin, and weight by bin fraction;
 *       the reduction vs the unsplit target entropy is the approximate information gain (§3.5).</li>
 *   <li>{@link #covarianceProxyEIG} — a Gaussian mutual-information bound from the sample covariance,
 *       {@code -0.5 log2(1 - r^2)} with {@code r} the Pearson correlation; a rough guide, not a
 *       calibrated bit count.</li>
 * </ul>
 *
 * <p>Caveat: PSL soft-truth is not a probability, so these are heuristic proxies (see the research
 * doc §3.7). Returns {@code 0} when no raw samples are present.</p>
 */
public final class PslInformationGainApproximator {

    private PslInformationGainApproximator() {
    }

    /**
     * Sample-split approximate EIG of observing {@code candidateKey} toward {@code targetKey}.
     *
     * @return information gain (bits, {@code >= 0}); 0 if raw samples are unavailable
     */
    public static double sampleSplitEIG(PslMarginalInference.Result result,
                                        String candidateKey, String targetKey) {
        List<Map<String, Double>> samples = result.rawSamples();
        if (samples == null || samples.isEmpty()) {
            return 0.0;
        }
        double baseEntropy = VariableUncertainty.binaryEntropy(mean(samples, targetKey));

        List<Double> hi = new ArrayList<>();
        List<Double> lo = new ArrayList<>();
        for (Map<String, Double> s : samples) {
            double c = s.getOrDefault(candidateKey, 0.0);
            double t = s.getOrDefault(targetKey, 0.0);
            (c > 0.5 ? hi : lo).add(t);
        }
        int n = samples.size();
        double conditional = 0.0;
        if (!hi.isEmpty()) {
            conditional += (hi.size() / (double) n) * VariableUncertainty.binaryEntropy(avg(hi));
        }
        if (!lo.isEmpty()) {
            conditional += (lo.size() / (double) n) * VariableUncertainty.binaryEntropy(avg(lo));
        }
        return Math.max(0.0, baseEntropy - conditional);
    }

    /**
     * Gaussian mutual-information proxy from sample statistics: {@code -0.5 log2(1 - r^2)} where
     * {@code r = cov / sqrt(varA * varB)} is the Pearson correlation. Monotonic in MI under a
     * bivariate-Gaussian approximation.
     */
    public static double covarianceProxyEIG(double candidateMean, double candidateVariance,
                                            double targetMean, double targetVariance,
                                            double sampleCovariance) {
        double denom = Math.sqrt(candidateVariance * targetVariance);
        if (denom < 1e-12) {
            return 0.0;
        }
        double r = sampleCovariance / denom;
        r = Math.max(-0.999999, Math.min(0.999999, r));
        return Math.max(0.0, -0.5 * Math.log(1 - r * r) / Math.log(2));
    }

    /** Sample covariance between two atoms' sample vectors (population form, /N). */
    public static double sampleCovariance(PslMarginalInference.Result result, String keyA, String keyB) {
        List<Map<String, Double>> samples = result.rawSamples();
        if (samples == null || samples.size() < 2) {
            return 0.0;
        }
        double ma = mean(samples, keyA);
        double mb = mean(samples, keyB);
        double sum = 0.0;
        for (Map<String, Double> s : samples) {
            sum += (s.getOrDefault(keyA, 0.0) - ma) * (s.getOrDefault(keyB, 0.0) - mb);
        }
        return sum / samples.size();
    }

    private static double mean(List<Map<String, Double>> samples, String key) {
        double sum = 0.0;
        for (Map<String, Double> s : samples) {
            sum += s.getOrDefault(key, 0.0);
        }
        return sum / samples.size();
    }

    private static double avg(List<Double> xs) {
        double sum = 0.0;
        for (double x : xs) {
            sum += x;
        }
        return sum / xs.size();
    }
}
