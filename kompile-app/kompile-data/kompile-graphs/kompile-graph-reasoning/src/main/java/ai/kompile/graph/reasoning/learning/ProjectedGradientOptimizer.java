/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.learning;

import java.util.function.DoubleUnaryOperator;

/**
 * Shared projected-gradient-descent optimizer for weight-space learning in the reasoning library.
 *
 * <p>This is the weight-space counterpart to the atom-space
 * {@link ai.kompile.graph.reasoning.psl.SgdHlMrfInference}, and is shared by
 * {@link StructuredPerceptronLearner}, {@link PseudolikelihoodLearner}, and
 * {@link MebnWeightLearner} to eliminate duplicated update loops.</p>
 *
 * <p>Each call to {@link #step} performs one gradient-descent update:
 * {@code params[i] = projection(params[i] - learningRate * gradient[i])}
 * for all {@code i}, and returns the maximum absolute parameter change. A caller can then
 * test {@link #converged(double)} to decide whether to stop.</p>
 *
 * <p>Two standard projections are provided as static factories:
 * <ul>
 *   <li>{@link #nonNegative()} — clamps to {@code [0, +∞)}, used by PSL rule-weight learners.</li>
 *   <li>{@link #unitInterval()} — clamps to {@code [0, 1]}, used by MEBN edge-strength learners.</li>
 * </ul>
 * A {@code null} projection argument is treated as the identity (no clamping).</p>
 */
public final class ProjectedGradientOptimizer {

    private final double learningRate;
    private final double tolerance;
    private final DoubleUnaryOperator projection;

    /**
     * Constructs a new optimizer.
     *
     * @param learningRate the per-step learning rate (gradient scale factor); must be positive
     * @param tolerance    convergence threshold; {@link #converged(double)} returns {@code true}
     *                     when {@code maxChange < tolerance}
     * @param projection   constraint projection applied after each update; pass {@code null}
     *                     for unconstrained (identity) descent
     */
    public ProjectedGradientOptimizer(double learningRate, double tolerance,
                                      DoubleUnaryOperator projection) {
        this.learningRate = learningRate;
        this.tolerance = tolerance;
        this.projection = projection != null ? projection : v -> v;
    }

    /**
     * Performs one in-place projected-gradient step.
     *
     * <p>For each index {@code i}: {@code params[i] = projection(params[i] - learningRate * gradient[i])}</p>
     *
     * @param params   parameter vector updated in place
     * @param gradient gradient vector (same length as {@code params})
     * @return maximum absolute change across all parameters
     * @throws IllegalArgumentException if {@code params} and {@code gradient} differ in length
     */
    public double step(double[] params, double[] gradient) {
        if (params.length != gradient.length) {
            throw new IllegalArgumentException(
                    "params length " + params.length + " != gradient length " + gradient.length);
        }
        double maxChange = 0.0;
        for (int i = 0; i < params.length; i++) {
            double oldVal = params[i];
            double newVal = projection.applyAsDouble(oldVal - learningRate * gradient[i]);
            double change = Math.abs(newVal - oldVal);
            if (change > maxChange) {
                maxChange = change;
            }
            params[i] = newVal;
        }
        return maxChange;
    }

    /**
     * Returns {@code true} when the maximum parameter change from the last {@link #step} call
     * is below the tolerance, indicating convergence.
     *
     * @param maxChange the value returned by the most recent call to {@link #step}
     * @return {@code true} if {@code maxChange < tolerance}
     */
    public boolean converged(double maxChange) {
        return maxChange < tolerance;
    }

    /**
     * Returns the learning rate supplied at construction.
     *
     * @return learning rate
     */
    public double learningRate() {
        return learningRate;
    }

    /**
     * Returns the convergence tolerance supplied at construction.
     *
     * @return tolerance
     */
    public double tolerance() {
        return tolerance;
    }

    /**
     * Returns a projection operator that clamps its argument to {@code [0, +∞)}.
     * Used by PSL rule-weight learners where weights must be non-negative.
     *
     * @return non-negative projection
     */
    public static DoubleUnaryOperator nonNegative() {
        return v -> Math.max(0.0, v);
    }

    /**
     * Returns a projection operator that clamps its argument to {@code [0, 1]}.
     * Used by MEBN edge-strength learners where strengths are probabilities.
     *
     * @return unit-interval projection
     */
    public static DoubleUnaryOperator unitInterval() {
        return v -> v < 0.0 ? 0.0 : Math.min(1.0, v);
    }
}
