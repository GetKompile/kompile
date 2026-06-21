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

/**
 * Per-atom marginal statistics produced by {@link PslMarginalInference}.
 *
 * <p>For each target atom the sampler collects the mean truth value and variance
 * across all retained samples, giving a distributional characterisation of the
 * atom's soft-truth uncertainty — in contrast to the point estimate returned by
 * {@link HlMrfMapInference}.</p>
 *
 * @param atomKey  the canonical ground atom key (e.g. {@code "State(alice)"})
 * @param mean     empirical mean of the truth value across samples (in [0, 1])
 * @param variance empirical variance (in [0, 1]); zero for a deterministic atom
 * @param samples  number of samples that contributed to the statistics
 */
public record AtomMarginal(String atomKey, double mean, double variance, int samples) {

    /**
     * A point-estimate marginal with zero variance (e.g. for observed / hard-clamped atoms).
     *
     * @param atomKey the atom key
     * @param value   the deterministic value
     * @return a deterministic {@code AtomMarginal}
     */
    public static AtomMarginal deterministic(String atomKey, double value) {
        return new AtomMarginal(atomKey, value, 0.0, 1);
    }

    /**
     * Standard deviation: {@code sqrt(variance)}.
     *
     * @return the standard deviation of the truth value across samples
     */
    public double stdDev() {
        return Math.sqrt(variance);
    }

    /**
     * Approximate 95% credible interval half-width (1.96 × stdDev), clamped to [0, 1].
     *
     * @return approximate 95% credible interval half-width
     */
    public double halfWidth95() {
        return Math.min(mean, Math.min(1.0 - mean, 1.96 * stdDev()));
    }

    @Override
    public String toString() {
        return String.format("AtomMarginal{%s mean=%.4f var=%.4f n=%d}", atomKey, mean, variance, samples);
    }
}
