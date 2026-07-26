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
import java.util.Set;
import java.util.TreeSet;

/**
 * A set of subjects one partition is about.
 *
 * <p>The split between {@code core} and {@code sharedBridges} is what makes the group's identity
 * stable. The core is what the group <em>is</em>; a shared bridge is a subject that some other
 * group also reads, attached here so this group can see it. Re-classifying a bridge therefore does
 * not rename the group, while a genuine change of membership does — because a group with different
 * core members is a different claim and should not silently inherit the old one's coverage.</p>
 *
 * @param id            stable identity, derived from the core
 * @param core          subjects this group owns, sorted; never empty
 * @param sharedBridges subjects read here but owned elsewhere, sorted
 */
public record EntityGroup(String id, List<String> core, List<String> sharedBridges) {

    public EntityGroup {
        id = id == null ? null : id.trim();
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("a group needs an id");
        }
        core = sorted(core);
        if (core.isEmpty()) {
            throw new IllegalArgumentException("group " + id + " has no core subjects");
        }
        sharedBridges = sorted(sharedBridges);
    }

    /** A group owning {@code core}, with the id derived from it. */
    public static EntityGroup of(List<String> core) {
        List<String> members = sorted(core);
        return new EntityGroup(idFor(members), members, List.of());
    }

    /** A group of one — a subject with nothing to be grouped with, or a bridge standing alone. */
    public static EntityGroup ofSingle(String entityId) {
        return of(List.of(entityId));
    }

    /**
     * Identity for a set of core subjects: the smallest, plus how many more.
     *
     * <p>Legible on purpose — a partition id ends up in logs, stores and manifests, and an opaque
     * digest would make every one of those unreadable. It is unique because cores are disjoint, so
     * no two groups can share a smallest member.</p>
     */
    public static String idFor(List<String> core) {
        List<String> members = sorted(core);
        if (members.isEmpty()) {
            throw new IllegalArgumentException("a group id needs at least one subject");
        }
        return members.size() == 1 ? members.get(0) : members.get(0) + "+" + (members.size() - 1);
    }

    /** The subject the group is named after. */
    public String anchor() {
        return core.get(0);
    }

    /** Everything this group reads: the core first, then the bridges attached to it. */
    public List<String> members() {
        if (sharedBridges.isEmpty()) {
            return core;
        }
        List<String> all = new ArrayList<>(core.size() + sharedBridges.size());
        all.addAll(core);
        all.addAll(sharedBridges);
        return List.copyOf(all);
    }

    public int size() {
        return core.size() + sharedBridges.size();
    }

    /** True when this group is one subject and nothing else. */
    public boolean isSingleton() {
        return size() == 1;
    }

    /** True when the subject is read here, whether owned or attached. */
    public boolean contains(String entityId) {
        return core.contains(entityId) || sharedBridges.contains(entityId);
    }

    /** True when this group is the one that owns the subject. */
    public boolean owns(String entityId) {
        return core.contains(entityId);
    }

    /** The same group, also reading {@code entityId} on someone else's behalf. */
    public EntityGroup sharing(String entityId) {
        if (entityId == null || entityId.isBlank() || contains(entityId.trim())) {
            return this;
        }
        Set<String> merged = new LinkedHashSet<>(sharedBridges);
        merged.add(entityId.trim());
        return new EntityGroup(id, core, List.copyOf(merged));
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(id).append(": ").append(core.size())
                .append(core.size() == 1 ? " subject" : " subjects");
        if (!sharedBridges.isEmpty()) {
            sb.append(" + ").append(sharedBridges.size()).append(" shared (")
                    .append(String.join(", ", sharedBridges)).append(")");
        }
        return sb.toString();
    }

    private static List<String> sorted(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        // Sorted and de-duplicated: the order is part of the identity, so it cannot be left to
        // however the caller happened to accumulate the members.
        Set<String> cleaned = new TreeSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return List.copyOf(cleaned);
    }
}
