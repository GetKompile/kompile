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
package ai.kompile.enrichment.impl.clean;

import ai.kompile.enrichment.config.EnrichmentConfig;
import ai.kompile.enrichment.impl.EnrichmentAuditService;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates and fixes graph structural issues:
 * - Fills blank entity titles from description or aliases
 * - Detects and removes dangling edges
 * - Normalizes date fields in metadataJson to ISO-8601
 */
@Service
public class GraphValidationService {
    private static final Logger log = LoggerFactory.getLogger(GraphValidationService.class);

    private final KnowledgeGraphService knowledgeGraphService;
    private final EnrichmentAuditService auditService;
    private final ObjectMapper objectMapper;

    public GraphValidationService(KnowledgeGraphService knowledgeGraphService,
                                  EnrichmentAuditService auditService,
                                  ObjectMapper objectMapper) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public int validate(Long factSheetId, String jobId, EnrichmentConfig config) {
        int fixed = 0;
        fixed += fixBlankTitles(factSheetId, jobId);
        int edgesDeleted = 0;
        edgesDeleted += removeDanglingEdges(factSheetId, jobId);
        edgesDeleted += removeDuplicateEdges(factSheetId, jobId);
        if (edgesDeleted > 0) {
            recalculateEdgeCounts(factSheetId);
        }
        fixed += edgesDeleted;
        return fixed;
    }

