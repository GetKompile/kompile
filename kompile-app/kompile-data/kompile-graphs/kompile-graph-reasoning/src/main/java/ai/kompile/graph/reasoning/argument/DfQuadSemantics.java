/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.argument;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * DF-QuAD gradual semantics for a {@link Qbaf}.
 *
 * <h3>Semantics (Rago et al. 2016)</h3>
 * <p>Let β be the base score of an argument {@code a}, and let {@code v₁…vₙ} be
 * the current strengths of its attackers and supporters respectively.
 *
 * <p>The <em>aggregate function</em> (linear combination over individual strengths) for
 * a set of arguments with strengths {v₁, …, vₙ} is:
 * <pre>  F(v₁, …, vₙ) = 1 − Π(1 − vᵢ)</pre>
 *
 * <p>Let {@code va⁻ = F(attackerStrengths)} and {@code va⁺ = F(supporterStrengths)},
 * with {@code F(∅) = 0}. The final strength σ(a) is:
 * <pre>
 *   if va⁻ > va⁺:  σ = β − β · (va⁻ − va⁺)         [weakened]
 *   if va⁺ > va⁻:  σ = β + (1−β) · (va⁺ − va⁻)     [strengthened]
 *   if va⁻ = va⁺:  σ = β                             [unchanged]
 * </pre>
 *
 * <h3>Evaluation algorithm</h3>
 * <p>Evaluation proceeds by Jacobi fixed-point iteration (each sweep reads previous-round
 * strengths, writes the new round atomically). Starting from each argument's {@code baseScore},
 * the algorithm runs up to {@code MAX_ITERATIONS} (1000) sweeps or until the maximum absolute
 * change across all arguments drops below {@code TOLERANCE} (1e-9).</p>
 *
 * <p>Convergence is guaranteed for acyclic QBAFs (they converge in at most {@code depth}
 * sweeps). For cyclic QBAFs, the algorithm may reach a fixed point, oscillate, or converge
 * slowly; the iteration cap prevents infinite loops. Cycle behavior is intentionally
 * left as "best-effort fixed-point" rather than undefined — oscillating cycles will
 * stabilize at the limit of the cap and may not represent a true semantic equilibrium.
 * Callers of {@link #evaluate(Qbaf)} can detect non-convergence by inspecting the
 * returned {@link Result#converged()} flag.</p>
 *
 * <h3>Edge cases and postulate compliance</h3>
 * <ul>
 *   <li><b>β = 0</b>: weakening leaves σ = 0 (β · anything = 0); strengthening yields
 *       (1 − 0) · (va⁺ − va⁻) = va⁺ − va⁻. Non-trivial strengthening is possible.</li>
 *   <li><b>β = 1</b>: weakening yields 1 − 1 · Δ = 1 − Δ; strengthening leaves
 *       σ = 1 + 0 · Δ = 1 (the (1−β) factor collapses). Monotonicity holds directionally
 *       but the absolute change at β=1 for support is zero — the argument is at the ceiling.</li>
 * </ul>
 */
public final class DfQuadSemantics {

    private static final int MAX_ITERATIONS = 1000;
    private static final double TOLERANCE = 1e-9;

    private DfQuadSemantics() {}

    /** The result of evaluating a {@link Qbaf}. */
    public record Result(
            Map<String, Double> strengths,
            boolean converged,
            int iterations
    ) {
        public Result {
            strengths = Collections.unmodifiableMap(new LinkedHashMap<>(strengths));
        }

        /** Strength of the CLAIM argument (the verdict score). */
        public double claimStrength(Qbaf qbaf) {
            return strengths.getOrDefault(qbaf.claimId(), 0.0);
        }
    }

    /**
     * Evaluate the given QBAF under DF-QuAD semantics and return per-argument strengths.
     *
     * @param qbaf the QBAF to evaluate; must not be null
     * @return the evaluation result with per-argument strengths and convergence metadata
     */
    public static Result evaluate(Qbaf qbaf) {
        // Initialize strengths to base scores
        Map<String, Double> current = new LinkedHashMap<>();
        for (Argument a : qbaf.arguments()) {
            current.put(a.id(), a.baseScore());
        }

        int iter = 0;
        boolean converged = false;
        while (iter < MAX_ITERATIONS) {
            Map<String, Double> next = new LinkedHashMap<>();
            double maxDelta = 0.0;

            for (Argument a : qbaf.arguments()) {
                Set<String> attackerIds = qbaf.attackers(a.id());
                Set<String> supporterIds = qbaf.supporters(a.id());

                double vaMinus = aggregate(attackerIds, current);
                double vaPlus  = aggregate(supporterIds, current);

                double beta = a.baseScore();
                double sigma;
                if (vaMinus > vaPlus) {
                    sigma = beta - beta * (vaMinus - vaPlus);
                } else if (vaPlus > vaMinus) {
                    sigma = beta + (1.0 - beta) * (vaPlus - vaMinus);
                } else {
                    sigma = beta;
                }
                // clamp to [0,1] to guard against floating-point drift
                sigma = Math.max(0.0, Math.min(1.0, sigma));
                next.put(a.id(), sigma);

                double delta = Math.abs(sigma - current.getOrDefault(a.id(), beta));
                if (delta > maxDelta) maxDelta = delta;
            }

            current = next;
            iter++;

            if (maxDelta < TOLERANCE) {
                converged = true;
                break;
            }
        }

        return new Result(current, converged, iter);
    }

    /**
     * DF-QuAD aggregate function: {@code F(v₁, …, vₙ) = 1 − Π(1 − vᵢ)}.
     * Returns 0 for an empty set.
     */
    static double aggregate(Set<String> argIds, Map<String, Double> strengths) {
        if (argIds.isEmpty()) return 0.0;
        double product = 1.0;
        for (String id : argIds) {
            double v = strengths.getOrDefault(id, 0.0);
            product *= (1.0 - v);
        }
        return 1.0 - product;
    }
}
