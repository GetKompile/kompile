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
package ai.kompile.graph.reasoning.community;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The result of community detection on a {@link ai.kompile.graph.reasoning.model.ReasoningGraph}.
 *
 * <p>Immutable value type. Each entity id is mapped to an integer community id in {@code [0, communityCount-1]}.
 * Community ids are dense (no gaps) but otherwise have no semantic ordering.</p>
 *
 * <p>The {@link #modularity()} score measures how well the partition separates the graph:
 * <ul>
 *   <li>{@code > 0.3} — meaningful community structure</li>
 *   <li>{@code > 0.7} — strong community structure</li>
 *   <li>{@code ~0.0} — random or fully-connected graph</li>
 *   <li>{@code < 0.0} — anti-community (partition worse than random)</li>
 * </ul>
 * The value is in {@code [-0.5, 1.0]} for undirected graphs with self-loops excluded from
 * the null model.</p>
 */
public final class CommunityAssignment {

    /** Maps entity id → community id (integer in [0, communityCount-1]). */
    private final Map<String, Integer> assignments;

    /** Newman-Girvan modularity Q of this partition; in (-0.5, 1.0]. */
    private final double modularity;

    /** Number of distinct communities. */
    private final int communityCount;

    /**
     * Construct a CommunityAssignment.
     *
     * @param assignments a map of entity-id → community-id; copied defensively
     * @param modularity  the modularity score of this partition
     */
    public CommunityAssignment(Map<String, Integer> assignments, double modularity) {
        Objects.requireNonNull(assignments, "assignments");
        this.assignments = Collections.unmodifiableMap(new HashMap<>(assignments));
        this.modularity = modularity;
        // Compute communityCount from the dense set of ids present
        int maxId = assignments.values().stream().mapToInt(Integer::intValue).max().orElse(-1);
        this.communityCount = maxId + 1;
    }

    /**
     * The community id of the given entity id, or {@code -1} if the entity was not part of
     * the graph when community detection ran.
     *
     * @param entityId the entity to look up
     * @return community id in {@code [0, communityCount-1]}, or {@code -1} if absent
     */
    public int communityOf(String entityId) {
        return assignments.getOrDefault(entityId, -1);
    }

    /**
     * All entity ids whose community id equals {@code communityId}.
     *
     * @param communityId a community id in {@code [0, communityCount-1]}
     * @return unmodifiable set of entity ids in that community
     */
    public Set<String> membersOf(int communityId) {
        Set<String> members = new HashSet<>();
        for (Map.Entry<String, Integer> e : assignments.entrySet()) {
            if (e.getValue() == communityId) {
                members.add(e.getKey());
            }
        }
        return Collections.unmodifiableSet(members);
    }

    /**
     * The full assignment map (entity-id → community-id).
     *
     * @return unmodifiable view
     */
    public Map<String, Integer> assignments() {
        return assignments;
    }

    /**
     * Newman-Girvan modularity Q of this partition.
     * Higher is better; {@code > 0.3} indicates meaningful community structure.
     *
     * @return modularity score in approximately {@code (-0.5, 1.0]}
     */
    public double modularity() {
        return modularity;
    }

    /**
     * Number of distinct communities found (always {@code >= 1} for a non-empty graph).
     *
     * @return the number of communities
     */
    public int communityCount() {
        return communityCount;
    }

    @Override
    public String toString() {
        return "CommunityAssignment{communities=" + communityCount
                + ", modularity=" + String.format("%.4f", modularity)
                + ", entities=" + assignments.size() + "}";
    }
}
