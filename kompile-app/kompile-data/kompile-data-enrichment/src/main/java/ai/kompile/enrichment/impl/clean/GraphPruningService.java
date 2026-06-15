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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Removes low-confidence orphan entities and weak edges.
 */
@Service
public class GraphPruningService {
    private static final Logger log = LoggerFactory.getLogger(GraphPruningService.class);

    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;
    private final KnowledgeGraphService knowledgeGraphService;
    private final EnrichmentAuditService auditService;
    private final ObjectMapper objectMapper;

    public GraphPruningService(GraphNodeRepository nodeRepository,
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

    public int pruneGraph(Long factSheetId, String jobId, EnrichmentConfig config) {
        int pruned = 0;
        pruned += pruneOrphanEntities(factSheetId, jobId, config.getPruneConfidenceThreshold());
        pruned += pruneBlankOrphanEntities(factSheetId, jobId);
        pruned += pruneWeakEdges(factSheetId, jobId, config.getPruneEdgeWeightThreshold());
        return pruned;
    }

    private int pruneOrphanEntities(Long factSheetId, String jobId, double confidenceThreshold) {
        List<GraphNode> entities = nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY);
        int pruned = 0;
        for (GraphNode entity : entities) {
            double confidence = entity.getConfidence() != null ? entity.getConfidence() : 1.0;
            int edgeCount = entity.getEdgeCount() != null ? entity.getEdgeCount() : 0;
            if (confidence < confidenceThreshold && edgeCount == 0) {
                logAndDelete(factSheetId, jobId, entity, "PRUNE_ENTITY",
                        String.format("Removed orphan entity '%s' (confidence=%.2f, edges=%d)",
                                entity.getTitle(), confidence, edgeCount));
                pruned++;
            }
        }
        log.info("Pruned {} low-confidence orphan entities for factSheet {}", pruned, factSheetId);
        return pruned;
    }

    private int pruneBlankOrphanEntities(Long factSheetId, String jobId) {
        List<GraphNode> entities = nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY);
        int pruned = 0;
        for (GraphNode entity : entities) {
            int edgeCount = entity.getEdgeCount() != null ? entity.getEdgeCount() : 0;
            boolean blankTitle = entity.getTitle() == null || entity.getTitle().isBlank();
            boolean blankDesc = entity.getDescription() == null || entity.getDescription().isBlank();
            if (edgeCount == 0 && blankTitle && blankDesc) {
                logAndDelete(factSheetId, jobId, entity, "PRUNE_ENTITY",
                        String.format("Removed blank orphan entity '%s'", entity.getNodeId()));
                pruned++;
            }
        }
        log.info("Pruned {} blank orphan entities for factSheet {}", pruned, factSheetId);
        return pruned;
    }

    private int pruneWeakEdges(Long factSheetId, String jobId, double edgeWeightThreshold) {
        int pruned = 0;
        // Scope weak edge pruning to this factSheet only
        for (EdgeType edgeType : List.of(EdgeType.EMBEDDING_SIMILARITY, EdgeType.SHARED_ENTITY)) {
            List<GraphEdge> edges = edgeRepository.findByFactSheetIdAndEdgeType(factSheetId, edgeType);
            for (GraphEdge edge : edges) {
                if (edge.getWeight() != null && edge.getWeight() < edgeWeightThreshold) {
                    try {
                        edgeRepository.delete(edge);
                        pruned++;
                    } catch (Exception e) {
                        log.warn("Failed to prune edge {}: {}", edge.getEdgeId(), e.getMessage());
                    }
                }
            }
        }
        if (pruned > 0) {
            auditService.logAction(factSheetId, jobId, "CLEAN", "PRUNE_EDGE",
                    null, "GRAPH_EDGE", null, null,
                    String.format("Pruned %d weak edges below weight threshold %.2f", pruned, edgeWeightThreshold));
        }
        log.info("Pruned {} weak edges for factSheet {}", pruned, factSheetId);
        return pruned;
    }

    private void logAndDelete(Long factSheetId, String jobId, GraphNode node, String action, String description) {
        try {
            String beforeJson = objectMapper.writeValueAsString(Map.of(
                    "nodeId", node.getNodeId(),
                    "title", node.getTitle() != null ? node.getTitle() : "",
                    "nodeType", node.getNodeType().name(),
                    "confidence", node.getConfidence() != null ? node.getConfidence() : 0,
                    "edgeCount", node.getEdgeCount() != null ? node.getEdgeCount() : 0,
                    "metadataJson", node.getMetadataJson() != null ? node.getMetadataJson() : ""
            ));
            knowledgeGraphService.deleteNode(node.getNodeId());
            auditService.logAction(factSheetId, jobId, "CLEAN", action,
                    node.getNodeId(), "GRAPH_NODE", beforeJson, null, description);
        } catch (Exception e) {
            log.error("Failed to prune node {}: {}", node.getNodeId(), e.getMessage());
        }
    }
}
