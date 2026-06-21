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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProjectedGradientOptimizer}.
 */
class ProjectedGradientOptimizerTest {

    /**
     * (a) {@code step} applies {@code project(p - lr * g)} in-place and returns the correct
     * maximum absolute change.
     *
     * <p>Example: lr=0.5, params=[2.0, 3.0], gradient=[1.0, -2.0], no projection (identity).
     * Expected: params[0] = 2.0 - 0.5*1.0 = 1.5, params[1] = 3.0 - 0.5*(-2.0) = 4.0.
     * Changes: |1.5-2.0|=0.5, |4.0-3.0|=1.0 → maxChange=1.0.</p>
     */
    @Test
    void step_appliesProjectedDescentAndReturnsMaxChange() {
        ProjectedGradientOptimizer opt =
                new ProjectedGradientOptimizer(0.5, 1e-4, null); // null → identity projection
        double[] params = {2.0, 3.0};
        double[] gradient = {1.0, -2.0};

        double maxChange = opt.step(params, gradient);

        assertEquals(1.5, params[0], 1e-12, "params[0] = 2.0 - 0.5*1.0 = 1.5");
        assertEquals(4.0, params[1], 1e-12, "params[1] = 3.0 - 0.5*(-2.0) = 4.0");
        assertEquals(1.0, maxChange, 1e-12, "maxChange = max(0.5, 1.0) = 1.0");
    }

    /**
     * (b) {@link ProjectedGradientOptimizer#nonNegative()} clamps negative results to 0.
     *
     * <p>Example: lr=1.0, params=[0.3], gradient=[1.0].
     * Raw update = 0.3 - 1.0*1.0 = -0.7. After clamp: max(0, -0.7) = 0.0.</p>
     */
    @Test
    void nonNegative_clampsNegativeResultToZero() {
        ProjectedGradientOptimizer opt =
                new ProjectedGradientOptimizer(1.0, 1e-4, ProjectedGradientOptimizer.nonNegative());
        double[] params = {0.3};
        double[] gradient = {1.0};

        opt.step(params, gradient);

        assertEquals(0.0, params[0], 1e-12, "nonNegative projection clamps -0.7 to 0.0");
    }

    /**
     * (c) {@link ProjectedGradientOptimizer#unitInterval()} clamps results to [0, 1].
     *
     * <p>Negative case: lr=1.0, params=[0.2], gradient=[1.0] → raw=-0.8 → clamped=0.0.
     * Overflow case: lr=1.0, params=[0.9], gradient=[-0.5] → raw=1.4 → clamped=1.0.</p>
     */
    @Test
    void unitInterval_clampsToZeroOneRange() {
        ProjectedGradientOptimizer opt =
                new ProjectedGradientOptimizer(1.0, 1e-4, ProjectedGradientOptimizer.unitInterval());

        double[] paramsNeg = {0.2};
        opt.step(paramsNeg, new double[]{1.0});
        assertEquals(0.0, paramsNeg[0], 1e-12, "unitInterval clamps below-zero to 0.0");

        double[] paramsOver = {0.9};
        opt.step(paramsOver, new double[]{-0.5});
        assertEquals(1.0, paramsOver[0], 1e-12, "unitInterval clamps above-one to 1.0");
    }

    /**
     * (d) {@link ProjectedGradientOptimizer#step} throws {@link IllegalArgumentException}
     * when {@code params} and {@code gradient} differ in length.
     */
    @Test
    void step_throwsOnLengthMismatch() {
        ProjectedGradientOptimizer opt =
                new ProjectedGradientOptimizer(0.1, 1e-4, ProjectedGradientOptimizer.nonNegative());
        double[] params = {1.0, 2.0};
        double[] gradient = {1.0};

        assertThrows(IllegalArgumentException.class,
                () -> opt.step(params, gradient),
                "mismatched lengths must throw IllegalArgumentException");
    }
}
