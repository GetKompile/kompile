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
package ai.kompile.knowledgegraph.tool;

import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.repository.*;
import ai.kompile.knowledgegraph.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP Tool implementation for Knowledge Graph operations.
 * Provides LLM access to graph-based retrieval and exploration.
 */
@Component
public class KnowledgeGraphToolImpl {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeGraphToolImpl.class);

    private final KnowledgeGraphService graphService;
    private final SourceWeightingService weightingService;
    private final EntityMentionRepository entityMentionRepository;
    private final GraphNodeRepository nodeRepository;

    // ═══════════════════════════════════════════════════════════════════════════
    // INPUT RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    public record SearchByEntityInput(String entityName, Integer maxResults) {}
    public record GetRelatedDocumentsInput(String documentId, Integer maxResults, String relationshipType) {}
    public record GetSourceContextInput(String sourceId, Boolean includeChildren) {}
    public record FindConnectedNodesInput(String nodeId, Integer depth) {}
    public record SearchNodesInput(String query, String nodeType, Integer maxResults) {}
    public record GetEntitiesInDocumentInput(String documentId) {}
    public record FindDocumentsByTopicInput(String topic, Integer maxResults) {}
    public record GetGraphPathInput(String sourceNodeId, String targetNodeId) {}

    @Autowired
    public KnowledgeGraphToolImpl(
            KnowledgeGraphService graphService,
            SourceWeightingService weightingService,
            EntityMentionRepository entityMentionRepository,
            GraphNodeRepository nodeRepository) {
        this.graphService = graphService;
        this.weightingService = weightingService;
        this.entityMentionRepository = entityMentionRepository;
        this.nodeRepository = nodeRepository;
        logger.debug("KnowledgeGraphToolImpl initialized");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY-BASED SEARCH
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "graph_search_by_entity",
          description = "Search for documents that mention a specific entity (person, organization, concept, etc.). " +
                        "Returns documents containing the entity along with relevance information. " +
                        "Use this when you want to find all documents related to a specific person, company, or topic.")
    public Map<String, Object> searchByEntity(SearchByEntityInput input) {
        logger.info("Knowledge Graph: Searching for entity: {}", input.entityName());

        if (input.entityName() == null || input.entityName().isBlank()) {
            return Map.of("error", "Entity name cannot be empty", "results", List.of());
        }

        int maxResults = input.maxResults() != null && input.maxResults() > 0 ?
            Math.min(input.maxResults(), 20) : 10;

        try {
            // Find documents containing this entity
            List<GraphNode> nodes = entityMentionRepository.findNodesWithEntity(
                input.entityName().toLowerCase().trim()
            );

            List<Map<String, Object>> results = nodes.stream()
                .limit(maxResults)
                .<Map<String, Object>>map(node -> Map.of(
                    "documentId", node.getExternalId() != null ? node.getExternalId() : node.getNodeId(),
                    "title", node.getTitle() != null ? node.getTitle() : "Untitled",
                    "type", node.getNodeType().name(),
                    "description", node.getDescription() != null ? node.getDescription() : "",
                    "source", node.getSourceNode() != null ? node.getSourceNode().getTitle() : "Unknown"
                ))
                .collect(Collectors.toList());

            return Map.of(
                "entity", input.entityName(),
                "resultCount", results.size(),
                "results", results
            );

        } catch (Exception e) {
            logger.error("Error searching by entity: {}", e.getMessage(), e);
            return Map.of("error", "Search failed: " + e.getMessage(), "results", List.of());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RELATED DOCUMENTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "graph_get_related_documents",
          description = "Find documents related to a specific document through the knowledge graph. " +
                        "Relationships can be: 'entity' (shared entities), 'similarity' (embedding similarity), " +
                        "'hierarchical' (parent/child), or 'any' (all types). " +
                        "Use this to expand context with related information.")
    public Map<String, Object> getRelatedDocuments(GetRelatedDocumentsInput input) {
        logger.info("Knowledge Graph: Finding related documents for: {}", input.documentId());

        if (input.documentId() == null || input.documentId().isBlank()) {
            return Map.of("error", "Document ID cannot be empty", "results", List.of());
        }

        int maxResults = input.maxResults() != null && input.maxResults() > 0 ?
            Math.min(input.maxResults(), 20) : 10;

        try {
            List<GraphNode> relatedNodes = graphService.findRelatedNodes(input.documentId(), maxResults);

            // Filter by relationship type if specified
            String relType = input.relationshipType() != null ?
                input.relationshipType().toLowerCase() : "any";

            List<Map<String, Object>> results = relatedNodes.stream()
                .map(node -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("documentId", node.getExternalId() != null ? node.getExternalId() : node.getNodeId());
                    result.put("title", node.getTitle() != null ? node.getTitle() : "Untitled");
                    result.put("type", node.getNodeType().name());
                    result.put("description", truncate(node.getDescription(), 200));
                    return result;
                })
                .limit(maxResults)
                .collect(Collectors.toList());

            return Map.of(
                "sourceDocument", input.documentId(),
                "relationshipType", relType,
                "resultCount", results.size(),
                "results", results
            );

        } catch (Exception e) {
            logger.error("Error getting related documents: {}", e.getMessage(), e);
            return Map.of("error", "Failed to find related documents: " + e.getMessage(), "results", List.of());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SOURCE CONTEXT
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "graph_get_source_context",
          description = "Get detailed information about a document source and its contents. " +
                        "Returns source metadata, document count, and optionally a list of all documents. " +
                        "Use this to understand what information is available from a particular source.")
    public Map<String, Object> getSourceContext(GetSourceContextInput input) {
        logger.info("Knowledge Graph: Getting source context for: {}", input.sourceId());

        if (input.sourceId() == null || input.sourceId().isBlank()) {
            return Map.of("error", "Source ID cannot be empty");
        }

        try {
            Optional<GraphNode> sourceOpt = graphService.getNode(input.sourceId());
            if (sourceOpt.isEmpty()) {
                return Map.of("error", "Source not found: " + input.sourceId());
            }

            GraphNode source = sourceOpt.get();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sourceId", source.getNodeId());
            result.put("title", source.getTitle());
            result.put("type", source.getSourceType());
            result.put("description", source.getDescription());
            result.put("documentCount", source.getChildCount());

            // Get source weight
            SourceWeight weight = weightingService.getSourceWeight(input.sourceId(), null);
            result.put("weight", weight.getEffectiveWeight());

            if (Boolean.TRUE.equals(input.includeChildren())) {
                List<GraphNode> children = graphService.getChildren(input.sourceId());
                List<Map<String, String>> documents = children.stream()
                    .filter(c -> c.getNodeType() == NodeLevel.DOCUMENT)
                    .limit(50)
                    .map(c -> Map.of(
                        "id", c.getNodeId(),
                        "title", c.getTitle() != null ? c.getTitle() : "Untitled"
                    ))
                    .collect(Collectors.toList());
                result.put("documents", documents);
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting source context: {}", e.getMessage(), e);
            return Map.of("error", "Failed to get source context: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH EXPLORATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "graph_find_connected",
          description = "Explore the knowledge graph starting from a node, finding all connected nodes up to a certain depth. " +
                        "Use this to discover relationships and expand your understanding of how information connects.")
    public Map<String, Object> findConnectedNodes(FindConnectedNodesInput input) {
        logger.info("Knowledge Graph: Finding connected nodes for: {} depth: {}",
            input.nodeId(), input.depth());

        if (input.nodeId() == null || input.nodeId().isBlank()) {
            return Map.of("error", "Node ID cannot be empty", "nodes", List.of());
        }

        int depth = input.depth() != null && input.depth() > 0 ?
            Math.min(input.depth(), 3) : 2;

        try {
            List<GraphNode> connectedNodes = graphService.getConnectedNodes(input.nodeId(), depth);

            List<Map<String, Object>> nodes = connectedNodes.stream()
                .limit(50)
                .map(node -> Map.of(
                    "nodeId", (Object) node.getNodeId(),
                    "title", node.getTitle() != null ? node.getTitle() : "Untitled",
                    "type", node.getNodeType().name(),
                    "connections", node.getEdgeCount()
                ))
                .collect(Collectors.toList());

            return Map.of(
                "sourceNode", input.nodeId(),
                "depth", depth,
                "nodeCount", nodes.size(),
                "nodes", nodes
            );

        } catch (Exception e) {
            logger.error("Error finding connected nodes: {}", e.getMessage(), e);
            return Map.of("error", "Failed to find connected nodes: " + e.getMessage(), "nodes", List.of());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE SEARCH
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "graph_search_nodes",
          description = "Search for nodes in the knowledge graph by text query. " +
                        "Optionally filter by node type: 'source', 'document', 'snippet', or 'entity'. " +
                        "Returns matching nodes with their titles, types, and connection counts.")
    public Map<String, Object> searchNodes(SearchNodesInput input) {
        logger.info("Knowledge Graph: Searching nodes with query: {}", input.query());

        if (input.query() == null || input.query().isBlank()) {
            return Map.of("error", "Search query cannot be empty", "results", List.of());
        }

        int maxResults = input.maxResults() != null && input.maxResults() > 0 ?
            Math.min(input.maxResults(), 30) : 10;

        try {
            NodeLevel nodeType = null;
            if (input.nodeType() != null && !input.nodeType().isBlank()) {
                try {
                    nodeType = NodeLevel.valueOf(input.nodeType().toUpperCase());
                } catch (IllegalArgumentException e) {
                    // Ignore invalid type, search all
                }
            }

            List<GraphNode> nodes = graphService.searchNodes(input.query(), nodeType, maxResults);

            List<Map<String, Object>> results = nodes.stream()
                .map(node -> Map.of(
                    "nodeId", (Object) node.getNodeId(),
                    "title", node.getTitle() != null ? node.getTitle() : "Untitled",
                    "type", node.getNodeType().name(),
                    "description", truncate(node.getDescription(), 150),
                    "connections", node.getEdgeCount()
                ))
                .collect(Collectors.toList());

            return Map.of(
                "query", input.query(),
                "nodeType", input.nodeType() != null ? input.nodeType() : "any",
                "resultCount", results.size(),
                "results", results
            );

        } catch (Exception e) {
            logger.error("Error searching nodes: {}", e.getMessage(), e);
            return Map.of("error", "Search failed: " + e.getMessage(), "results", List.of());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "graph_get_document_entities",
          description = "Get all entities mentioned in a specific document. " +
                        "Returns entities with their types (person, organization, concept, etc.) and mention counts. " +
                        "Use this to understand the key topics and entities discussed in a document.")
    public Map<String, Object> getEntitiesInDocument(GetEntitiesInDocumentInput input) {
        logger.info("Knowledge Graph: Getting entities for document: {}", input.documentId());

        if (input.documentId() == null || input.documentId().isBlank()) {
            return Map.of("error", "Document ID cannot be empty", "entities", List.of());
        }

        try {
            Optional<GraphNode> nodeOpt = graphService.getNode(input.documentId());
            if (nodeOpt.isEmpty()) {
                // Try by external ID
                nodeOpt = nodeRepository.findByExternalIdAndNodeType(input.documentId(), NodeLevel.DOCUMENT);
            }

            if (nodeOpt.isEmpty()) {
                return Map.of("error", "Document not found: " + input.documentId(), "entities", List.of());
            }

            List<EntityMention> mentions = entityMentionRepository.findByNode(nodeOpt.get());

            List<Map<String, Object>> entities = mentions.stream()
                .sorted((a, b) -> Integer.compare(b.getMentionCount(), a.getMentionCount()))
                .map(mention -> Map.of(
                    "entity", (Object) mention.getEntityName(),
                    "type", mention.getEntityType(),
                    "mentions", mention.getMentionCount(),
                    "confidence", mention.getConfidence()
                ))
                .collect(Collectors.toList());

            return Map.of(
                "documentId", input.documentId(),
                "entityCount", entities.size(),
                "entities", entities
            );

        } catch (Exception e) {
            logger.error("Error getting document entities: {}", e.getMessage(), e);
            return Map.of("error", "Failed to get entities: " + e.getMessage(), "entities", List.of());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOPIC-BASED SEARCH
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "graph_find_by_topic",
          description = "Find documents related to a specific topic. " +
                        "Topics are categories that have been assigned to sources. " +
                        "Use this to find all information about a specific domain or subject area.")
    public Map<String, Object> findDocumentsByTopic(FindDocumentsByTopicInput input) {
        logger.info("Knowledge Graph: Finding documents for topic: {}", input.topic());

        if (input.topic() == null || input.topic().isBlank()) {
            return Map.of("error", "Topic cannot be empty", "results", List.of());
        }

        int maxResults = input.maxResults() != null && input.maxResults() > 0 ?
            Math.min(input.maxResults(), 30) : 10;

        try {
            List<String> sourceIds = weightingService.getSourcesForTopic(input.topic());

            if (sourceIds.isEmpty()) {
                return Map.of(
                    "topic", input.topic(),
                    "message", "No sources found for this topic. Available topics: " +
                        String.join(", ", weightingService.getTopics()),
                    "results", List.of()
                );
            }

            List<Map<String, Object>> results = new ArrayList<>();
            for (String sourceId : sourceIds) {
                if (results.size() >= maxResults) break;

                List<GraphNode> children = graphService.getChildren(sourceId);
                for (GraphNode child : children) {
                    if (results.size() >= maxResults) break;
                    if (child.getNodeType() == NodeLevel.DOCUMENT) {
                        results.add(Map.of(
                            "documentId", child.getNodeId(),
                            "title", child.getTitle() != null ? child.getTitle() : "Untitled",
                            "source", sourceId,
                            "description", truncate(child.getDescription(), 150)
                        ));
                    }
                }
            }

            return Map.of(
                "topic", input.topic(),
                "sourceCount", sourceIds.size(),
                "resultCount", results.size(),
                "results", results
            );

        } catch (Exception e) {
            logger.error("Error finding documents by topic: {}", e.getMessage(), e);
            return Map.of("error", "Search failed: " + e.getMessage(), "results", List.of());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH OVERVIEW
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "graph_get_overview",
          description = "Get an overview of the knowledge graph including statistics, available sources, and topics. " +
                        "Use this to understand what information is available in the knowledge base.")
    public Map<String, Object> getGraphOverview() {
        logger.info("Knowledge Graph: Getting overview");

        try {
            Map<String, Object> stats = graphService.getGraphStatistics();
            List<GraphNode> sources = graphService.getAllSources();
            List<String> topics = weightingService.getTopics();

            List<Map<String, Object>> sourceList = sources.stream()
                .limit(20)
                .map(s -> Map.of(
                    "id", (Object) s.getNodeId(),
                    "title", s.getTitle() != null ? s.getTitle() : "Untitled",
                    "type", s.getSourceType() != null ? s.getSourceType() : "unknown",
                    "documentCount", s.getChildCount()
                ))
                .collect(Collectors.toList());

            Map<String, Object> overview = new LinkedHashMap<>();
            overview.put("statistics", stats);
            overview.put("sources", sourceList);
            overview.put("topics", topics);
            overview.put("sourceCount", sources.size());
            overview.put("topicCount", topics.size());

            return overview;

        } catch (Exception e) {
            logger.error("Error getting graph overview: {}", e.getMessage(), e);
            return Map.of("error", "Failed to get overview: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }
}
