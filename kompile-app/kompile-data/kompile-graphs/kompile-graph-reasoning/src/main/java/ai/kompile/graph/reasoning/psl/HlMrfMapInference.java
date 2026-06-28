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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Entry point and router for HL-MRF MAP (most-probable-explanation) inference — the
 * hand-rolled analogue of PSL's {@code MPEInference}.
 *
 * <p>It minimizes the total weighted energy {@code E(y) = Σ_r w_r · d_r(y)^{p_r}} over the
 * target atom values {@code y ∈ [0,1]}, where {@code d_r} is each ground rule's
 * {@link GroundRule#distanceToSatisfaction distance to satisfaction} and {@code p_r ∈ {1,2}}.
 * The energy is convex (a sum of hinges over affine functions with non-negative weights), so
 * MAP is solved exactly by projected gradient descent. Atom truth values are continuous
 * {@code [0,1]} soft-truth — the defining difference from the discrete Bayesian-network path
 * in the sibling {@code algorithm.bayesian} package.</p>
 *
 * <p>This class grounds the program once and dispatches to an {@link HlMrfSolver} chosen by
 * problem size: the plain-Java {@link ScalarHlMrfInference} for the small subgraphs that
 * dominate in practice, and the ND4J-vectorized {@link TensorHlMrfInference} (GPU-capable)
 * once the program is large enough for matrix operations to pay off — provided an ND4J
 * backend is available, otherwise it falls back to the scalar solver.</p>
 */
public final class HlMrfMapInference {

    private HlMrfMapInference() {}

    public static final int DEFAULT_MAX_ITERATIONS = 2000;
    public static final double DEFAULT_TOLERANCE = 1e-6;
    public static final double DEFAULT_HARD_WEIGHT = 1.0e6;

    /**
     * Route to the ND4J {@link TensorHlMrfInference} backend at or above this many ground
     * rules (when a backend is available). Below it, the scalar solver is faster because it
     * avoids per-iteration op-dispatch and host/device transfer overhead. The default KG
     * subgraphs (maxNodes ≈ 100) sit well under this, so they stay on the scalar path.
     */
    public static final int DEFAULT_TENSOR_THRESHOLD = 4000;

    /**
     * Above this many dense incidence-matrix cells ({@code groundRules × atoms}) the dense
     * {@link TensorHlMrfInference} would use too much memory, so routing prefers the sparse
     * {@link SgdHlMrfInference} instead (≈ 50M floats ≈ 200 MB).
     */
    public static final long DEFAULT_DENSE_CELL_BUDGET = 50_000_000L;

    /**
     * Tolerance below which a hard constraint's distance-to-satisfaction counts as a violation
     * in {@link Result#hardViolations}.
     */
    public static final double HARD_VIOLATION_TOLERANCE = 1e-4;

    /**
     * Inference outcome.
     *
     * @param values      final truth assignment (observed atoms unchanged, targets optimized)
     * @param groundRules the ground rules that were optimized (for explainability)
     * @param iterations  number of descent iterations performed
     * @param objective   final total energy
     * @param converged   whether the descent reached the tolerance before the iteration cap
     */
    public record Result(Map<String, Double> values, List<GroundRule> groundRules,
                         int iterations, double objective, boolean converged) {
        /**
         * Ground rules that are marked hard and have a distance-to-satisfaction above
         * {@link #HARD_VIOLATION_TOLERANCE} at the final atom assignment.
         */
        public List<GroundRule> hardViolations() {
            List<GroundRule> violations = new ArrayList<>();
            for (GroundRule gr : groundRules) {
                if (gr.hard() && gr.distanceToSatisfaction(values) > HARD_VIOLATION_TOLERANCE) {
                    violations.add(gr);
                }
            }
            return violations;
        }

        /**
         * Per-ground-rule satisfaction detail at the MAP solution.
         *
         * <p>For every logical ground rule in {@link #groundRules} this computes the
         * Łukasiewicz {@link GroundRule#distanceToSatisfaction distance to satisfaction}
         * and its weighted energy contribution {@link GroundRule#potential potential} at the
         * final atom assignment {@link #values}. The results are ordered identically to
         * {@link #groundRules}.</p>
         *
         * <p>This is the primary hook for PSL "why" explanations: a downstream reasoning
         * trail can rank rules by {@link GroundRuleResult#distanceToSatisfaction()} to surface
         * the most violated constraints, or filter by {@link GroundRuleResult#satisfied()} to
         * identify exactly which constraints hold at the MAP solution.</p>
         *
         * <p>The computation is purely post-hoc — it reads the final {@link #values} map
         * and calls the existing {@link GroundRule} methods; no solver state is consulted.</p>
         *
         * @param hardWeight the penalty weight for hard constraints, used to compute
         *                   {@link GroundRuleResult#weightedPotential()}; pass
         *                   {@link HlMrfMapInference#DEFAULT_HARD_WEIGHT} if unsure
         * @return immutable list of per-rule results, one per entry in {@link #groundRules}
         */
        public List<GroundRuleResult> groundRuleResults(double hardWeight) {
            List<GroundRuleResult> results = new ArrayList<>(groundRules.size());
            for (GroundRule gr : groundRules) {
                double d = gr.distanceToSatisfaction(values);
                double pot = gr.potential(values, hardWeight);
                boolean satisfied = d <= HARD_VIOLATION_TOLERANCE;
                results.add(new GroundRuleResult(gr, d, pot, satisfied));
            }
            return results;
        }

        /**
         * Convenience overload that uses {@link HlMrfMapInference#DEFAULT_HARD_WEIGHT} for
         * the hard-constraint penalty when computing {@link GroundRuleResult#weightedPotential()}.
         *
         * @return immutable list of per-rule results
         * @see #groundRuleResults(double)
         */
        public List<GroundRuleResult> groundRuleResults() {
            return groundRuleResults(DEFAULT_HARD_WEIGHT);
        }
    }

    public static Result solve(PslProgram program) {
        return solve(program, DEFAULT_MAX_ITERATIONS, DEFAULT_TOLERANCE, DEFAULT_HARD_WEIGHT);
    }

    public static Result solve(PslProgram program, int maxIterations, double tolerance, double hardWeight) {
        List<GroundRule> ground = program.ground();
        List<ArithmeticGroundRule> arithGround = program.groundArithmetic();

        if (!arithGround.isEmpty()) {
            // Use ADMM when arithmetic rules are present (required for proper halfspace projection)
            AdmmHlMrfInference admm = new AdmmHlMrfInference();
            return admm.solve(program, ground, arithGround, maxIterations, tolerance, hardWeight);
        }

        return chooseSolver(ground.size(), program.atomCount())
                .solve(program, ground, maxIterations, tolerance, hardWeight);
    }

    /**
     * Threshold below which programs are considered "small" and routed to
     * {@link ScalarHlMrfInference}. The default KG subgraphs (maxNodes ≈ 100) sit well under
     * this, so they stay on the fast scalar path. ADMM startup overhead only pays off above
     * this threshold (roughly 500 ground rules).
     */
    public static final int DEFAULT_SCALAR_THRESHOLD = 500;

    /**
     * Pick the inference strategy by problem size:
     * <ul>
     *   <li>fewer than {@link #DEFAULT_SCALAR_THRESHOLD} ground rules ⇒
     *       {@link ScalarHlMrfInference} (fastest for small KG subgraphs; avoids ADMM startup
     *       overhead that dominates at this scale).</li>
     *   <li>{@link #DEFAULT_SCALAR_THRESHOLD}–{@link #DEFAULT_TENSOR_THRESHOLD} ground rules ⇒
     *       {@link AdmmHlMrfInference} (canonical PSL solver, Bach et al. UAI 2013).</li>
     *   <li>above {@link #DEFAULT_TENSOR_THRESHOLD}, if the dense incidence matrix fits
     *       {@link #DEFAULT_DENSE_CELL_BUDGET} and an ND4J backend is available ⇒
     *       {@link TensorHlMrfInference};</li>
     *   <li>otherwise ⇒ {@link SgdHlMrfInference} (sparse, mini-batch SGD).</li>
     * </ul>
     */
    public static HlMrfSolver chooseSolver(int groundRuleCount, int atomCount) {
        if (groundRuleCount < DEFAULT_SCALAR_THRESHOLD) {
            return new ScalarHlMrfInference();
        }
        if (groundRuleCount < DEFAULT_TENSOR_THRESHOLD) {
            return new AdmmHlMrfInference(); // canonical ADMM for mid-scale
        }
        long cells = (long) groundRuleCount * Math.max(1, atomCount);
        if (cells <= DEFAULT_DENSE_CELL_BUDGET && TensorHlMrfInference.isAvailable()) {
            return new TensorHlMrfInference();
        }
        return new SgdHlMrfInference();
    }
}
