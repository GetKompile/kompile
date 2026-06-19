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
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * {@link KnowledgeBase} implementation backed by the Kompile knowledge graph.
 *
 * <p>Provides atomic predicates for evaluating first-order logical constraints
 * during SSBN generation. Each predicate call queries the KG service.</p>
 */
public class GraphKnowledgeBase implements KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(GraphKnowledgeBase.class);
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private final KnowledgeGraphService graphService;

    /**
     * Cache of entity type assignments: type name → set of entity IDs.
     */
    private final Map<String, Set<String>> entityTypeCache = new HashMap<>();

    public GraphKnowledgeBase(KnowledgeGraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * Register entities of a specific type for quantifier evaluation.
     */
    public void registerEntityType(String typeName, Set<String> entityIds) {
        entityTypeCache.put(typeName, new LinkedHashSet<>(entityIds));
    }

    /**
     * Auto-populate entity type cache from a set of KG node IDs.
     * Queries each node from the KG and registers it under its NodeLevel type name.
     * This makes quantifier evaluation robust without requiring callers to
     * manually call registerEntityType() for every type.
     *
     * @param nodeIds set of KG node IDs to query and register
     */
    public void autoPopulate(Set<String> nodeIds) {
        Map<String, Set<String>> typeGroups = new HashMap<>();
        for (String nodeId : nodeIds) {
            graphService.getNode(nodeId).ifPresent(node -> {
                String typeName = node.getNodeType().name();
                typeGroups.computeIfAbsent(typeName, k -> new LinkedHashSet<>()).add(nodeId);
            });
        }
        for (Map.Entry<String, Set<String>> entry : typeGroups.entrySet()) {
            entityTypeCache.merge(entry.getKey(), entry.getValue(), (existing, newIds) -> {
                existing.addAll(newIds);
                return existing;
            });
        }
        log.debug("Auto-populated {} entity types from {} node IDs", typeGroups.size(), nodeIds.size());
    }

    /**
     * Get the underlying graph service for direct queries.
     */
    public KnowledgeGraphService getGraphService() {
        return graphService;
    }

    @Override
    public boolean entityExists(String entityId) {
        return graphService.getNode(entityId).isPresent();
    }

    @Override
    public boolean edgeExists(String sourceId, String targetId) {
        return graphService.edgeExists(sourceId, targetId);
    }

    @Override
    public boolean edgeExistsOfType(String sourceId, String targetId, EdgeType edgeType) {
        List<GraphEdge> edges = graphService.getEdgesForNode(sourceId);
        return edges.stream().anyMatch(e ->
                e.getEdgeType() == edgeType &&
                        e.getSourceNode() != null && e.getSourceNode().getNodeId().equals(sourceId) &&
                        e.getTargetNode() != null && e.getTargetNode().getNodeId().equals(targetId));
    }

    @Override
    public Optional<String> getEntityType(String entityId) {
        return graphService.getNode(entityId)
                .map(node -> node.getNodeType().name());
    }

    @Override
    public Optional<String> getMetadata(String entityId, String metadataKey) {
        return graphService.getNode(entityId)
                .flatMap(node -> extractMetadataValue(node.getMetadataJson(), metadataKey));
    }

    @Override
    public Optional<Double> getEdgeWeight(String sourceId, String targetId) {
        List<GraphEdge> edges = graphService.getEdgesForNode(sourceId);
        return edges.stream()
                .filter(e -> e.getSourceNode() != null && e.getSourceNode().getNodeId().equals(sourceId) &&
                        e.getTargetNode() != null && e.getTargetNode().getNodeId().equals(targetId))
                .map(GraphEdge::getWeight)
                .filter(Objects::nonNull)
                .findFirst();
    }

    @Override
    public Set<String> getEntitiesOfType(String typeName) {
        return entityTypeCache.getOrDefault(typeName, Set.of());
    }

    @Override
    public Set<String> getConnectedEntities(String entityId) {
        List<GraphEdge> edges = graphService.getEdgesForNode(entityId);
        Set<String> connected = new LinkedHashSet<>();
        for (GraphEdge edge : edges) {
            if (edge.getSourceNode() != null && edge.getSourceNode().getNodeId().equals(entityId)
                    && edge.getTargetNode() != null) {
                connected.add(edge.getTargetNode().getNodeId());
            }
            if (edge.getTargetNode() != null && edge.getTargetNode().getNodeId().equals(entityId)
                    && edge.getSourceNode() != null) {
                connected.add(edge.getSourceNode().getNodeId());
            }
        }
        return connected;
    }

    @Override
    public boolean shareProperty(String entityId1, String entityId2, String propertyKey) {
        Optional<String> val1 = getMetadata(entityId1, propertyKey);
        Optional<String> val2 = getMetadata(entityId2, propertyKey);
        return val1.isPresent() && val2.isPresent() && val1.get().equals(val2.get());
    }

    private Optional<String> extractMetadataValue(String metadataJson, String key) {
        if (metadataJson == null || metadataJson.isBlank()) return Optional.empty();
        try {
            Map<String, Object> metadata = MAPPER.readValue(metadataJson,
                    new TypeReference<Map<String, Object>>() {});
            Object value = metadata.get(key);
            return value != null ? Optional.of(value.toString()) : Optional.empty();
        } catch (Exception e) {
            log.debug("Failed to parse metadata JSON for key '{}': {}", key, e.getMessage());
            return Optional.empty();
        }
    }
}
