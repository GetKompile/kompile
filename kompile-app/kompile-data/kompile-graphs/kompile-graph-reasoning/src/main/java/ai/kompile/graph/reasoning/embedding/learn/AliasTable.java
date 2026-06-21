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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Vose alias method for O(1) sampling from a discrete distribution over {@code n} outcomes.
 *
 * <p>Given a weight array {@code w[0..n-1]} (any non-negative values; need not sum to 1),
 * {@link #build(double[])} normalises the weights and constructs the alias table in O(n) time.
 * Thereafter each call to {@link #sample(Random)} draws one outcome in O(1) — two random numbers,
 * one array lookup, one comparison.</p>
 *
 * <p>This is the standard data structure used by DeepWalk / node2vec for degree-raised negative
 * sampling ({@code weight[i] = degree(i)^0.75}) and by node2vec's biased-walk preprocessing for
 * per-edge transition probability tables.</p>
 *
 * <p>References:
 * <ul>
 *   <li>Vose, M.D. (1991). A linear algorithm for generating random numbers with a given
 *       distribution. <i>IEEE Transactions on Software Engineering</i>, 17(9):972-975.</li>
 *   <li>Walker, A.J. (1977). An efficient method for generating discrete random variables with
 *       general distributions. <i>ACM TOMS</i>, 3(3):253-256.</li>
 * </ul>
 * </p>
 */
public final class AliasTable {

    private final double[] prob;   // prob[i] = probability of drawing i directly (in [0,1] after normalisation)
    private final int[]    alias;  // alias[i] = the "other" outcome when i is not drawn directly

    private AliasTable(double[] prob, int[] alias) {
        this.prob  = prob;
        this.alias = alias;
    }

    /**
     * Build an alias table from a raw weight array.
     *
     * @param weights non-negative weights; must contain at least one element and at least one
     *                positive weight. The array is not modified.
     * @return a ready-to-sample {@link AliasTable}
     * @throws IllegalArgumentException if {@code weights} is empty or sums to zero
     */
    public static AliasTable build(double[] weights) {
        int n = weights.length;
        if (n == 0) {
            throw new IllegalArgumentException("weights array must not be empty");
        }

        // Normalise so that all probabilities sum to n (Vose's scaling choice keeps values in [0,2))
        double sum = 0.0;
        for (double w : weights) {
            sum += w;
        }
        if (sum <= 0.0) {
            throw new IllegalArgumentException("weights must sum to a positive value");
        }

        double[] prob  = new double[n];
        int[]    alias = new int[n];

        // scaled probabilities: each p[i] ∈ [0, n]
        double[] scaled = new double[n];
        for (int i = 0; i < n; i++) {
            scaled[i] = weights[i] * n / sum;
        }

        // Separate into "small" (< 1) and "large" (≥ 1) buckets
        Deque<Integer> small = new ArrayDeque<>();
        Deque<Integer> large = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (scaled[i] < 1.0) {
                small.push(i);
            } else {
                large.push(i);
            }
        }

        // Pair up small and large until one bucket empties
        while (!small.isEmpty() && !large.isEmpty()) {
            int s = small.pop();
            int l = large.pop();

            prob[s]  = scaled[s];
            alias[s] = l;

            scaled[l] = (scaled[l] + scaled[s]) - 1.0;   // l absorbs what s couldn't cover
            if (scaled[l] < 1.0) {
                small.push(l);
            } else {
                large.push(l);
            }
        }

        // Remaining elements are exactly 1.0 (up to floating-point rounding)
        while (!large.isEmpty()) {
            prob[large.pop()] = 1.0;
        }
        while (!small.isEmpty()) {
            prob[small.pop()] = 1.0;  // rounding artefact
        }

        return new AliasTable(prob, alias);
    }

    /**
     * Draw one outcome from the distribution in O(1).
     *
     * @param rng caller-supplied random number generator (for full reproducibility, pass a seeded
     *            {@link Random})
     * @return index in {@code [0, n)}
     */
    public int sample(Random rng) {
        int i = rng.nextInt(prob.length);
        return rng.nextDouble() < prob[i] ? i : alias[i];
    }

    /** Number of outcomes in the distribution. */
    public int size() {
        return prob.length;
    }
}
