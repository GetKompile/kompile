/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.embedding.learn;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AliasTable} (Vose alias method).
 */
class AliasTableTest {

    private static final int DRAWS = 200_000;
    private static final long SEED = 42L;

    /**
     * Uniform weights (all equal) should produce approximately equal frequencies across outcomes.
     * Tolerance: within 3% of the theoretical 1/n probability for each outcome.
     */
    @Test
    void uniformWeightsProduceApproximatelyUniformDistribution() {
        int n = 5;
        double[] weights = new double[n];
        for (int i = 0; i < n; i++) {
            weights[i] = 1.0;
        }

        AliasTable table = AliasTable.build(weights);
        int[] counts = new int[n];
        Random rng = new Random(SEED);
        for (int i = 0; i < DRAWS; i++) {
            counts[table.sample(rng)]++;
        }

        double expected = (double) DRAWS / n;
        double tolerance = expected * 0.03;   // 3% relative tolerance
        for (int i = 0; i < n; i++) {
            assertTrue(
                    Math.abs(counts[i] - expected) < tolerance,
                    "Outcome " + i + " count " + counts[i] + " deviates more than 3% from " + expected);
        }
    }

    /**
     * Skewed weights: outcome 0 has weight 10×, outcome 1 has weight 1×, outcome 2 has weight
     * 1×. Outcome 0 should be sampled roughly 10/12 ≈ 83.3% of the time; others ~8.3% each.
     * Tolerance: within 5% absolute.
     */
    @Test
    void skewedWeightsRespectRelativeProbabilities() {
        double[] weights = {10.0, 1.0, 1.0};   // outcome 0 is 10× more likely
        AliasTable table = AliasTable.build(weights);

        int[] counts = new int[3];
        Random rng = new Random(SEED);
        for (int i = 0; i < DRAWS; i++) {
            counts[table.sample(rng)]++;
        }

        double total = 10.0 + 1.0 + 1.0;
        double[] expectedFraction = {10.0 / total, 1.0 / total, 1.0 / total};

        for (int i = 0; i < 3; i++) {
            double observedFraction = (double) counts[i] / DRAWS;
            assertTrue(
                    Math.abs(observedFraction - expectedFraction[i]) < 0.05,
                    "Outcome " + i + ": expected ~" + expectedFraction[i]
                            + " but observed " + observedFraction);
        }
    }

    /**
     * Heavily skewed distribution with one dominant weight.  The dominant outcome should capture
     * close to its true probability.
     */
    @Test
    void singleDominantOutcomeIsDrawnMostOften() {
        // Outcome 0 gets 97% of probability mass
        double[] weights = {97.0, 1.0, 1.0, 1.0};
        AliasTable table = AliasTable.build(weights);

        int[] counts = new int[4];
        Random rng = new Random(SEED + 1);
        for (int i = 0; i < DRAWS; i++) {
            counts[table.sample(rng)]++;
        }

        double dominantFraction = (double) counts[0] / DRAWS;
        assertTrue(dominantFraction > 0.92,
                "Dominant outcome fraction " + dominantFraction + " should be > 0.92");
    }

    /**
     * Sampling is reproducible: the same seed produces the same sequence of outcomes.
     */
    @Test
    void samplingIsReproducible() {
        double[] weights = {3.0, 1.0, 2.0};
        AliasTable table = AliasTable.build(weights);

        int[] run1 = new int[100];
        int[] run2 = new int[100];
        for (int i = 0; i < 100; i++) {
            run1[i] = table.sample(new Random(SEED + i));
            run2[i] = table.sample(new Random(SEED + i));
        }
        for (int i = 0; i < 100; i++) {
            assertEquals(run1[i], run2[i], "Sample at position " + i + " differed");
        }
    }

    /**
     * Size is reported correctly.
     */
    @Test
    void sizeMatchesWeightsLength() {
        double[] weights = {1.0, 2.0, 3.0, 4.0, 5.0};
        AliasTable table = AliasTable.build(weights);
        assertEquals(5, table.size());
    }

    /**
     * Empty weights array must throw {@link IllegalArgumentException}.
     */
    @Test
    void emptyWeightsThrows() {
        assertThrows(IllegalArgumentException.class, () -> AliasTable.build(new double[0]));
    }

    /**
     * All-zero weights array must throw {@link IllegalArgumentException}.
     */
    @Test
    void allZeroWeightsThrows() {
        assertThrows(IllegalArgumentException.class, () -> AliasTable.build(new double[]{0.0, 0.0}));
    }

    /**
     * A single-element distribution must always return index 0.
     */
    @Test
    void singleElementAlwaysReturnZero() {
        AliasTable table = AliasTable.build(new double[]{7.5});
        Random rng = new Random(SEED);
        for (int i = 0; i < 100; i++) {
            assertEquals(0, table.sample(rng), "Single-element table must always return 0");
        }
    }
}
