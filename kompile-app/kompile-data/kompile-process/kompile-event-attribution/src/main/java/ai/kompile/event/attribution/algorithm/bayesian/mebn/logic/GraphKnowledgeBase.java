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

import ai.kompile.graph.reasoning.mebn.logic.KnowledgeBase;
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
    private final Long factSheetId;

    /**
     * Cache of entity type assignments: type name → set of entity IDs.
     */
    private final Map<String, Set<String>> entityTypeCache = new HashMap<>();
    private final Map<String, List<GraphEdge>> edgeCache = new HashMap<>();

    public GraphKnowledgeBase(KnowledgeGraphService graphService) {
        this(graphService, null);
    }

    /** Create a knowledge-base view whose node and edge predicates are fact-sheet isolated. */
    public GraphKnowledgeBase(KnowledgeGraphService graphService, Long factSheetId) {
        this.graphService = graphService;
        this.factSheetId = factSheetId != null && factSheetId > 0 ? factSheetId : null;
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
            scopedNode(nodeId).ifPresent(node -> {
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
        return scopedNode(entityId).isPresent();
    }

    @Override
    public boolean edgeExists(String sourceId, String targetId) {
        if (factSheetId == null) return graphService.edgeExists(sourceId, targetId);
        return edgesForNode(sourceId).stream().anyMatch(edge ->
                sourceId.equals(sourceId(edge)) && targetId.equals(targetId(edge)));
    }

    @Override
    public boolean edgeExistsOfType(String sourceId, String targetId, String edgeType) {
        List<GraphEdge> edges = edgesForNode(sourceId);
        return edges.stream().anyMatch(e ->
                e.getEdgeType() != null && e.getEdgeType().name().equals(edgeType) &&
                        sourceId.equals(sourceId(e)) && targetId.equals(targetId(e)));
    }

    @Override
    public Optional<String> getEntityType(String entityId) {
        return scopedNode(entityId)
                .map(node -> node.getNodeType().name());
    }

    @Override
    public Optional<String> getMetadata(String entityId, String metadataKey) {
        return scopedNode(entityId)
                .flatMap(node -> extractMetadataValue(node.getMetadataJson(), metadataKey));
    }

    @Override
    public Optional<Double> getEdgeWeight(String sourceId, String targetId) {
        List<GraphEdge> edges = edgesForNode(sourceId);
        return edges.stream()
                .filter(e -> sourceId.equals(sourceId(e)) && targetId.equals(targetId(e)))
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
        List<GraphEdge> edges = edgesForNode(entityId);
        Set<String> connected = new LinkedHashSet<>();
        for (GraphEdge edge : edges) {
            String sourceId = sourceId(edge);
            String targetId = targetId(edge);
            if (entityId.equals(sourceId) && targetId != null) {
                connected.add(targetId);
            }
            if (entityId.equals(targetId) && sourceId != null) {
                connected.add(sourceId);
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

    private Optional<GraphNode> scopedNode(String entityId) {
        return graphService.getNode(entityId).filter(node -> factSheetId == null
                || factSheetId.equals(node.getFactSheetId()));
    }

    private List<GraphEdge> edgesForNode(String entityId) {
        return edgeCache.computeIfAbsent(entityId, id -> {
            List<GraphEdge> edges = factSheetId == null
                    ? graphService.getEdgesForNode(id)
                    : graphService.getEdgesForNodeInFactSheet(id, factSheetId);
            return edges == null ? List.of() : List.copyOf(edges);
        });
    }

    private static String sourceId(GraphEdge edge) {
        return edge.getSourceNode() != null ? edge.getSourceNode().getNodeId() : edge.getSourceNodeId();
    }

    private static String targetId(GraphEdge edge) {
        return edge.getTargetNode() != null ? edge.getTargetNode().getNodeId() : edge.getTargetNodeId();
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
