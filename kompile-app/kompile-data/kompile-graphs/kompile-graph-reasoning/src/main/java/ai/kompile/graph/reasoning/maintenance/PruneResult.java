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
package ai.kompile.graph.reasoning.maintenance;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The decision output of a pruning policy: the set of entity and relation ids that should be
 * removed from the graph, each annotated with a human-readable reason.
 *
 * <p>This is a <em>decision only</em> — no store access or deletion is performed by any class in
 * this library. The calling code (e.g. a knowledge-graph pruner bean) reads these ids and applies
 * the deletions through its own store API.</p>
 *
 * <p>Instances are immutable and can be safely shared across threads.</p>
 */
public final class PruneResult {

    /** Entity ids to remove, mapped to the reason each was selected. */
    private final Map<String, String> entityReasons;

    /** Relation ids to remove, mapped to the reason each was selected. */
    private final Map<String, String> relationReasons;

    private PruneResult(Map<String, String> entityReasons, Map<String, String> relationReasons) {
        this.entityReasons = Collections.unmodifiableMap(new LinkedHashMap<>(entityReasons));
        this.relationReasons = Collections.unmodifiableMap(new LinkedHashMap<>(relationReasons));
    }

    /** Empty result — nothing to prune. */
    public static PruneResult empty() {
        return new PruneResult(Map.of(), Map.of());
    }

    /**
     * Build a {@code PruneResult} using the fluent {@link Builder}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Merge two results, combining their entity and relation maps.
     * If the same id appears in both, the reason from {@code other} wins.
     */
    public PruneResult merge(PruneResult other) {
        Map<String, String> entities = new LinkedHashMap<>(this.entityReasons);
        entities.putAll(other.entityReasons);
        Map<String, String> relations = new LinkedHashMap<>(this.relationReasons);
        relations.putAll(other.relationReasons);
        return new PruneResult(entities, relations);
    }

    /** The set of entity ids selected for removal. */
    public Set<String> entityIds() {
        return entityReasons.keySet();
    }

    /** The set of relation ids selected for removal. */
    public Set<String> relationIds() {
        return relationReasons.keySet();
    }

    /** The reason a given entity id was selected, or {@code null} if it was not selected. */
    public String entityReason(String entityId) {
        return entityReasons.get(entityId);
    }

    /** The reason a given relation id was selected, or {@code null} if it was not selected. */
    public String relationReason(String relationId) {
        return relationReasons.get(relationId);
    }

    /** All entity-id → reason mappings. */
    public Map<String, String> entityReasons() {
        return entityReasons;
    }

    /** All relation-id → reason mappings. */
    public Map<String, String> relationReasons() {
        return relationReasons;
    }

    /** Total number of elements (entities + relations) selected. */
    public int totalSelected() {
        return entityReasons.size() + relationReasons.size();
    }

    /** Whether no elements were selected. */
    public boolean isEmpty() {
        return entityReasons.isEmpty() && relationReasons.isEmpty();
    }

    @Override
    public String toString() {
        return "PruneResult{entities=" + entityReasons.size()
                + ", relations=" + relationReasons.size() + "}";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Builder
    // ─────────────────────────────────────────────────────────────────────────

    /** Fluent builder for {@link PruneResult}. */
    public static final class Builder {

        private final Map<String, String> entityReasons = new LinkedHashMap<>();
        private final Map<String, String> relationReasons = new LinkedHashMap<>();

        private Builder() {}

        /** Mark an entity id for removal with the given reason. */
        public Builder addEntity(String entityId, String reason) {
            entityReasons.put(entityId, reason);
            return this;
        }

        /** Mark a relation id for removal with the given reason. */
        public Builder addRelation(String relationId, String reason) {
            relationReasons.put(relationId, reason);
            return this;
        }

        /** Build an immutable {@link PruneResult}. */
        public PruneResult build() {
            return new PruneResult(entityReasons, relationReasons);
        }
    }
}
