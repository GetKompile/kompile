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

package ai.kompile.process.discovery.mining.miner;

import ai.kompile.process.discovery.mining.causal.DependencyMeasures;
import ai.kompile.process.discovery.mining.dfg.DfgBuilder;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.log.EventLog;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Heuristics Miner (Weijters &amp; van der Aalst) — converts a {@link DirectlyFollowsGraph} into a
 * {@link HeuristicsNet} by retaining only the dependency arcs whose Heuristics-Miner measure meets
 * or exceeds a configurable threshold.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>For every ordered pair of distinct activities {@code (a, b)} compute
 *       {@code dep(a,b) = (|a→b| − |b→a|) / (|a→b| + |b→a| + 1)} via
 *       {@link DependencyMeasures#dependency(DirectlyFollowsGraph, String, String)}.</li>
 *   <li>Keep arc {@code a→b} if {@code dep(a,b) >= dependencyThreshold}.</li>
 *   <li>Detect length-1 self-loops: for every activity {@code a} where {@code |a→a| > 0},
 *       compute the self-loop measure {@code |a→a| / (|a→a| + 1)} (delegated to
 *       {@link DependencyMeasures#dependency} with {@code a == b}) and keep it if it also
 *       meets the threshold.</li>
 * </ol>
 *
 * <p>Start/end activities are taken directly from the DFG (they encode trace-boundary frequencies
 * already). No LLM, no external dependency.
 */
public final class HeuristicsMiner {

    private HeuristicsMiner() {
    }

    /**
     * Build the DFG from {@code log} and apply the dependency-threshold cut.
     *
     * @param log                the event log to mine
     * @param dependencyThreshold arcs with measure &lt; this value are pruned (typical: 0.5)
     * @return a {@link HeuristicsNet} for the log
     */
    public static HeuristicsNet mine(EventLog log, double dependencyThreshold) {
        return mine(DfgBuilder.build(log), dependencyThreshold);
    }

    /**
     * Apply the dependency-threshold cut directly to an already-built DFG.
     *
     * @param dfg                an already-built {@link DirectlyFollowsGraph}
     * @param dependencyThreshold arcs with measure &lt; this value are pruned (typical: 0.5)
     * @return a {@link HeuristicsNet} derived from {@code dfg}
     */
    public static HeuristicsNet mine(DirectlyFollowsGraph dfg, double dependencyThreshold) {
        Set<String> activities = new LinkedHashSet<>(dfg.activities());
        Set<HeuristicsNet.DependencyArc> keptArcs = new LinkedHashSet<>();
        Set<String> selfLoops = new LinkedHashSet<>();

        // --- self-loop detection (a→a arcs in the DFG) ---
        for (String a : activities) {
            long selfCount = dfg.follows(a, a);
            if (selfCount > 0) {
                // DependencyMeasures.dependency handles a==b → selfCount/(selfCount+1)
                double selfDep = DependencyMeasures.dependency(dfg, a, a);
                if (selfDep >= dependencyThreshold) {
                    selfLoops.add(a);
                }
            }
        }

        // --- directed dependency arcs (a != b) ---
        String[] actArr = activities.toArray(new String[0]);
        for (int i = 0; i < actArr.length; i++) {
            for (int j = 0; j < actArr.length; j++) {
                if (i == j) {
                    continue; // handled above
                }
                String a = actArr[i];
                String b = actArr[j];
                long freq = dfg.follows(a, b);
                if (freq == 0) {
                    continue; // no arc in the DFG at all — skip
                }
                double dep = DependencyMeasures.dependency(dfg, a, b);
                if (dep >= dependencyThreshold) {
                    keptArcs.add(new HeuristicsNet.DependencyArc(a, b, freq, dep));
                }
            }
        }

        // Preserve start/end activity maps from the DFG
        Map<String, Long> starts = new LinkedHashMap<>(dfg.startActivities());
        Map<String, Long> ends = new LinkedHashMap<>(dfg.endActivities());

        return new HeuristicsNet(activities, keptArcs, selfLoops, starts, ends);
    }
}
