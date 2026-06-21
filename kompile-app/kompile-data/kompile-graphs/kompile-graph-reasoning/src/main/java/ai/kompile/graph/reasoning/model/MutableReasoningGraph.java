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
package ai.kompile.graph.reasoning.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Standard in-memory {@link ReasoningGraph} with O(1) entity lookup and indexed incoming/outgoing
 * adjacency, maintained incrementally as entities and relations are added.
 *
 * <p>This is the workhorse adapters build: project a source store's nodes/edges into one of these
 * and hand it to the engines. It is not thread-safe; build it on one thread, then read.</p>
 *
 * <p>Insertion order is preserved for both entities and relations so that results are
 * deterministic regardless of the underlying source's iteration order.</p>
 */
public final class MutableReasoningGraph implements ReasoningGraph {

    private final Map<String, GraphEntity> entities = new LinkedHashMap<>();
    private final List<GraphRelation> relations = new ArrayList<>();
    private final Map<String, List<GraphRelation>> outgoing = new LinkedHashMap<>();
    private final Map<String, List<GraphRelation>> incoming = new LinkedHashMap<>();

    public MutableReasoningGraph() {
    }

    /** Add or replace an entity (keyed by {@link GraphEntity#id()}). Returns {@code this}. */
    public MutableReasoningGraph addEntity(GraphEntity entity) {
        Objects.requireNonNull(entity, "entity");
        entities.put(entity.id(), entity);
        return this;
    }

    /** Convenience: add a {@link SimpleGraphEntity} by id/type/label. */
    public MutableReasoningGraph addEntity(String id, String type, String label) {
        return addEntity(SimpleGraphEntity.of(id, type, label));
    }

    /**
     * Add a relation and update the adjacency indices. Endpoints are <em>not</em> required to
     * exist yet (adapters may add relations and entities in any order), but
     * {@link #outgoing(String)}/{@link #incoming(String)} only ever return added relations.
     * Returns {@code this}.
     */
    public MutableReasoningGraph addRelation(GraphRelation relation) {
        Objects.requireNonNull(relation, "relation");
        relations.add(relation);
        outgoing.computeIfAbsent(relation.sourceId(), k -> new ArrayList<>()).add(relation);
        incoming.computeIfAbsent(relation.targetId(), k -> new ArrayList<>()).add(relation);
        return this;
    }

    /** Convenience: add a directed {@link SimpleGraphRelation}. */
    public MutableReasoningGraph addRelation(String id, String sourceId, String targetId, String type, double weight) {
        return addRelation(SimpleGraphRelation.directed(id, sourceId, targetId, type, weight));
    }

    @Override
    public Collection<GraphEntity> entities() {
        return Collections.unmodifiableCollection(entities.values());
    }

    @Override
    public Collection<GraphRelation> relations() {
        return Collections.unmodifiableList(relations);
    }

    @Override
    public Optional<GraphEntity> entity(String id) {
        return Optional.ofNullable(entities.get(id));
    }

    @Override
    public List<GraphRelation> outgoing(String entityId) {
        return Collections.unmodifiableList(outgoing.getOrDefault(entityId, List.of()));
    }

    @Override
    public List<GraphRelation> incoming(String entityId) {
        return Collections.unmodifiableList(incoming.getOrDefault(entityId, List.of()));
    }

    @Override
    public List<GraphRelation> relationsOf(String entityId) {
        List<GraphRelation> out = outgoing.getOrDefault(entityId, List.of());
        List<GraphRelation> in = incoming.getOrDefault(entityId, List.of());
        List<GraphRelation> all = new ArrayList<>(out.size() + in.size());
        all.addAll(out);
        all.addAll(in);
        return Collections.unmodifiableList(all);
    }
}
