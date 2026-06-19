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
package ai.kompile.enrichment.impl.organize;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Analyzes entity type distribution and co-occurrence for taxonomy discovery.
 */
@Service
public class EntityTypeAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(EntityTypeAnalysisService.class);

    private final KnowledgeGraphService knowledgeGraphService;
    private final ObjectMapper objectMapper;

    public EntityTypeAnalysisService(KnowledgeGraphService knowledgeGraphService,
                                     ObjectMapper objectMapper) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.objectMapper = objectMapper;
    }

    /**
     * Count entity type distribution from all ENTITY nodes' metadataJson.
     */
    public Map<String, Long> getEntityTypeCounts(Long factSheetId) {
        List<GraphNode> entities = knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (GraphNode entity : entities) {
            String type = extractEntityType(entity);
            if (type != null && !type.isBlank()) {
                counts.merge(type, 1L, Long::sum);
            } else {
                counts.merge("UNKNOWN", 1L, Long::sum);
            }
        }
        return counts;
    }

    /**
     * Compute entity type co-occurrence: which types appear connected by edges.
     */
    public Map<String, Set<String>> getTypeCoOccurrence(Long factSheetId) {
        List<GraphNode> entities = knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY);
        Map<String, String> nodeIdToType = new HashMap<>();
        for (GraphNode entity : entities) {
            String type = extractEntityType(entity);
            if (type != null) {
                nodeIdToType.put(entity.getNodeId(), type);
            }
        }

        Map<String, Set<String>> coOccurrence = new LinkedHashMap<>();
        for (GraphNode entity : entities) {
            // Use vector store (SSOT): look up edges by string nodeId
            List<GraphEdge> edges = knowledgeGraphService.getEdgesForNode(entity.getNodeId());
            String sourceType = nodeIdToType.get(entity.getNodeId());
            if (sourceType == null) continue;

            for (GraphEdge edge : edges) {
                String neighborId = edge.getSourceNode().getNodeId().equals(entity.getNodeId())
                        ? edge.getTargetNode().getNodeId() : edge.getSourceNode().getNodeId();
                String neighborType = nodeIdToType.get(neighborId);
                if (neighborType != null && !neighborType.equals(sourceType)) {
                    coOccurrence.computeIfAbsent(sourceType, k -> new LinkedHashSet<>()).add(neighborType);
                    coOccurrence.computeIfAbsent(neighborType, k -> new LinkedHashSet<>()).add(sourceType);
                }
            }
        }
        return coOccurrence;
    }

    private String extractEntityType(GraphNode node) {
        if (node.getMetadataJson() == null) return null;
        try {
            JsonNode meta = objectMapper.readTree(node.getMetadataJson());
            JsonNode typeNode = meta.path("entity_type");
            return typeNode.isMissingNode() || typeNode.isNull() ? null : typeNode.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
