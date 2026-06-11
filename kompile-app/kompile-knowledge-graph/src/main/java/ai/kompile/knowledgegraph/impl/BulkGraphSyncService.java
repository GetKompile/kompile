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
package ai.kompile.knowledgegraph.impl;

import ai.kompile.knowledgegraph.builder.dto.ExtractedGraphDTO;
import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Bulk graph sync service that persists extracted entities and relationships
 * to the JPA knowledge graph in batched operations within a single transaction.
 * <p>
 * This avoids the N+1 query problem that occurs when persisting entities
 * one at a time via KnowledgeGraphService, which causes H2 cache thrashing
 * when the graph contains tens of thousands of nodes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BulkGraphSyncService {

    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;
    private final ObjectMapper objectMapper;

    /**
     * Bulk sync extracted entities and relationships to JPA in a single transaction.
     *
     * @param entities      Extracted entities from LLM
     * @param relationships Extracted relationships from LLM
     * @param factSheetId   Optional fact sheet scope (may be null)
     * @return Map from entity ID (without "matrix:" prefix) to JPA node UUID
     */
    @Transactional
    public Map<String, String> syncEntitiesAndRelationships(
            List<ExtractedGraphDTO.ExtractedEntity> entities,
            List<ExtractedGraphDTO.ExtractedRelationship> relationships,
            Long factSheetId) {

        if (entities == null || entities.isEmpty()) {
            return Collections.emptyMap();
        }

        long start = System.currentTimeMillis();

        // === PHASE 1: Bulk node sync ===
        Map<String, String> entityIdToNodeId = syncNodes(entities, factSheetId);

        long nodeMs = System.currentTimeMillis() - start;

        // === PHASE 2: Bulk edge sync ===
        int edgesCreated = 0;
        if (relationships != null && !relationships.isEmpty()) {
            edgesCreated = syncEdges(relationships, entityIdToNodeId, factSheetId);
        }

        long totalMs = System.currentTimeMillis() - start;
        log.info("Bulk sync complete: {} entities ({} new), {} edges created in {}ms (nodes={}ms)",
                entities.size(), entityIdToNodeId.size(), edgesCreated, totalMs, nodeMs);

        return entityIdToNodeId;
    }

    private Map<String, String> syncNodes(
            List<ExtractedGraphDTO.ExtractedEntity> entities,
            Long factSheetId) {

        // Collect all external IDs
        Set<String> externalIds = entities.stream()
                .map(e -> "matrix:" + e.getId())
                .collect(Collectors.toSet());

        // Single bulk lookup for existing nodes, scoped by fact sheet when available.
        List<GraphNode> existingNodes = factSheetId != null
                ? nodeRepository.findByExternalIdInAndNodeTypeAndFactSheetId(externalIds, NodeLevel.ENTITY, factSheetId)
                : nodeRepository.findByExternalIdInAndNodeType(externalIds, NodeLevel.ENTITY);
        Map<String, GraphNode> existingByExtId = existingNodes.stream()
                .collect(Collectors.toMap(GraphNode::getExternalId, n -> n, (a, b) -> a));

        Map<String, String> entityIdToNodeId = new HashMap<>();
        List<GraphNode> nodesToSave = new ArrayList<>();
        // Track which entity ID maps to which new node (by list index)
        List<String> newNodeEntityIds = new ArrayList<>();

        for (ExtractedGraphDTO.ExtractedEntity entity : entities) {
            String externalId = "matrix:" + entity.getId();
            GraphNode existing = existingByExtId.get(externalId);

            if (existing != null) {
                entityIdToNodeId.put(entity.getId(), existing.getNodeId());
            } else {
                Map<String, Object> meta = entity.getMetadata() != null
                        ? new HashMap<>(entity.getMetadata())
                        : new HashMap<>();
                meta.put("extraction_source", "matrix_graph");
                if (entity.getNodeLabel() != null && !entity.getNodeLabel().isBlank()) {
                    meta.put("entity_type", entity.getNodeLabel());
                }
                if (factSheetId != null) meta.put("factSheetId", factSheetId);

                String metaJson;
                try {
                    metaJson = objectMapper.writeValueAsString(meta);
                } catch (Exception e) {
                    metaJson = "{}";
                }

                String title = entity.getTitle();
                if (title != null && title.length() > 490) {
                    title = title.substring(0, 490) + "...";
                }

                GraphNode newNode = GraphNode.builder()
                        .externalId(externalId)
                        .nodeType(NodeLevel.ENTITY)
                        .title(title != null ? title : entity.getId())
                        .description(entity.getDescription())
                        .metadataJson(metaJson)
                        .factSheetId(factSheetId)
                        .build();

                nodesToSave.add(newNode);
                newNodeEntityIds.add(entity.getId());
            }
        }

        // Batch save all new nodes at once
        if (!nodesToSave.isEmpty()) {
            List<GraphNode> saved = nodeRepository.saveAll(nodesToSave);
            for (int i = 0; i < saved.size(); i++) {
                entityIdToNodeId.put(newNodeEntityIds.get(i), saved.get(i).getNodeId());
            }
            log.debug("Bulk created {} new entity nodes", saved.size());
        }

        return entityIdToNodeId;
    }

    private int syncEdges(
            List<ExtractedGraphDTO.ExtractedRelationship> relationships,
            Map<String, String> entityIdToNodeId,
            Long factSheetId) {

        // Collect all node IDs that will be involved in edges
        Set<String> allNodeIds = new HashSet<>(entityIdToNodeId.values());

        // Bulk load existing semantic edges for these nodes to avoid N individual existence checks.
        Map<String, GraphEdge> existingEdgesByKey = loadExistingEdgeKeyMap(allNodeIds);
        Set<String> reservedEdgeKeys = new HashSet<>(existingEdgesByKey.keySet());

        // Bulk load all nodes we need for edge creation (single query instead of N)
        Map<String, GraphNode> nodesByNodeId = new HashMap<>();
        if (!allNodeIds.isEmpty()) {
            for (List<String> chunk : partition(new ArrayList<>(allNodeIds), 500)) {
                List<GraphNode> nodes = nodeRepository.findByNodeIdIn(chunk);
                for (GraphNode n : nodes) {
                    nodesByNodeId.put(n.getNodeId(), n);
                }
            }
        }

        List<GraphEdge> edgesToSave = new ArrayList<>();
        List<GraphEdge> existingEdgesToUpdate = new ArrayList<>();
        Set<GraphNode> nodesNeedingEdgeCountUpdate = new HashSet<>();

        for (ExtractedGraphDTO.ExtractedRelationship rel : relationships) {
            String sourceNodeId = entityIdToNodeId.get(rel.getSource());
            String targetNodeId = entityIdToNodeId.get(rel.getTarget());
            if (sourceNodeId == null || targetNodeId == null) continue;

            String label = normalizeRelationshipLabel(rel.getRelationshipType());
            String forwardKey = edgeKey(sourceNodeId, targetNodeId, EdgeType.USER_DEFINED, label, factSheetId);
            String reverseKey = edgeKey(targetNodeId, sourceNodeId, EdgeType.USER_DEFINED, label, factSheetId);
            String description = normalizeRelationshipDescription(rel.getDescription(), label);
            String metadataJson = relationshipMetadataJson(rel, label, description, factSheetId);

            GraphEdge existing = existingEdgesByKey.get(forwardKey);
            if (existing == null) {
                existing = existingEdgesByKey.get(reverseKey);
            }
            if (existing != null) {
                boolean changed = false;
                if (existing.getLabel() == null || existing.getLabel().isBlank()) {
                    existing.setLabel(label);
                    changed = true;
                }
                if (existing.getDescription() == null || existing.getDescription().isBlank()
                        || existing.getDescription().equals(existing.getLabel())) {
                    existing.setDescription(description);
                    changed = true;
                }
                if (existing.getMetadataJson() == null || existing.getMetadataJson().isBlank()) {
                    existing.setMetadataJson(metadataJson);
                    changed = true;
                }
                if (existing.getFactSheetId() == null && factSheetId != null) {
                    existing.setFactSheetId(factSheetId);
                    changed = true;
                }
                if (changed) {
                    existingEdgesToUpdate.add(existing);
                }
                continue;
            }
            if (reservedEdgeKeys.contains(forwardKey) || reservedEdgeKeys.contains(reverseKey)) {
                continue;
            }

            // Mark as existing to prevent duplicates within this batch.
            reservedEdgeKeys.add(forwardKey);
            reservedEdgeKeys.add(reverseKey);

            GraphNode source = nodesByNodeId.get(sourceNodeId);
            GraphNode target = nodesByNodeId.get(targetNodeId);
            if (source == null || target == null) continue;

            double weight = rel.getWeight() != null ? rel.getWeight() : 1.0;

            GraphEdge edge = GraphEdge.builder()
                    .sourceNode(source)
                    .targetNode(target)
                    .edgeType(EdgeType.USER_DEFINED)
                    .weight(weight)
                    .description(description)
                    .label(label)
                    .metadataJson(metadataJson)
                    .bidirectional(true)
                    .factSheetId(factSheetId)
                    .build();

            edgesToSave.add(edge);

            source.incrementEdgeCount();
            target.incrementEdgeCount();
            nodesNeedingEdgeCountUpdate.add(source);
            nodesNeedingEdgeCountUpdate.add(target);
        }

        if (!existingEdgesToUpdate.isEmpty()) {
            edgeRepository.saveAll(existingEdgesToUpdate);
            log.debug("Bulk updated semantic metadata on {} existing edges", existingEdgesToUpdate.size());
        }

        // Batch save all new edges
        if (!edgesToSave.isEmpty()) {
            edgeRepository.saveAll(edgesToSave);
            // Batch update edge counts on affected nodes
            nodeRepository.saveAll(nodesNeedingEdgeCountUpdate);
            log.debug("Bulk created {} new edges, updated {} node edge counts",
                    edgesToSave.size(), nodesNeedingEdgeCountUpdate.size());
        }

        return edgesToSave.size();
    }

    /**
     * Load all existing semantic edge keys for a set of node IDs into a fast lookup map.
     * Each key includes source, target, edge type, label, and fact sheet so different
     * semantic relationships between the same entity pair can coexist.
     */
    private Map<String, GraphEdge> loadExistingEdgeKeyMap(Set<String> nodeIds) {
        Map<String, GraphEdge> edges = new HashMap<>();
        if (nodeIds.isEmpty()) return edges;

        List<String> nodeIdList = new ArrayList<>(nodeIds);
        for (List<String> chunk : partition(nodeIdList, 500)) {
            try {
                List<GraphEdge> sourceEdges = edgeRepository.findBySourceNodeNodeIdIn(chunk);
                for (GraphEdge e : sourceEdges) {
                    String sId = e.getSourceNode().getNodeId();
                    String tId = e.getTargetNode().getNodeId();
                    String label = existingEdgeSemanticLabel(e);
                    edges.putIfAbsent(edgeKey(sId, tId, e.getEdgeType(), label, e.getFactSheetId()), e);
                    edges.putIfAbsent(edgeKey(tId, sId, e.getEdgeType(), label, e.getFactSheetId()), e);
                }
            } catch (Exception e) {
                log.warn("Failed to bulk-load source edges: {}", e.getMessage());
            }

            try {
                List<GraphEdge> targetEdges = edgeRepository.findByTargetNodeNodeIdIn(chunk);
                for (GraphEdge e : targetEdges) {
                    String sId = e.getSourceNode().getNodeId();
                    String tId = e.getTargetNode().getNodeId();
                    String label = existingEdgeSemanticLabel(e);
                    edges.putIfAbsent(edgeKey(sId, tId, e.getEdgeType(), label, e.getFactSheetId()), e);
                    edges.putIfAbsent(edgeKey(tId, sId, e.getEdgeType(), label, e.getFactSheetId()), e);
                }
            } catch (Exception e) {
                log.warn("Failed to bulk-load target edges: {}", e.getMessage());
            }
        }

        return edges;
    }

    private String edgeKey(String sourceNodeId, String targetNodeId, EdgeType edgeType,
                           String label, Long factSheetId) {
        String type = edgeType != null ? edgeType.name() : EdgeType.USER_DEFINED.name();
        String sheet = factSheetId != null ? factSheetId.toString() : "global";
        return sheet + "|" + sourceNodeId + "|" + targetNodeId + "|" + type + "|" + normalizeRelationshipLabel(label);
    }

    private String normalizeRelationshipLabel(String label) {
        if (label == null || label.isBlank()) {
            return "RELATED_TO";
        }
        String normalized = label.trim()
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return normalized.isBlank() ? "RELATED_TO" : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeRelationshipDescription(String description, String label) {
        if (description != null && !description.isBlank()) {
            return description.trim();
        }
        return label;
    }

    private String existingEdgeSemanticLabel(GraphEdge edge) {
        if (edge == null) {
            return "RELATED_TO";
        }
        String label = normalizeRelationshipLabel(edge.getLabel());
        String edgeTypeName = edge.getEdgeType() != null ? edge.getEdgeType().name() : null;
        if (!"RELATED_TO".equals(label) && !Objects.equals(label, edgeTypeName)) {
            return label;
        }
        String inferred = inferRelationshipLabel(edge.getDescription());
        return inferred != null ? inferred : label;
    }

    private String inferRelationshipLabel(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String upper = description.trim().toUpperCase(Locale.ROOT);
        if (upper.contains(" IS HEADER OF CELL") || upper.contains(" HEADER OF ")) {
            return "HEADER_OF";
        }
        if (upper.contains(" DEPENDS ON ") || upper.startsWith("DEPENDS ON ")) {
            return "DEPENDS_ON";
        }
        if (upper.contains(" CREATED ON ") || upper.contains(" PUBLISHED ON ")) {
            return "PUBLISHED_ON";
        }
        if (upper.matches("[A-Z][A-Z0-9_ ]{1,80}")) {
            return normalizeRelationshipLabel(upper);
        }
        return null;
    }

    private String relationshipMetadataJson(ExtractedGraphDTO.ExtractedRelationship rel,
                                            String label,
                                            String description,
                                            Long factSheetId) {
        Map<String, Object> metadata = rel.getMetadata() != null
                ? new LinkedHashMap<>(rel.getMetadata())
                : new LinkedHashMap<>();
        metadata.putIfAbsent("semanticType", label);
        metadata.putIfAbsent("relationshipType", label);
        metadata.putIfAbsent("semanticContext", description);
        metadata.putIfAbsent("sourceEntityId", rel.getSource());
        metadata.putIfAbsent("targetEntityId", rel.getTarget());
        metadata.putIfAbsent("extractionSource", "matrix_graph");
        if (rel.getConfidence() != null) {
            metadata.putIfAbsent("confidence", rel.getConfidence());
        }
        if (rel.getWeight() != null) {
            metadata.putIfAbsent("weight", rel.getWeight());
        }
        if (factSheetId != null) {
            metadata.putIfAbsent("factSheetId", factSheetId);
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{\"semanticType\":\"" + label + "\"}";
        }
    }

    /** Partition a list into chunks of the given size. */
    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
