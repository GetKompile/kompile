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

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * A unified, store-agnostic graph of {@link GraphEntity entities} and {@link GraphRelation
 * relations} that the reasoning engines (PSL, Bayesian, MEBN, causal) operate over.
 *
 * <p>This is the single abstraction the library reasons about. It is produced by adapters that
 * map a concrete source — the JPA knowledge graph, the vector/matrix store, a mined
 * directly-follows graph, a hand-built test fixture — onto a common shape. Nothing in this
 * interface references a persistence technology, Spring, or a specific store; that is the whole
 * point of the design.</p>
 *
 * <p>Implementations are expected to provide O(1) {@link #entity(String)} lookup and indexed
 * {@link #incoming(String)} / {@link #outgoing(String)} adjacency; see {@link MutableReasoningGraph}
 * for the standard in-memory implementation.</p>
 */
public interface ReasoningGraph {

    /** All entities in the graph, in a stable iteration order. */
    Collection<GraphEntity> entities();

    /** All relations in the graph, in a stable iteration order. */
    Collection<GraphRelation> relations();

    /** The entity with the given id, if present. */
    Optional<GraphEntity> entity(String id);

    /** Whether an entity with the given id exists. */
    default boolean containsEntity(String id) {
        return entity(id).isPresent();
    }

    /** Relations whose {@link GraphRelation#sourceId() source} is {@code entityId}. */
    List<GraphRelation> outgoing(String entityId);

    /** Relations whose {@link GraphRelation#targetId() target} is {@code entityId}. */
    List<GraphRelation> incoming(String entityId);

    /**
     * All relations incident to {@code entityId} (both directions). For an undirected reading,
     * callers should treat each relation symmetrically regardless of source/target.
     */
    List<GraphRelation> relationsOf(String entityId);

    /** Number of entities. */
    default int entityCount() {
        return entities().size();
    }

    /** Number of relations. */
    default int relationCount() {
        return relations().size();
    }

    /** Whether the graph has no entities. */
    default boolean isEmpty() {
        return entities().isEmpty();
    }
}
