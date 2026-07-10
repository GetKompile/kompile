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

package ai.kompile.process.discovery.mining.dfg;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A Directly-Follows Graph (DFG): the interpretable backbone of process discovery.
 *
 * <p>Nodes are activities; a directed arc {@code a → b} carries the number of times {@code b}
 * directly followed {@code a} within a trace. {@link #startActivities()} / {@link #endActivities()}
 * record how often each activity began / ended a trace (the implicit ▷ start and □ end markers).
 * Everything is a frequency, so the structure is human-readable as-is — and it is the single input
 * the Inductive and Heuristics miners cut on, as well as the source of the data-driven edge weights
 * that feed the causal/Bayesian layer.
 *
 * <p>Instances are immutable; build with {@link DfgBuilder} and derive filtered copies with
 * {@link #filter(long)}.
 */
public final class DirectlyFollowsGraph implements Serializable {

    private static final long serialVersionUID = 1L;

    /** A directed directly-follows arc between two activities. */
    public record Arc(String from, String to) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    private final Set<String> activities;
    private final Map<String, Map<String, Long>> follows; // from -> (to -> count)
    private final Map<String, Long> startActivities;
    private final Map<String, Long> endActivities;

    DirectlyFollowsGraph(Set<String> activities,
                         Map<String, Map<String, Long>> follows,
                         Map<String, Long> startActivities,
                         Map<String, Long> endActivities) {
        this.activities = activities;
        this.follows = follows;
        this.startActivities = startActivities;
        this.endActivities = endActivities;
    }

    public Set<String> activities() {
        return Collections.unmodifiableSet(activities);
    }

    public Map<String, Long> startActivities() {
        return Collections.unmodifiableMap(startActivities);
    }

    public Map<String, Long> endActivities() {
        return Collections.unmodifiableMap(endActivities);
    }

    public boolean isEmpty() {
        return activities.isEmpty();
    }

    /** How many times {@code b} directly followed {@code a} (0 if never). */
    public long follows(String a, String b) {
        return follows.getOrDefault(a, Map.of()).getOrDefault(b, 0L);
    }

    /** True if {@code b} ever directly followed {@code a}. */
    public boolean hasArc(String a, String b) {
        return follows(a, b) > 0;
    }

    /** Activities that directly follow {@code a}, with counts (empty if none). */
    public Map<String, Long> successors(String a) {
        return Collections.unmodifiableMap(follows.getOrDefault(a, Map.of()));
    }

    /** Activities that directly precede {@code b}, with counts (computed on demand; DFGs are small). */
    public Map<String, Long> predecessors(String b) {
        Map<String, Long> preds = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Long>> fromEntry : follows.entrySet()) {
            Long c = fromEntry.getValue().get(b);
            if (c != null && c > 0) {
                preds.put(fromEntry.getKey(), c);
            }
        }
        return preds;
    }

    /** All directly-follows arcs as {@code (from,to) → count}, in insertion order. */
    public Map<Arc, Long> arcs() {
        Map<Arc, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Long>> fromEntry : follows.entrySet()) {
            String from = fromEntry.getKey();
            for (Map.Entry<String, Long> toEntry : fromEntry.getValue().entrySet()) {
                out.put(new Arc(from, toEntry.getKey()), toEntry.getValue());
            }
        }
        return out;
    }

    /** Total observed directly-follows transitions (sum of all arc counts). */
    public long totalArcWeight() {
        long sum = 0;
        for (Map<String, Long> tos : follows.values()) {
            for (long c : tos.values()) {
                sum += c;
            }
        }
        return sum;
    }

    /**
     * Returns a copy keeping only arcs whose count is at least {@code minArcCount}. This is the
     * interpretable "frequency threshold" the UI slider exposes and the simplest form of the IMf
     * infrequent-behaviour filter. Activities and start/end markers are preserved so the miner can
     * still reason about them even when all their arcs are pruned.
     */
    public DirectlyFollowsGraph filter(long minArcCount) {
        if (minArcCount <= 1) {
            return this;
        }
        Map<String, Map<String, Long>> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Long>> fromEntry : follows.entrySet()) {
            for (Map.Entry<String, Long> toEntry : fromEntry.getValue().entrySet()) {
                if (toEntry.getValue() >= minArcCount) {
                    kept.computeIfAbsent(fromEntry.getKey(), k -> new LinkedHashMap<>())
                            .put(toEntry.getKey(), toEntry.getValue());
                }
            }
        }
        return new DirectlyFollowsGraph(
                new LinkedHashSet<>(activities),
                kept,
                new LinkedHashMap<>(startActivities),
                new LinkedHashMap<>(endActivities));
    }
}
