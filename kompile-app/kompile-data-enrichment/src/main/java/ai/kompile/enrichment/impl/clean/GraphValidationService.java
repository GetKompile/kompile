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
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;
    private final KnowledgeGraphService knowledgeGraphService;
    private final EnrichmentAuditService auditService;
    private final ObjectMapper objectMapper;

    public GraphValidationService(GraphNodeRepository nodeRepository,
                                  GraphEdgeRepository edgeRepository,
                                  KnowledgeGraphService knowledgeGraphService,
                                  EnrichmentAuditService auditService,
                                  ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
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
        List<GraphNode> entities = nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY);
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
                entity.setTitle(newTitle);
                nodeRepository.save(entity);
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
        List<GraphNode> allNodes = nodeRepository.findByFactSheetId(factSheetId);
        Set<String> nodeIds = allNodes.stream().map(GraphNode::getNodeId).collect(Collectors.toSet());

        // Get all edges that touch nodes in this factSheet
        List<String> edgesToDelete = new ArrayList<>();
        for (GraphNode node : allNodes) {
            List<GraphEdge> edges = edgeRepository.findBySourceNodeIdOrTargetNodeId(node.getId());
            for (GraphEdge edge : edges) {
                if (!nodeIds.contains(edge.getSourceNode().getNodeId()) || !nodeIds.contains(edge.getTargetNode().getNodeId())) {
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
        List<GraphEdge> edges = edgeRepository.findByFactSheetId(factSheetId);
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
        if (factSheetId == null) {
            return;
        }
        List<GraphNode> nodes = nodeRepository.findByFactSheetId(factSheetId);
        if (nodes == null || nodes.isEmpty()) {
            return;
        }

        Map<String, GraphNode> nodesById = nodes.stream()
                .filter(node -> node.getNodeId() != null)
                .collect(Collectors.toMap(GraphNode::getNodeId, node -> node, (a, b) -> a));
        Map<String, Integer> counts = new HashMap<>();
        nodesById.keySet().forEach(nodeId -> counts.put(nodeId, 0));

        List<GraphEdge> edges = edgeRepository.findByFactSheetId(factSheetId);
        if (edges != null) {
            for (GraphEdge edge : edges) {
                if (edge == null || edge.getSourceNode() == null || edge.getTargetNode() == null) {
                    continue;
                }
                String sourceNodeId = edge.getSourceNode().getNodeId();
                String targetNodeId = edge.getTargetNode().getNodeId();
                if (!nodesById.containsKey(sourceNodeId) || !nodesById.containsKey(targetNodeId)) {
                    continue;
                }
                counts.merge(sourceNodeId, 1, Integer::sum);
                if (!sourceNodeId.equals(targetNodeId)) {
                    counts.merge(targetNodeId, 1, Integer::sum);
                }
            }
        }

        List<GraphNode> changed = new ArrayList<>();
        for (GraphNode node : nodesById.values()) {
            int recalculated = counts.getOrDefault(node.getNodeId(), 0);
            int current = node.getEdgeCount() != null ? node.getEdgeCount() : 0;
            if (current != recalculated) {
                node.setEdgeCount(recalculated);
                changed.add(node);
            }
        }
        if (!changed.isEmpty()) {
            nodeRepository.saveAll(changed);
            log.info("Recalculated edge counts for {} nodes in factSheet {}", changed.size(), factSheetId);
        }
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
