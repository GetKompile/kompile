/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian.mebn.logic;

import ai.kompile.knowledgegraph.domain.EdgeType;

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
    boolean edgeExistsOfType(String sourceId, String targetId, EdgeType edgeType);

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
}
