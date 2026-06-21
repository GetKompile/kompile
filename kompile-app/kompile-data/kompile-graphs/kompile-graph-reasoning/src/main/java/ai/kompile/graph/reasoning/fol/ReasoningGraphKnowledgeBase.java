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
package ai.kompile.graph.reasoning.fol;

import ai.kompile.graph.reasoning.mebn.logic.KnowledgeBase;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.model.TemporalInterval;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An in-library {@link KnowledgeBase} implementation backed entirely by a generic
 * {@link ReasoningGraph}.
 *
 * <p>This is the keystone that lets the MEBN and FOL engines run <em>entirely inside</em>
 * this library without any external store. Previously the only {@code KnowledgeBase}
 * implementation ({@code GraphKnowledgeBase}) lived in {@code kompile-event-attribution}
 * and was coupled to the JPA KG store. Now every {@link ai.kompile.graph.reasoning.mebn.logic.LogicalConstraint},
 * every {@link ai.kompile.graph.reasoning.mebn.MFrag} context check, and the
 * {@link ai.kompile.graph.reasoning.mebn.SSBNGenerator} can run against any
 * {@code ReasoningGraph} without a Spring context or a database.</p>
 *
 * <h3>Mapping</h3>
 * <ul>
 *   <li>{@link #entityExists} → {@link ReasoningGraph#containsEntity}</li>
 *   <li>{@link #edgeExists} → any outgoing relation whose target matches</li>
 *   <li>{@link #edgeExistsOfType} → same, plus {@link GraphRelation#type()} must match (case-insensitive)</li>
 *   <li>{@link #getEntityType} → {@link GraphEntity#type()}, empty string returns empty Optional</li>
 *   <li>{@link #getMetadata} → first checks {@link GraphEntity#stringAttribute(String)},
 *       then {@link GraphEntity#tags()} membership (tag is treated as key=tag, value="true")</li>
 *   <li>{@link #getEdgeWeight} → highest-weight outgoing relation to target (if multiple edges exist)</li>
 *   <li>{@link #getEntitiesOfType} → all entities whose {@link GraphEntity#type()} matches (case-insensitive)</li>
 *   <li>{@link #getConnectedEntities} → union of source/target ids across
 *       {@link ReasoningGraph#relationsOf}</li>
 *   <li>{@link #shareProperty} → entities share a tag name, or share the same string value for an attribute key</li>
 * </ul>
 */
public final class ReasoningGraphKnowledgeBase implements KnowledgeBase {

    private final ReasoningGraph graph;

    /**
     * Construct a knowledge base backed by the given graph.
     *
     * @param graph the graph to query; must not be {@code null}
     */
    public ReasoningGraphKnowledgeBase(ReasoningGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph must not be null");
    }

    /** The underlying graph (for advanced callers that need raw access). */
    public ReasoningGraph graph() {
        return graph;
    }

    // ─── Entity predicates ──────────────────────────────────────────────────────

    @Override
    public boolean entityExists(String entityId) {
        if (entityId == null) return false;
        return graph.containsEntity(entityId);
    }

    // ─── Edge predicates ────────────────────────────────────────────────────────

    @Override
    public boolean edgeExists(String sourceId, String targetId) {
        if (sourceId == null || targetId == null) return false;
        List<GraphRelation> out = graph.outgoing(sourceId);
        for (GraphRelation r : out) {
            if (targetId.equals(r.targetId())) return true;
        }
        // Also check undirected edges stored in the incoming index
        List<GraphRelation> in = graph.incoming(sourceId);
        for (GraphRelation r : in) {
            if (!r.directed() && targetId.equals(r.sourceId())) return true;
        }
        return false;
    }

    @Override
    public boolean edgeExistsOfType(String sourceId, String targetId, String edgeType) {
        if (sourceId == null || targetId == null || edgeType == null) return false;
        List<GraphRelation> out = graph.outgoing(sourceId);
        for (GraphRelation r : out) {
            if (targetId.equals(r.targetId()) && edgeType.equalsIgnoreCase(r.type())) return true;
        }
        // Undirected: also try flipped source/target
        List<GraphRelation> in = graph.incoming(sourceId);
        for (GraphRelation r : in) {
            if (!r.directed() && targetId.equals(r.sourceId())
                    && edgeType.equalsIgnoreCase(r.type())) return true;
        }
        return false;
    }

    // ─── Entity property accessors ──────────────────────────────────────────────

    @Override
    public Optional<String> getEntityType(String entityId) {
        if (entityId == null) return Optional.empty();
        return graph.entity(entityId)
                .map(GraphEntity::type)
                .filter(t -> !t.isEmpty());
    }

    @Override
    public Optional<String> getMetadata(String entityId, String metadataKey) {
        if (entityId == null || metadataKey == null) return Optional.empty();
        return graph.entity(entityId).flatMap(e -> {
            // 1. Try the attributes map first
            String v = e.stringAttribute(metadataKey);
            if (v != null) return Optional.of(v);
            // 2. Treat tags as boolean flags: if the tag equals the key, return "true"
            if (e.hasTag(metadataKey)) return Optional.of("true");
            return Optional.empty();
        });
    }

    @Override
    public Optional<Double> getEdgeWeight(String sourceId, String targetId) {
        if (sourceId == null || targetId == null) return Optional.empty();
        double best = Double.NEGATIVE_INFINITY;
        boolean found = false;
        for (GraphRelation r : graph.outgoing(sourceId)) {
            if (targetId.equals(r.targetId())) {
                if (r.weight() > best) {
                    best = r.weight();
                    found = true;
                }
            }
        }
        // Undirected fallback
        if (!found) {
            for (GraphRelation r : graph.incoming(sourceId)) {
                if (!r.directed() && targetId.equals(r.sourceId())) {
                    if (r.weight() > best) {
                        best = r.weight();
                        found = true;
                    }
                }
            }
        }
        return found ? Optional.of(best) : Optional.empty();
    }

    // ─── Set-returning queries ───────────────────────────────────────────────────

    @Override
    public Set<String> getEntitiesOfType(String typeName) {
        if (typeName == null) return Set.of();
        Set<String> result = new HashSet<>();
        for (GraphEntity e : graph.entities()) {
            if (typeName.equalsIgnoreCase(e.type())) {
                result.add(e.id());
            }
        }
        return result;
    }

    @Override
    public Set<String> getConnectedEntities(String entityId) {
        if (entityId == null) return Set.of();
        Set<String> connected = new HashSet<>();
        for (GraphRelation r : graph.relationsOf(entityId)) {
            // For directed relations: the "other end" depends on which end entityId is
            if (entityId.equals(r.sourceId())) {
                connected.add(r.targetId());
            } else {
                connected.add(r.sourceId());
            }
        }
        return connected;
    }

    // ─── Temporal accessors ─────────────────────────────────────────────────────

    @Override
    public Optional<Instant> getTimestamp(String entityId) {
        if (entityId == null) return Optional.empty();
        return graph.entity(entityId).flatMap(GraphEntity::timestampOpt);
    }

    @Override
    public Optional<TemporalInterval> getValidTime(String entityId) {
        if (entityId == null) return Optional.empty();
        return graph.entity(entityId).map(GraphEntity::validTime);
    }

    // ─── Structural property checks ──────────────────────────────────────────────

    @Override
    public boolean shareProperty(String entityId1, String entityId2, String propertyKey) {
        if (entityId1 == null || entityId2 == null || propertyKey == null) return false;
        Optional<GraphEntity> e1 = graph.entity(entityId1);
        Optional<GraphEntity> e2 = graph.entity(entityId2);
        if (e1.isEmpty() || e2.isEmpty()) return false;

        GraphEntity a = e1.get();
        GraphEntity b = e2.get();

        // Check tag intersection: both carry a tag named propertyKey
        if (a.hasTag(propertyKey) && b.hasTag(propertyKey)) return true;

        // Check shared attribute value for the given key
        String v1 = a.stringAttribute(propertyKey);
        String v2 = b.stringAttribute(propertyKey);
        return v1 != null && v1.equals(v2);
    }
}
