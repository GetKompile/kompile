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

import java.util.List;

/**
 * Strategy for HL-MRF MAP inference over a pre-grounded {@link PslProgram}.
 *
 * <p>Two implementations exist behind this seam, selected by problem size in
 * {@link HlMrfMapInference#chooseSolver(int)}:</p>
 * <ul>
 *   <li>{@link ScalarHlMrfInference} — plain-Java projected gradient descent; the fast,
 *       dependency-free path used for the small KG subgraphs that dominate in practice.</li>
 *   <li>{@link TensorHlMrfInference} — the same algorithm vectorized with ND4J matrix
 *       operations (GPU-capable via the active backend) for large / collective-inference
 *       programs and weight learning.</li>
 * </ul>
 *
 * <p>The grounding is done once by the caller and passed in, so the two strategies are
 * interchangeable and never re-ground.</p>
 */
public interface HlMrfSolver {

    /**
     * Run MAP inference.
     *
     * @param program       the program (source of atom values and observed/target partition)
     * @param groundRules   the already-grounded rules to optimize
     * @param maxIterations iteration cap
     * @param tolerance     convergence tolerance on gradient norm and per-step progress
     * @param hardWeight    penalty weight used in place of infinity for hard constraints
     */
    HlMrfMapInference.Result solve(PslProgram program, List<GroundRule> groundRules,
                                   int maxIterations, double tolerance, double hardWeight);

    /** Convenience overload using the default iteration/tolerance/hard-weight settings. */
    default HlMrfMapInference.Result solve(PslProgram program, List<GroundRule> groundRules) {
        return solve(program, groundRules, HlMrfMapInference.DEFAULT_MAX_ITERATIONS,
                HlMrfMapInference.DEFAULT_TOLERANCE, HlMrfMapInference.DEFAULT_HARD_WEIGHT);
    }
}
