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
     * Pick the inference strategy by problem size:
     * <ul>
     *   <li>fewer than {@link #DEFAULT_TENSOR_THRESHOLD} ground rules ⇒ {@link AdmmHlMrfInference}
     *       for mid-scale problems; {@link ScalarHlMrfInference} for very small ones (the ADMM
     *       startup overhead dominates below ~50 rules).</li>
     *   <li>for large programs, if the dense incidence matrix fits {@link #DEFAULT_DENSE_CELL_BUDGET}
     *       and an ND4J backend is available ⇒ {@link TensorHlMrfInference};</li>
     *   <li>otherwise ⇒ {@link SgdHlMrfInference} (sparse, mini-batch SGD).</li>
     * </ul>
     *
     * <p>ADMM is the canonical PSL solver (Bach et al. UAI 2013) and is now the default for
     * mid-scale programs (50–{@link #DEFAULT_TENSOR_THRESHOLD} ground rules). Very small programs
     * (fewer than 50 ground rules) still use {@link ScalarHlMrfInference} to avoid ADMM startup
     * overhead.</p>
     */
    public static HlMrfSolver chooseSolver(int groundRuleCount, int atomCount) {
        if (groundRuleCount < 50) {
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
