/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining.causal;

import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;

import java.util.Collection;

/**
 * Statistical measures over a {@link DirectlyFollowsGraph} that turn raw co-occurrence into evidence of
 * dependency. Two complementary signals:
 *
 * <ul>
 *   <li><b>dependency</b> — the Heuristics-Miner measure {@code (|a→b|−|b→a|)/(|a→b|+|b→a|+1)}: how
 *       <em>directed</em> the relation is (near +1 = a strongly leads to b; near 0 = symmetric/parallel);</li>
 *   <li><b>χ² independence</b> — a 2×2 test of whether b follows a more than b's overall base rate as a
 *       successor, separating a genuine dependency from a coincidence of two common activities.</li>
 * </ul>
 *
 * These are exactly the data-driven edge strengths the noisy-OR Bayesian CPTs and PSL {@code Link} atoms
 * otherwise have to estimate structurally.
 */
public final class DependencyMeasures {

    /** χ² critical value for 1 degree of freedom at p = 0.05. */
    public static final double CHI2_CRITICAL_0_05 = 3.841;

    private DependencyMeasures() {
    }

    /** Heuristics-Miner dependency measure for {@code a → b} (self-loops use the length-1 form). */
    public static double dependency(DirectlyFollowsGraph dfg, String a, String b) {
        double ab = dfg.follows(a, b);
        if (a.equals(b)) {
            return ab / (ab + 1.0);
        }
        double ba = dfg.follows(b, a);
        return (ab - ba) / (ab + ba + 1.0);
    }

    /**
     * χ² statistic (1 d.f.) for the 2×2 table {predecessor is/ isn't {@code a}} × {successor is/ isn't
     * {@code b}} over all directly-follows transitions. Higher means b follows a far more (or less) than
     * chance; compare against {@link #CHI2_CRITICAL_0_05}. Returns 0 when the table is degenerate.
     */
    public static double chiSquare(DirectlyFollowsGraph dfg, String a, String b) {
        long obs = dfg.follows(a, b);
        long rowTotal = sum(dfg.successors(a).values());      // transitions out of a
        long colTotal = sum(dfg.predecessors(b).values());    // transitions into b
        long n = dfg.totalArcWeight();
        if (n <= 0 || rowTotal <= 0 || colTotal <= 0) {
            return 0.0;
        }
        double aCell = obs;
        double bCell = rowTotal - obs;
        double cCell = colTotal - obs;
        double dCell = (double) n - rowTotal - colTotal + obs;
        double denom = (aCell + bCell) * (cCell + dCell) * (aCell + cCell) * (bCell + dCell);
        if (denom <= 0) {
            return 0.0;
        }
        double diff = aCell * dCell - bCell * cCell;
        return (n * diff * diff) / denom;
    }

    private static long sum(Collection<Long> values) {
        long s = 0;
        for (long v : values) {
            s += v;
        }
        return s;
    }
}
