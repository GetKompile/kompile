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
 * Quadratic-Energy (QE) gradual semantics for a {@link Qbaf}.
 *
 * <h3>Semantics (Potyka 2018, §4)</h3>
 * <p>Let β be the base score of argument {@code a}, and let σ(b) be the current strength of
 * each neighbour b. The <em>energy</em> of {@code a} aggregates incoming influences:
 * <pre>
 *   E(a) = Σ_{b ∈ Sup(a)} σ(b)  −  Σ_{b ∈ Att(a)} σ(b)
 * </pre>
 * The auxiliary <em>ramp-quadratic</em> function {@code h} maps any real to [0,1):
 * <pre>
 *   h(x) = max(x, 0)² / (1 + max(x, 0)²)
 * </pre>
 * Note: h is non-negative, h(0) = 0, h is strictly increasing for x &gt; 0,
 * and h(x) → 1 as x → ∞. For x ≤ 0, h(x) = 0.
 *
 * <p>The final strength update rule is:
 * <pre>
 *   σ(a) = β(a) + (1 − β(a)) · h( E(a)) − β(a) · h(−E(a))
 * </pre>
 * When E(a) &gt; 0 (net support), the second term pulls σ toward 1.
 * When E(a) &lt; 0 (net attack), the third term pulls σ toward 0.
 * When E(a) = 0, σ = β (stability postulate).
 *
 * <h3>Boundary behavior</h3>
 * <ul>
 *   <li>β = 0: strengthening yields (1−0)·h(E) − 0 = h(E) ≥ 0; net-attack yields 0.</li>
 *   <li>β = 1: strengthening yields 1 + 0 − 1·h(−E) = 1 (ceiling); weakening yields
 *       1 − h(−E) ≤ 1.</li>
 * </ul>
 *
 * <h3>Evaluation algorithm</h3>
 * <p>Jacobi fixed-point iteration: each sweep reads the <em>previous</em> round's strengths
 * to produce the next round atomically, starting from σ = β. The iteration terminates when
 * the maximum absolute change across all arguments falls below {@code tolerance}
 * (default 1e-9) or after {@code maxIterations} (default 1000) sweeps.</p>
 *
 * <p><b>Convergence guarantee (acyclic QBAFs):</b> for acyclic QBAFs the energy E(a) depends
 * only on arguments whose strengths are already at their fixed-point by topological order;
 * the iteration converges in at most {@code depth} sweeps — typically 1–3 for shallow graphs.</p>
 *
 * <p><b>Cyclic QBAFs:</b> QE is continuous and the update function is a contraction on
 * small networks; damped iteration ({@code dampingFactor} &lt; 1.0) applies a convex
 * combination between the previous strength and the raw update,
 * {@code σ_new = (1−d)·σ_old + d·σ_raw}, improving convergence on unstable cycles.
 * Callers can detect non-convergence via {@link Result#converged()}.</p>
 *
 * <h3>Key property for learning</h3>
 * <p>h is infinitely differentiable on all of ℝ (since max(·,0)² is smooth at 0, where
 * the derivative is also 0). The update σ is therefore smooth in all base scores, which
 * means finite-difference gradient estimates are accurate and gradient-descent weight
 * learning is numerically well-behaved.</p>
 */
public final class QeSemantics {

    private static final int    DEFAULT_MAX_ITERATIONS = 1000;
    private static final double DEFAULT_TOLERANCE      = 1e-9;
    private static final double DEFAULT_DAMPING        = 1.0;

    private final int    maxIterations;
    private final double tolerance;
    /** Damping factor in (0, 1]. At 1.0 (default) no damping is applied. */
    private final double dampingFactor;

    /** Create QE semantics with default parameters (no damping, tol=1e-9, maxIter=1000). */
    public QeSemantics() {
        this(DEFAULT_MAX_ITERATIONS, DEFAULT_TOLERANCE, DEFAULT_DAMPING);
    }

    /**
     * Create QE semantics with explicit control knobs.
     *
     * @param maxIterations maximum Jacobi sweeps before giving up (≥ 1)
     * @param tolerance     convergence threshold; iteration stops when max |Δσ| &lt; tolerance (≥ 0)
     * @param dampingFactor convex-combination weight in (0, 1]; use &lt; 1.0 for cyclic graphs
     */
    public QeSemantics(int maxIterations, double tolerance, double dampingFactor) {
        if (maxIterations < 1) throw new IllegalArgumentException("maxIterations must be >= 1");
        if (tolerance < 0.0)   throw new IllegalArgumentException("tolerance must be >= 0");
        if (dampingFactor <= 0.0 || dampingFactor > 1.0)
            throw new IllegalArgumentException("dampingFactor must be in (0,1], got " + dampingFactor);
        this.maxIterations = maxIterations;
        this.tolerance     = tolerance;
        this.dampingFactor = dampingFactor;
    }

    // ── Result type (same shape as DfQuadSemantics.Result) ───────────────────────

    /** The result of evaluating a {@link Qbaf} under QE semantics. */
    public record Result(
            Map<String, Double> strengths,
            boolean converged,
            int iterations
    ) {
        public Result {
            strengths = Collections.unmodifiableMap(new LinkedHashMap<>(strengths));
        }

        /** Strength of the CLAIM argument. */
        public double claimStrength(Qbaf qbaf) {
            return strengths.getOrDefault(qbaf.claimId(), 0.0);
        }
    }

    // ── Static convenience entry-point ────────────────────────────────────────────

    /**
     * Evaluate the given QBAF under QE semantics with default parameters.
     * Equivalent to {@code new QeSemantics().evaluate(qbaf)}.
     *
     * @param qbaf the QBAF to evaluate; must not be null
     * @return per-argument strengths and convergence metadata
     */
    public static Result evaluate(Qbaf qbaf) {
        return new QeSemantics().evaluateQbaf(qbaf);
    }

    /**
     * Evaluate the given QBAF under these QE semantics parameters.
     *
     * @param qbaf the QBAF to evaluate; must not be null
     * @return per-argument strengths and convergence metadata
     */
    public Result evaluateQbaf(Qbaf qbaf) {
        // Initialise strengths to base scores
        Map<String, Double> current = new LinkedHashMap<>();
        for (Argument a : qbaf.arguments()) {
            current.put(a.id(), a.baseScore());
        }

        int    iter      = 0;
        boolean converged = false;

        while (iter < maxIterations) {
            Map<String, Double> next    = new LinkedHashMap<>();
            double              maxDelta = 0.0;

            for (Argument a : qbaf.arguments()) {
                Set<String> attackerIds  = qbaf.attackers(a.id());
                Set<String> supporterIds = qbaf.supporters(a.id());

                // Energy: sum of supporter strengths minus sum of attacker strengths
                double energy = 0.0;
                for (String sid : supporterIds) energy += current.getOrDefault(sid, 0.0);
                for (String aid : attackerIds)  energy -= current.getOrDefault(aid, 0.0);

                double beta    = a.baseScore();
                double sigmaRaw = beta + (1.0 - beta) * h(energy) - beta * h(-energy);
                // clamp to [0,1] to guard floating-point edge cases
                sigmaRaw = Math.max(0.0, Math.min(1.0, sigmaRaw));

                // Apply damping if factor < 1
                double sigmaDamped = dampingFactor < 1.0
                        ? (1.0 - dampingFactor) * current.getOrDefault(a.id(), beta) + dampingFactor * sigmaRaw
                        : sigmaRaw;

                next.put(a.id(), sigmaDamped);

                double delta = Math.abs(sigmaDamped - current.getOrDefault(a.id(), beta));
                if (delta > maxDelta) maxDelta = delta;
            }

            current = next;
            iter++;

            if (maxDelta < tolerance) {
                converged = true;
                break;
            }
        }

        return new Result(current, converged, iter);
    }

    // ── h function ────────────────────────────────────────────────────────────────

    /**
     * Ramp-quadratic function: {@code h(x) = max(x, 0)² / (1 + max(x, 0)²)}.
     * Maps ℝ → [0, 1). Smooth everywhere (derivative is 0 at x=0 from both sides).
     *
     * @param x any real number
     * @return h(x) in [0, 1)
     */
    static double h(double x) {
        double r = Math.max(x, 0.0);
        return (r * r) / (1.0 + r * r);
    }
}
