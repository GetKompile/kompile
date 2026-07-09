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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of a Monte-Carlo Shapley attribution run.
 *
 * <p>Contains per-player Shapley values, per-player standard errors, and
 * summary metadata (sample count, seed, efficiency check).</p>
 *
 * <h2>Efficiency axiom</h2>
 * <p>The Shapley values satisfy the <em>efficiency</em> axiom:
 * {@code sum(shapley.values()) ≈ holds(allPlayers) − holds(∅)}.
 * For a crisp claim this difference is in {0, 1}.
 * Verify via {@link #sum()} and compare to {@code targetDelta}.</p>
 *
 * <h2>Standard error</h2>
 * <p>Each player's standard error is computed from the per-sample variance of
 * marginal contributions.  The estimator error is O(1/√samples) for the
 * Monte-Carlo permutation estimator (see Castro, Gómez &amp; Tejada, 2009).
 * Double the {@code samples} to halve the standard error.</p>
 *
 * @param shapley      per-player Shapley value estimates (player → value)
 * @param stdError     per-player standard error of the Shapley estimate
 * @param samples      number of Monte-Carlo permutations used
 * @param seed         random seed used (for reproducibility)
 * @param targetDelta  {@code 1{holds(allPlayers)} − 1{holds(∅)}} — should ≈ {@link #sum()}
 * @param bySource     Shapley values aggregated by {@link ai.kompile.graph.reasoning.fol.Fact#sourceId}
 *                     (sum of member player values per source); may be empty if not computed
 */
public record ShapleyReport(
        Map<String, Double> shapley,
        Map<String, Double> stdError,
        int samples,
        long seed,
        double targetDelta,
        Map<String, Double> bySource) {

    public ShapleyReport {
        Objects.requireNonNull(shapley, "shapley");
        Objects.requireNonNull(stdError, "stdError");
        Objects.requireNonNull(bySource, "bySource");
        shapley = Collections.unmodifiableMap(shapley);
        stdError = Collections.unmodifiableMap(stdError);
        bySource = Collections.unmodifiableMap(bySource);
    }

    /**
     * Convenience constructor without {@code bySource} — aggregated source map is empty.
     *
     * @param shapley     per-player Shapley values
     * @param stdError    per-player standard errors
     * @param samples     sample count
     * @param seed        random seed
     * @param targetDelta efficiency target ({@code holds(all)−holds(∅)} ∈ {0,1})
     */
    public ShapleyReport(Map<String, Double> shapley,
                         Map<String, Double> stdError,
                         int samples,
                         long seed,
                         double targetDelta) {
        this(shapley, stdError, samples, seed, targetDelta, Map.of());
    }

    /**
     * Sum of all Shapley values.
     *
     * <p>By the efficiency axiom this should be approximately equal to
     * {@link #targetDelta} (the difference in game value between the grand coalition
     * and the empty coalition).  Deviations beyond a few standard errors indicate
     * either too few samples or a degenerate claim (all Shapley values should be 0
     * when the claim never changes value regardless of the coalition).</p>
     *
     * @return sum of shapley values (may be negative when Monte-Carlo error dominates)
     */
    public double sum() {
        double s = 0.0;
        for (double v : shapley.values()) s += v;
        return s;
    }

    /**
     * Return the {@code k} players with the highest Shapley values, in descending order.
     *
     * <p>Ties are broken arbitrarily (insertion order of the underlying map).</p>
     *
     * @param k maximum number of top contributors to return
     * @return ordered list of (player, value) entries, length ≤ min(k, players)
     * @throws IllegalArgumentException if {@code k < 1}
     */
    public List<Map.Entry<String, Double>> topContributors(int k) {
        if (k < 1) throw new IllegalArgumentException("k must be ≥ 1, got: " + k);
        List<Map.Entry<String, Double>> entries = new ArrayList<>(shapley.entrySet());
        entries.sort(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()));
        return Collections.unmodifiableList(entries.subList(0, Math.min(k, entries.size())));
    }

    /**
     * Return the Shapley value for a specific player, or {@code 0.0} if the player
     * was not evaluated.
     *
     * @param player the player (fact atom key)
     * @return estimated Shapley value
     */
    public double shapleyFor(String player) {
        return shapley.getOrDefault(player, 0.0);
    }

    /**
     * Return the standard error for a specific player, or {@code 0.0} if the player
     * was not evaluated.
     *
     * @param player the player (fact atom key)
     * @return estimated standard error
     */
    public double stdErrorFor(String player) {
        return stdError.getOrDefault(player, 0.0);
    }

    @Override
    public String toString() {
        return "ShapleyReport{samples=" + samples
                + ", seed=" + seed
                + ", sum=" + String.format("%.4f", sum())
                + ", targetDelta=" + String.format("%.4f", targetDelta)
                + ", players=" + shapley.size()
                + '}';
    }
}
