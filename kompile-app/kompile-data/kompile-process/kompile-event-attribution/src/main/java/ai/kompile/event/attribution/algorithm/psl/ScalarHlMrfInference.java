/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.psl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plain-Java HL-MRF MAP inference: projected gradient descent with backtracking line search,
 * operating on a {@code Map<String,Double>} of atom truth values.
 *
 * <p>This is the default, dependency-free solver. For the small KG subgraphs that dominate in
 * practice (≤ a few hundred ground rules) it finishes in single-digit milliseconds — well below
 * the overhead at which the vectorized {@link TensorHlMrfInference} pays off.</p>
 *
 * <p>The energy {@code E(y) = Σ_r w_r · d_r(y)^{p_r}} is convex (each {@code d_r} is a hinge over
 * an affine function and weights are non-negative), so the descent converges to the global
 * optimum. Hard constraints are enforced as a large squared penalty ({@code hardWeight}).</p>
 */
public class ScalarHlMrfInference implements HlMrfSolver {

    @Override
    public HlMrfMapInference.Result solve(PslProgram program, List<GroundRule> ground,
                                          int maxIterations, double tolerance, double hardWeight) {
        Map<String, Double> values = program.valueSnapshot();
        List<String> targets = program.targetKeys();

        // Neutral initialization for the free variables.
        for (String t : targets) values.put(t, 0.5);

        if (targets.isEmpty() || ground.isEmpty()) {
            return new HlMrfMapInference.Result(values, ground, 0, objective(ground, values, hardWeight), true);
        }

        Set<String> targetSet = new HashSet<>(targets);
        double objective = objective(ground, values, hardWeight);
        double step = 0.1;
        boolean converged = false;
        int iter = 0;

        for (; iter < maxIterations; iter++) {
            Map<String, Double> grad = gradient(ground, values, targetSet, hardWeight);
            double gradNorm2 = 0.0;
            for (double g : grad.values()) gradNorm2 += g * g;
            if (gradNorm2 <= tolerance * tolerance) {
                converged = true;
                break;
            }

            // Backtracking line search: shrink the step until the energy strictly decreases.
            boolean accepted = false;
            for (int ls = 0; ls < 50; ls++) {
                Map<String, Double> candidate = new LinkedHashMap<>(values);
                double maxChange = 0.0;
                for (String t : targets) {
                    double v = clamp01(values.get(t) - step * grad.getOrDefault(t, 0.0));
                    maxChange = Math.max(maxChange, Math.abs(v - values.get(t)));
                    candidate.put(t, v);
                }
                double candidateObjective = objective(ground, candidate, hardWeight);
                if (candidateObjective < objective) {
                    boolean tinyProgress = (objective - candidateObjective) < tolerance && maxChange < tolerance;
                    values = candidate;
                    objective = candidateObjective;
                    accepted = true;
                    if (tinyProgress) converged = true;
                    break;
                }
                step *= 0.5;
            }
            if (!accepted) { // step underflow ⇒ already at the optimum
                converged = true;
                break;
            }
            if (converged) break;
            step *= 1.5; // grow the step again for the next iteration
        }

        return new HlMrfMapInference.Result(values, ground, iter, objective, converged);
    }

    /**
     * Gradient of the energy w.r.t. each target atom. For a ground rule with positive
     * distance {@code d}, the body conjunction and head disjunction are both in their linear
     * (un-clamped) regime, so each literal's contribution is {@code ±coef}, with
     * {@code coef = w·p·d^{p-1}}.
     */
    private static Map<String, Double> gradient(List<GroundRule> ground, Map<String, Double> values,
                                                Set<String> targets, double hardWeight) {
        Map<String, Double> grad = new HashMap<>();
        for (GroundRule gr : ground) {
            double d = gr.distanceToSatisfaction(values);
            if (d <= 0.0) continue;
            double w = gr.hard() ? hardWeight : gr.weight();
            double coef = gr.squared() ? 2.0 * w * d : w;
            for (GroundRule.Lit l : gr.body()) {
                if (targets.contains(l.atomKey())) {
                    // ∂d/∂value = +∂bodyTruth/∂value = (negated ? -1 : +1)
                    grad.merge(l.atomKey(), coef * (l.negated() ? -1.0 : 1.0), Double::sum);
                }
            }
            for (GroundRule.Lit l : gr.head()) {
                if (targets.contains(l.atomKey())) {
                    // ∂d/∂value = -∂headTruth/∂value = (negated ? +1 : -1)
                    grad.merge(l.atomKey(), coef * (l.negated() ? 1.0 : -1.0), Double::sum);
                }
            }
        }
        return grad;
    }

    private static double objective(List<GroundRule> ground, Map<String, Double> values, double hardWeight) {
        double sum = 0.0;
        for (GroundRule gr : ground) sum += gr.potential(values, hardWeight);
        return sum;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
