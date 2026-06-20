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

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * The output of the {@link HeuristicsMiner}: a dependency net over process activities.
 *
 * <p>A dependency net is a directed graph where each arc {@code a → b} has survived the
 * dependency-measure threshold cut (Weijters &amp; van der Aalst). It is more permissive than a
 * process tree — it can represent short loops and non-block-structured concurrency — but is still
 * compact enough to visualise and interpret directly.
 *
 * <p>Instances are immutable. Obtain via {@link HeuristicsMiner#mine(ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph, double)}.
 */
public final class HeuristicsNet {

    /**
     * A dependency arc kept by the miner, carrying both the raw directly-follows frequency and the
     * Heuristics-Miner dependency value.
     */
    public record DependencyArc(String from, String to, long frequency, double dependencyValue) {}

    private final Set<String> activities;
    private final Set<DependencyArc> arcs;
    private final Set<String> selfLoopActivities;
    private final Map<String, Long> startActivities;
    private final Map<String, Long> endActivities;

    HeuristicsNet(Set<String> activities,
                  Set<DependencyArc> arcs,
                  Set<String> selfLoopActivities,
                  Map<String, Long> startActivities,
                  Map<String, Long> endActivities) {
        this.activities = activities;
        this.arcs = arcs;
        this.selfLoopActivities = selfLoopActivities;
        this.startActivities = startActivities;
        this.endActivities = endActivities;
    }

    /** All activities in the net (the node set). */
    public Set<String> activities() {
        return Collections.unmodifiableSet(activities);
    }

    /** Dependency arcs whose measure met or exceeded the threshold used during mining. */
    public Set<DependencyArc> arcs() {
        return Collections.unmodifiableSet(arcs);
    }

    /**
     * Activities detected as length-1 self-loops (a directly follows itself at least once); the
     * dependency measure for a self-loop is {@code |a→a| / (|a→a| + 1)} which can independently
     * cross the threshold.
     */
    public Set<String> selfLoopActivities() {
        return Collections.unmodifiableSet(selfLoopActivities);
    }

    /** Activities that began at least one trace, with their start-frequency counts. */
    public Map<String, Long> startActivities() {
        return Collections.unmodifiableMap(startActivities);
    }

    /** Activities that ended at least one trace, with their end-frequency counts. */
    public Map<String, Long> endActivities() {
        return Collections.unmodifiableMap(endActivities);
    }

    @Override
    public String toString() {
        return "HeuristicsNet{activities=" + activities.size()
                + ", arcs=" + arcs.size()
                + ", selfLoops=" + selfLoopActivities
                + ", starts=" + startActivities.keySet()
                + ", ends=" + endActivities.keySet() + '}';
    }
}
