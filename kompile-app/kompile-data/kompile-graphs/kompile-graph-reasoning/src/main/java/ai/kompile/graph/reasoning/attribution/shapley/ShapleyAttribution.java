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
package ai.kompile.graph.reasoning.attribution.shapley;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Monte-Carlo Shapley source-attribution estimator for crisp Datalog claims.
 *
 * <h2>Background</h2>
 * <p>The Shapley value (Shapley, 1953) of a player {@code p} in a cooperative game
 * {@code (N, v)} is the unique fair allocation satisfying efficiency, symmetry,
 * dummy-player, and additivity axioms.  For database provenance, the "value" of a
 * coalition {@code S ⊆ N} of base facts is whether the query (claim) holds given
 * exactly those facts ({@code v(S) ∈ {0,1}}).  Exact Shapley computation is
 * #P-hard beyond hierarchical conjunctive queries (Livshits, Bertossi, Kimelfeld &amp;
 * Sebag, ICDT 2020); this class implements the standard Monte-Carlo permutation
 * estimator (Maleki et al., 2013; Castro, Gómez &amp; Tejada, 2009).</p>
 *
 * <h2>Algorithm</h2>
 * <p>For each of {@code samples} random permutations of the player list:</p>
 * <ol>
 *   <li>Walk the permutation left to right, maintaining a running prefix set {@code P}.</li>
 *   <li>For player at position {@code i}: marginal contribution =
 *       {@code eval(P ∪ {p_i}) − eval(P)} where each result is in {0, 1}.</li>
 *   <li>Add the marginal to player {@code p_i}'s running sum and sum-of-squares.</li>
 *   <li>Add {@code p_i} to {@code P} and continue.</li>
 * </ol>
 * <p>After all samples, divide each player's sum by {@code samples} to get the
 * Shapley estimate, and derive the per-player standard error from the variance.</p>
 *
 * <h2>Antithetic variate (optional)</h2>
 * <p>The current implementation does <strong>not</strong> apply the antithetic-variate
 * trick (using both a permutation and its reverse in the same sample).  This is noted
 * here for completeness: the variance reduction from antitheticness is small when
 * individual marginal contributions are in {0,1} and the game is not close to symmetric.
 * A future extension can pass {@code antitheticPairs = true} and count each pair as
 * two samples.</p>
 *
 * <h2>Error bound</h2>
 * <p>The Monte-Carlo estimator has standard error O(1/√samples) per player.
 * Doubling the sample count halves the error.  A 95 % confidence interval is
 * approximately {@code shapley ± 2 × stdError}.  For n players and total variance
 * bounded by 1, the expected maximum standard error across players is at most
 * 1/√samples.</p>
 *
 * <h2>Infra-free</h2>
 * <p>No Spring, no JPA, no external libraries beyond the Java standard library.</p>
 *
 * @see ClaimEvaluator
 * @see DatalogClaimEvaluator
 * @see ShapleyReport
 * @see ClaimShapley
 */
public final class ShapleyAttribution {

    private ShapleyAttribution() {}

    /**
     * Default singleton — callers that need no configuration use this.
     */
    public static final ShapleyAttribution INSTANCE = new ShapleyAttribution();

    /**
     * Run the Monte-Carlo Shapley estimator.
     *
     * <p>The {@code seed} parameter guarantees determinism: given the same
     * {@code eval}, {@code players}, {@code samples}, and {@code seed}, this
     * method always returns an identical {@link ShapleyReport}.</p>
     *
     * @param eval    the claim evaluator (supplies game values for any coalition);
     *                must be deterministic; must not be null
     * @param players ordered list of player identifiers (fact atom keys);
     *                must not contain duplicates; must not be null
     * @param samples number of random permutations to sample; must be ≥ 1
     * @param seed    random seed for the permutation generator
     * @return a {@link ShapleyReport} with per-player estimates and standard errors
     * @throws IllegalArgumentException if {@code samples < 1}
     * @throws IllegalArgumentException if {@code players} contains duplicates
     */
    public ShapleyReport assess(ClaimEvaluator eval,
                                List<String> players,
                                int samples,
                                long seed) {
        Objects.requireNonNull(eval, "eval");
        Objects.requireNonNull(players, "players");
        if (samples < 1) {
            throw new IllegalArgumentException("samples must be ≥ 1, got: " + samples);
        }
        // Validate no duplicates
        if (new java.util.HashSet<>(players).size() != players.size()) {
            throw new IllegalArgumentException(
                    "players list must not contain duplicates: " + players);
        }

        int n = players.size();

        if (n == 0) {
            // No players: all Shapley values are 0, targetDelta = 0
            return new ShapleyReport(Map.of(), Map.of(), samples, seed, 0.0);
        }

        // Compute the game values at the boundary coalitions for the efficiency check
        double vEmpty = eval.holds(Set.of()) ? 1.0 : 0.0;
        double vAll   = eval.holds(new java.util.HashSet<>(players)) ? 1.0 : 0.0;
        double targetDelta = vAll - vEmpty;

        // Accumulators: sum and sum-of-squares per player (for variance / std-error)
        double[] sumMarg   = new double[n];
        double[] sumMargSq = new double[n];

        // Mutable permutation array (indices into players list) — shuffled each round
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) perm[i] = i;

        Random rng = new Random(seed);

        // Main sampling loop
        for (int s = 0; s < samples; s++) {
            // Fisher-Yates shuffle — O(n) per sample
            for (int i = n - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp;
            }

            // Walk the permutation prefix-by-prefix
            // prefix is built incrementally; we only store it as a mutable HashSet
            java.util.HashSet<String> prefix = new java.util.HashSet<>(n);
            boolean prevHolds = eval.holds(prefix); // eval(∅)

            for (int i = 0; i < n; i++) {
                String player = players.get(perm[i]);
                prefix.add(player);
                boolean nowHolds = eval.holds(prefix);
                // Marginal contribution in {-1, 0, 1} — but since v ∈ {0,1},
                // the marginal is in {-1, 0, 1}; in practice for monotone games {0, 1}.
                double marginal = (nowHolds ? 1.0 : 0.0) - (prevHolds ? 1.0 : 0.0);
                int playerIdx = perm[i];
                sumMarg[playerIdx]   += marginal;
                sumMargSq[playerIdx] += marginal * marginal;
                prevHolds = nowHolds;
            }
        }

        // Compute estimates and standard errors
        Map<String, Double> shapleyMap = new LinkedHashMap<>(n * 2);
        Map<String, Double> stdErrMap  = new LinkedHashMap<>(n * 2);

        for (int i = 0; i < n; i++) {
            String player = players.get(i);
            double mean = sumMarg[i] / samples;
            // Sample variance: Var = (E[X²] - E[X]²) = (sumSq/samples - mean²)
            // Use Bessel-corrected population std-error: σ/√samples where σ² estimated from sample
            double variance = (sumMargSq[i] / samples) - (mean * mean);
            // Clamp to 0 for floating-point noise
            if (variance < 0.0) variance = 0.0;
            double stdError = Math.sqrt(variance / samples);
            shapleyMap.put(player, mean);
            stdErrMap.put(player, stdError);
        }

        return new ShapleyReport(shapleyMap, stdErrMap, samples, seed, targetDelta);
    }
}
