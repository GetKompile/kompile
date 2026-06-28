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

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.knowledgegraph.builder.dto.ExtractedGraphDTO;
import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bulk graph sync service that persists extracted entities and relationships
 * to the matrix/vector store in batched operations via the {@link KnowledgeGraphService} seam.
 *
 * <p>The matrix store's {@code createNode}/{@code createEdge} operations are idempotent,
 * so the dedup keys guard within-batch duplicates only; the store handles cross-batch idempotency.</p>
 */
@Service
@Slf4j
public class BulkGraphSyncService {

    @Autowired
    private KnowledgeGraphService knowledgeGraphService;
    @Autowired
    private ObjectMapper objectMapper;

    /** No-arg constructor for CGLIB proxy / GraalVM native image. */
    protected BulkGraphSyncService() {}

    /**
     * Bulk sync extracted entities and relationships to the matrix/vector store.
     *
     * @param entities      Extracted entities from LLM
     * @param relationships Extracted relationships from LLM
     * @param factSheetId   Optional fact sheet scope (may be null)
     * @return Map from entity ID (without "matrix:" prefix) to graph nodeId UUID
     */
    public Map<String, String> syncEntitiesAndRelationships(
            List<ExtractedGraphDTO.ExtractedEntity> entities,
            List<ExtractedGraphDTO.ExtractedRelationship> relationships,
            Long factSheetId) {

        if (entities == null || entities.isEmpty()) {
            return Collections.emptyMap();
        }

        long start = System.currentTimeMillis();

        // === PHASE 1: Node sync ===
        Map<String, String> entityIdToNodeId = syncNodes(entities, factSheetId);

        long nodeMs = System.currentTimeMillis() - start;

        // === PHASE 2: Edge sync ===
        int edgesCreated = 0;
        if (relationships != null && !relationships.isEmpty()) {
            edgesCreated = syncEdges(relationships, entityIdToNodeId, factSheetId);
        }

        long totalMs = System.currentTimeMillis() - start;
        log.info("Bulk sync complete: {} entities, {} edges created in {}ms (nodes={}ms)",
                entities.size(), edgesCreated, totalMs, nodeMs);

        return entityIdToNodeId;
    }

    private Map<String, String> syncNodes(
            List<ExtractedGraphDTO.ExtractedEntity> entities,
            Long factSheetId) {

        Map<String, String> entityIdToNodeId = new HashMap<>();

        for (ExtractedGraphDTO.ExtractedEntity entity : entities) {
            // Use "matrix:<id>" as the stable external ID (matches historical convention)
            String externalId = "matrix:" + entity.getId();

            Map<String, Object> meta = entity.getMetadata() != null
                    ? new HashMap<>(entity.getMetadata())
                    : new HashMap<>();
            meta.put("extraction_source", "matrix_graph");
            if (entity.getNodeLabel() != null && !entity.getNodeLabel().isBlank()) {
                meta.put("entity_type", GraphConstants.normalizeEntityType(entity.getNodeLabel()));
            }
            if (factSheetId != null) {
                meta.put("factSheetId", factSheetId);
            }

            String rawTitle = entity.getTitle();
            final String title = rawTitle == null ? entity.getId()
                    : (rawTitle.length() > 490 ? rawTitle.substring(0, 490) + "..." : rawTitle);

            try {
                // getNodeByExternalId + createNode are idempotent on the matrix store
                GraphNode node = knowledgeGraphService.getNodeByExternalId(externalId, NodeLevel.ENTITY, factSheetId)
                        .orElseGet(() -> knowledgeGraphService.createNode(
                                NodeLevel.ENTITY, externalId, title,
                                entity.getDescription(), meta, factSheetId));
                if (node != null) {
                    entityIdToNodeId.put(entity.getId(), node.getNodeId());
                }
            } catch (Exception e) {
                log.warn("BulkGraphSyncService: failed to sync entity {}: {}", entity.getId(), e.getMessage());
            }
        }

        log.debug("Bulk synced {} entity nodes", entityIdToNodeId.size());
        return entityIdToNodeId;
    }

    private int syncEdges(
            List<ExtractedGraphDTO.ExtractedRelationship> relationships,
            Map<String, String> entityIdToNodeId,
            Long factSheetId) {

        int edgesCreated = 0;
        // Guard against within-batch duplicates (store handles cross-batch idempotency)
        Set<String> reservedEdgeKeys = new HashSet<>();

        for (ExtractedGraphDTO.ExtractedRelationship rel : relationships) {
            String sourceNodeId = entityIdToNodeId.get(rel.getSource());
            String targetNodeId = entityIdToNodeId.get(rel.getTarget());
            if (sourceNodeId == null || targetNodeId == null) continue;

            String label = normalizeRelationshipLabel(rel.getRelationshipType());
            String forwardKey = edgeKey(sourceNodeId, targetNodeId, label, factSheetId);
            String reverseKey = edgeKey(targetNodeId, sourceNodeId, label, factSheetId);

            if (reservedEdgeKeys.contains(forwardKey) || reservedEdgeKeys.contains(reverseKey)) {
                continue;
            }

            try {
                // The matrix store createEdge is idempotent: same source+target+relationType
                // returns the existing edge without creating a duplicate.
                if (!knowledgeGraphService.edgeExists(sourceNodeId, targetNodeId,
                        EdgeType.USER_DEFINED, label, factSheetId)) {
                    String description = normalizeRelationshipDescription(rel.getDescription(), label);
                    double weight = rel.getWeight() != null ? rel.getWeight() : 1.0;
                    knowledgeGraphService.createEdge(sourceNodeId, targetNodeId,
                            EdgeType.USER_DEFINED, label, weight, description);
                    edgesCreated++;
                    reservedEdgeKeys.add(forwardKey);
                    reservedEdgeKeys.add(reverseKey);
                }
            } catch (Exception e) {
                log.debug("BulkGraphSyncService: failed to sync edge {} -> {}: {}",
                        rel.getSource(), rel.getTarget(), e.getMessage());
            }
        }

        return edgesCreated;
    }

    private String edgeKey(String sourceNodeId, String targetNodeId, String label, Long factSheetId) {
        String sheet = factSheetId != null ? factSheetId.toString() : "global";
        return sheet + "|" + sourceNodeId + "|" + targetNodeId + "|" + normalizeRelationshipLabel(label);
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
}
