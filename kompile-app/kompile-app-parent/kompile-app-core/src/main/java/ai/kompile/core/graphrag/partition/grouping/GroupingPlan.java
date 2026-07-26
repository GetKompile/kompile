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

package ai.kompile.core.graphrag.partition.grouping;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What grouping decided, and why.
 *
 * <p>The plan is meant to be read, not just consumed. A partition run over a group is a claim about
 * several subjects at once, and someone reconciling that claim later needs to know which subjects
 * were meant to be read together, which were pulled out as bridges, and what the grouping gave up —
 * a component too large for the cap was split, and the links crossing that split were not seen by
 * either half. That is what {@link #notes()} carries.</p>
 *
 * @param policy  grouping in force; part of how the resulting partitions were decided
 * @param groups  the partitions to run, in id order
 * @param bridges subjects classified as bridges, in id order, whatever was then done with them
 * @param notes   what the grouping had to give up, in the order it happened
 */
public record GroupingPlan(
        GroupingPolicy policy,
        List<EntityGroup> groups,
        List<String> bridges,
        List<String> notes) {

    public GroupingPlan {
        policy = policy == null ? GroupingPolicy.defaults() : policy;
        groups = groups == null ? List.of() : List.copyOf(groups);
        bridges = bridges == null ? List.of() : List.copyOf(bridges);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static GroupingPlan empty(GroupingPolicy policy) {
        return new GroupingPlan(policy, List.of(), List.of(), List.of());
    }

    /** Every group that reads {@code entityId} — more than one only under a sharing policy. */
    public List<EntityGroup> groupsFor(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            return List.of();
        }
        String subject = entityId.trim();
        List<EntityGroup> found = new ArrayList<>(1);
        for (EntityGroup group : groups) {
            if (group.contains(subject)) {
                found.add(group);
            }
        }
        return List.copyOf(found);
    }

    /** The group that owns {@code entityId}; there is at most one, whatever the bridge policy. */
    public Optional<EntityGroup> owner(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            return Optional.empty();
        }
        String subject = entityId.trim();
        return groups.stream().filter(group -> group.owns(subject)).findFirst();
    }

    public boolean isBridge(String entityId) {
        return entityId != null && bridges.contains(entityId.trim());
    }

    /** Distinct subjects across every group — what this plan actually covers. */
    public int subjectsCovered() {
        Set<String> subjects = new LinkedHashSet<>();
        groups.forEach(group -> subjects.addAll(group.members()));
        return subjects.size();
    }

    /**
     * Reads beyond one per subject, caused by sharing a bridge into several groups.
     *
     * <p>Worth surfacing rather than hiding: it is the price of {@link BridgePolicy#SHARE}, and a
     * caller that did not expect to pay it should be able to see the bill.</p>
     */
    public int duplicatedReads() {
        int total = 0;
        for (EntityGroup group : groups) {
            total += group.sharedBridges().size();
        }
        return total;
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder()
                .append(groups.size()).append(groups.size() == 1 ? " group" : " groups")
                .append(" over ").append(subjectsCovered()).append(" subjects");
        if (!bridges.isEmpty()) {
            sb.append(", ").append(bridges.size()).append(" bridge(s)");
        }
        int duplicated = duplicatedReads();
        if (duplicated > 0) {
            sb.append(", ").append(duplicated).append(" duplicated read(s)");
        }
        sb.append(" [").append(policy.version()).append("]");
        if (!notes.isEmpty()) {
            sb.append("; ").append(String.join("; ", notes));
        }
        return sb.toString();
    }
}
