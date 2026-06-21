/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.mebn.logic;

import ai.kompile.graph.reasoning.model.TemporalInterval;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Interface to the knowledge graph state used for evaluating logical constraints.
 *
 * <p>Provides the atomic predicates that {@link LogicalConstraint}s can test
 * during SSBN generation. This abstracts the KG service into a clean interface
 * for logical reasoning.</p>
 */
public interface KnowledgeBase {

    /**
     * Check if an entity (KG node) exists.
     */
    boolean entityExists(String entityId);

    /**
     * Check if an edge exists between two entities (directed: source → target).
     */
    boolean edgeExists(String sourceId, String targetId);

    /**
     * Check if a specific type of edge exists between two entities.
     */
    boolean edgeExistsOfType(String sourceId, String targetId, String edgeType);

    /**
     * Get the entity type (NodeLevel) of a KG node.
     */
    Optional<String> getEntityType(String entityId);

    /**
     * Get a metadata value for an entity.
     */
    Optional<String> getMetadata(String entityId, String metadataKey);

    /**
     * Get the edge weight between two entities (if an edge exists).
     */
    Optional<Double> getEdgeWeight(String sourceId, String targetId);

    /**
     * Get all entity IDs of a given type.
     */
    Set<String> getEntitiesOfType(String typeName);

    /**
     * Get all entity IDs connected to a given entity.
     */
    Set<String> getConnectedEntities(String entityId);

    /**
     * Check if two entities share a specific property value.
     */
    boolean shareProperty(String entityId1, String entityId2, String propertyKey);

    /**
     * The event-occurrence timestamp of an entity ({@link ai.kompile.graph.reasoning.model.GraphEntity#timestamp()}),
     * or empty if none is set.
     *
     * <p>Default implementation returns {@link Optional#empty()} so that existing
     * {@code KnowledgeBase} implementations that pre-date T2 compile without changes.</p>
     *
     * @param entityId the entity id; may be {@code null} (returns empty)
     * @return the entity's timestamp wrapped in an {@link Optional}, or empty
     */
    default Optional<Instant> getTimestamp(String entityId) {
        return Optional.empty();
    }

    /**
     * The valid-time interval of an entity ({@link ai.kompile.graph.reasoning.model.GraphEntity#validTime()}),
     * or empty if none is set.
     *
     * <p>Default implementation returns {@link Optional#empty()} so that existing
     * {@code KnowledgeBase} implementations that pre-date T2 compile without changes.</p>
     *
     * @param entityId the entity id; may be {@code null} (returns empty)
     * @return the entity's valid-time interval wrapped in an {@link Optional}, or empty
     */
    default Optional<TemporalInterval> getValidTime(String entityId) {
        return Optional.empty();
    }
}
