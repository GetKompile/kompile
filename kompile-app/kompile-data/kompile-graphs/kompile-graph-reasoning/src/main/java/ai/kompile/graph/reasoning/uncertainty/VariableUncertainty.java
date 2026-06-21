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

/**
 * Distributional uncertainty summary for one variable/node in a reasoning graph.
 *
 * <p>All fields are in natural units: {@code variance} in {@code [0, 0.25]} for a Bernoulli node,
 * {@code entropyBits} in bits ({@code [0, 1]} for binary), and {@code marginal} in {@code [0, 1]}.
 * This is the per-node uncertainty primitive that backs active KB maintenance ("which node is most
 * uncertain, and which one — if observed — most reduces uncertainty about a target").</p>
 *
 * @param variableKey variable name (Bayesian) or atom key (PSL)
 * @param marginal    posterior marginal {@code P(X = true | evidence)} or PSL soft-truth mean
 * @param variance    {@code marginal * (1 - marginal)} for Bernoulli; sample variance for PSL
 * @param entropyBits Shannon entropy {@code H(X)} in bits
 * @param samples     number of samples that contributed (1 for exact VE, K for PSL sampling)
 * @param path        which inference path produced this
 */
public record VariableUncertainty(
        String variableKey,
        double marginal,
        double variance,
        double entropyBits,
        int samples,
        InferencePath path
) {
    /** The reasoning path a {@link VariableUncertainty} was derived from. */
    public enum InferencePath { BAYESIAN, PSL, FOL }

    /** Standard deviation, {@code sqrt(variance)}. */
    public double stdDev() {
        return Math.sqrt(variance);
    }

    /**
     * Binary Shannon entropy in bits: {@code -p log2 p - (1-p) log2(1-p)}. Maximised (1 bit) at
     * {@code p = 0.5} and zero at certainty ({@code p <= 0} or {@code p >= 1}).
     */
    public static double binaryEntropy(double p) {
        if (p <= 0.0 || p >= 1.0) {
            return 0.0;
        }
        return -p * log2(p) - (1 - p) * log2(1 - p);
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