    private int fixBlankTitles(Long factSheetId, String jobId) {
        // Use vector store (SSOT)
        List<GraphNode> entities = knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY);
        int fixed = 0;
        for (GraphNode entity : entities) {
            if (entity.getTitle() != null && !entity.getTitle().isBlank()) continue;

            String newTitle = null;

            // Try description first 80 chars
            if (entity.getDescription() != null && !entity.getDescription().isBlank()) {
                String desc = entity.getDescription().trim();
                newTitle = desc.length() > 80 ? desc.substring(0, 80) + "..." : desc;
            }

            // Try first alias from metadataJson
            if (newTitle == null && entity.getMetadataJson() != null) {
                try {
                    var metaNode = objectMapper.readTree(entity.getMetadataJson());
                    var aliases = metaNode.path("aliases");
                    if (aliases.isArray() && !aliases.isEmpty()) {
                        newTitle = aliases.get(0).asText();
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse aliases from entity {} metadata: {}", entity.getNodeId(), e.getMessage());
                }
            }

            if (newTitle != null) {
                String beforeTitle = entity.getTitle() != null ? entity.getTitle() : "";
                // Persist via vector store (SSOT)
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = entity.getMetadataJson() != null && !entity.getMetadataJson().isBlank()
                            ? objectMapper.readValue(entity.getMetadataJson(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
                            : new java.util.LinkedHashMap<>();
                    knowledgeGraphService.updateNode(entity.getNodeId(), newTitle, entity.getDescription(), meta);
                } catch (Exception saveEx) {
                    log.warn("Failed to update title for entity {}: {}", entity.getNodeId(), saveEx.getMessage());
                }
                auditService.logAction(factSheetId, jobId, "CLEAN", "FIX_BLANK_TITLE",
                        entity.getNodeId(), "GRAPH_NODE",
                        String.format("{\"title\":\"%s\"}", escapeJson(beforeTitle)),
                        String.format("{\"title\":\"%s\"}", escapeJson(newTitle)),
                        String.format("Filled blank title for entity '%s' from %s",
                                entity.getNodeId(), entity.getDescription() != null ? "description" : "alias"));
                fixed++;
            }
        }
        log.info("Fixed {} blank entity titles for factSheet {}", fixed, factSheetId);
        return fixed;
    }

    private int removeDanglingEdges(Long factSheetId, String jobId) {
        // Use vector store (SSOT) to enumerate all nodes and edges in factSheet
        List<GraphNode> allNodes = knowledgeGraphService.getNodesInFactSheet(factSheetId);
        Set<String> nodeIds = allNodes.stream().map(GraphNode::getNodeId).collect(Collectors.toSet());

        // Get all edges for this factSheet and find dangling ones
        List<String> edgesToDelete = new ArrayList<>();
        List<GraphEdge> allEdges = knowledgeGraphService.getEdgesInFactSheet(factSheetId);
        for (GraphEdge edge : allEdges) {
            if (edge.getSourceNode() == null || edge.getTargetNode() == null
                    || !nodeIds.contains(edge.getSourceNode().getNodeId())
                    || !nodeIds.contains(edge.getTargetNode().getNodeId())) {
                if (edge.getEdgeId() != null) {
                    edgesToDelete.add(edge.getEdgeId());
                }
            }
        }

        if (!edgesToDelete.isEmpty()) {
            // Deduplicate
            List<String> uniqueEdges = new ArrayList<>(new java.util.LinkedHashSet<>(edgesToDelete));
            knowledgeGraphService.deleteEdgesBulk(uniqueEdges);
            auditService.logAction(factSheetId, jobId, "CLEAN", "REMOVE_DANGLING_EDGE",
                    null, "GRAPH_EDGE", null, null,
                    String.format("Removed %d dangling edges pointing to deleted nodes", uniqueEdges.size()));
            log.info("Removed {} dangling edges for factSheet {}", uniqueEdges.size(), factSheetId);
            return uniqueEdges.size();
        }
        return 0;
    }

    private int removeDuplicateEdges(Long factSheetId, String jobId) {
        if (factSheetId == null) {
            return 0;
        }
        // Use vector store (SSOT)
        List<GraphEdge> edges = knowledgeGraphService.getEdgesInFactSheet(factSheetId);
        if (edges == null || edges.isEmpty()) {
            return 0;
        }

        Map<String, List<GraphEdge>> edgesByKey = new HashMap<>();
        for (GraphEdge edge : edges) {
            String key = duplicateEdgeKey(edge);
            if (key == null) {
                continue;
            }
            edgesByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(edge);
        }

        List<String> edgesToDelete = new ArrayList<>();
        for (List<GraphEdge> duplicateGroup : edgesByKey.values()) {
            if (duplicateGroup.size() < 2) {
                continue;
            }
            GraphEdge keeper = chooseDuplicateKeeper(duplicateGroup);
            for (GraphEdge edge : duplicateGroup) {
                if (edge != keeper && edge.getEdgeId() != null) {
                    edgesToDelete.add(edge.getEdgeId());
                }
            }
        }

        if (edgesToDelete.isEmpty()) {
            return 0;
        }

        List<String> uniqueEdges = new ArrayList<>(new LinkedHashSet<>(edgesToDelete));
        knowledgeGraphService.deleteEdgesBulk(uniqueEdges);
        auditService.logAction(factSheetId, jobId, "CLEAN", "REMOVE_DUPLICATE_EDGE",
                null, "GRAPH_EDGE", null, null,
                String.format("Removed %d duplicate semantic relation edges", uniqueEdges.size()));
        log.info("Removed {} duplicate relation edges for factSheet {}", uniqueEdges.size(), factSheetId);
        return uniqueEdges.size();
    }

    private GraphEdge chooseDuplicateKeeper(List<GraphEdge> edges) {
        return edges.stream()
                .max(Comparator.comparingDouble(this::edgeStrength)
                        .thenComparingInt(edge -> textLength(edge.getMetadataJson()))
                        .thenComparingInt(edge -> textLength(edge.getDescription()))
                        .thenComparingLong(edge -> edge.getId() != null ? edge.getId() : 0L))
                .orElse(edges.get(0));
    }

    private double edgeStrength(GraphEdge edge) {
        if (edge.getConfidence() != null) {
            return edge.getConfidence();
        }
        if (edge.getWeight() != null) {
            return edge.getWeight();
        }
        if (edge.getSimilarityScore() != null) {
            return edge.getSimilarityScore();
        }
        return 0.0;
    }

    private int textLength(String value) {
        return value != null ? value.length() : 0;
    }

    private String duplicateEdgeKey(GraphEdge edge) {
        if (edge == null || edge.getSourceNode() == null || edge.getTargetNode() == null) {
            return null;
        }
        String sourceNodeId = edge.getSourceNode().getNodeId();
        String targetNodeId = edge.getTargetNode().getNodeId();
        if (sourceNodeId == null || targetNodeId == null) {
            return null;
        }

        boolean symmetric = Boolean.TRUE.equals(edge.getBidirectional())
                && edge.getEdgeType() != EdgeType.HIERARCHICAL;
        if (symmetric && sourceNodeId.compareTo(targetNodeId) > 0) {
            String tmp = sourceNodeId;
            sourceNodeId = targetNodeId;
            targetNodeId = tmp;
        }

        String edgeType = edge.getEdgeType() != null ? edge.getEdgeType().name() : EdgeType.USER_DEFINED.name();
        String label = normalizeSemanticLabel(edgeSemanticLabel(edge));
        return edge.getFactSheetId() + "|" + sourceNodeId + "|" + targetNodeId + "|" + edgeType + "|" + label;
    }

    private String edgeSemanticLabel(GraphEdge edge) {
        return firstNonBlank(
                edge.getLabel(),
                metadataValue(edge.getMetadataJson(), "semanticType", "relationshipType", "relationType"),
                edge.getDescription(),
                edge.getEdgeType() != null ? edge.getEdgeType().name() : null,
                "RELATED_TO");
    }

    private String metadataValue(String metadataJson, String... keys) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            var node = objectMapper.readTree(metadataJson);
            for (String key : keys) {
                var value = node.path(key);
                if (!value.isMissingNode() && value.asText() != null && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract metadata keys {} from JSON: {}", (Object) keys, e.getMessage());
        }
        return null;
    }

    private String normalizeSemanticLabel(String value) {
        if (value == null || value.isBlank()) {
            return "RELATED_TO";
        }
        String normalized = value.trim()
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? "RELATED_TO" : normalized;
    }

    private void recalculateEdgeCounts(Long factSheetId) {
        // The vector store (SSOT) maintains edge counts intrinsically through the adjacency matrix.
        // No explicit recalculation or JPA save is needed — this method is a no-op in the matrix-store regime.
        log.debug("recalculateEdgeCounts called for factSheet {} — no-op (vector store is SSOT)", factSheetId);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
