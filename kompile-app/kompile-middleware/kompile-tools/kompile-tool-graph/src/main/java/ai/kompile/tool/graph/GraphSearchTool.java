/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.tool.graph;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tools for multi-dimensional graph search.
 * Supports searching nodes and edges by type, text, metadata, weight,
 * edge type, and fact-sheet scope.
 */
@Component
@ConditionalOnBean(KnowledgeGraphService.class)
public class GraphSearchTool {

    private static final Logger log = LoggerFactory.getLogger(GraphSearchTool.class);

    private final KnowledgeGraphService graphService;
    private final UnifiedGraphBridge unifiedGraphBridge;

    // ═══════════════════════════════════════════════════════════════════════════
    // INPUT RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    public record SearchNodesInput(
            String query,
            String nodeType,
            Long factSheetId,
            Integer maxResults
    ) {}

    public record SearchEdgesInput(
            String query,
            String edgeType,
            Double minWeight,
            Long factSheetId,
            Integer maxResults
    ) {}

    public record GetNodeDetailInput(String nodeId, Long factSheetId) {
        public GetNodeDetailInput(String nodeId) {
            this(nodeId, null);
        }
    }

    public record GetEdgeDetailInput(String edgeId, Long factSheetId) {
        public GetEdgeDetailInput(String edgeId) {
            this(edgeId, null);
        }
    }

    public record FindEdgesBetweenInput(
            String sourceNodeId,
            String targetNodeId,
            Boolean bidirectional,
            Long factSheetId
    ) {
        public FindEdgesBetweenInput(String sourceNodeId, String targetNodeId, Boolean bidirectional) {
            this(sourceNodeId, targetNodeId, bidirectional, null);
        }
    }

    public record SearchByMetadataInput(
            String metadataKey,
            String metadataValue,
            String nodeType,
            Long factSheetId,
            Integer maxResults
    ) {}

    public GraphSearchTool(KnowledgeGraphService graphService) {
        this(graphService, null);
    }

    @Autowired
    public GraphSearchTool(KnowledgeGraphService graphService,
                           @org.springframework.lang.Nullable UnifiedGraphBridge unifiedGraphBridge) {
        this.graphService = graphService;
        this.unifiedGraphBridge = unifiedGraphBridge;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOOLS
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "graph_search",
          description = "Search the knowledge graph for nodes matching a text query. "
                  + "Filter by nodeType (SOURCE, DOCUMENT, SNIPPET, ENTITY, CUSTOM, TABLE) "
                  + "and optionally scope to a factSheetId. "
                  + "Returns nodes with their ID, title, type, description, and connection count.")
    public Map<String, Object> searchNodes(SearchNodesInput input) {
        log.info("Graph search: query='{}' type={} factSheet={}",
                input.query(), input.nodeType(), input.factSheetId());

        if (input.query() == null || input.query().isBlank()) {
            return Map.of("error", "Search query is required", "results", List.of());
        }

        int limit = clampLimit(input.maxResults(), 30);
        NodeLevel type = parseNodeLevel(input.nodeType());

        try {
            UnifiedGraph unified = unifiedGraph(input.factSheetId());
            if (unified != null) {
                return searchUnifiedNodes(input.query(), type, limit, unified);
            }

            List<GraphNode> nodes;
            if (input.factSheetId() != null) {
                nodes = graphService.searchNodesInFactSheetByType(
                        input.factSheetId(), input.query(), type, limit);
            } else {
                nodes = graphService.searchNodesGlobal(input.query(), type, limit);
            }

            return buildNodeResults(input.query(), nodes, type, sourceLabel(null));

        } catch (Exception e) {
            log.error("Graph search failed: {}", e.getMessage(), e);
            return Map.of("error", "Search failed: " + e.getMessage(), "results", List.of());
        }
    }

    @Tool(name = "graph_search_edges",
          description = "Search edges (relationships) in the knowledge graph by description text. "
                  + "Filter by edgeType (HIERARCHICAL, EMBEDDING_SIMILARITY, SHARED_ENTITY, "
                  + "USER_DEFINED, CITATION, TEMPORAL, CROSS_SOURCE, CONTAINS) "
                  + "and minimum weight. Scope to a factSheetId for project isolation. "
                  + "Returns edges with source/target node info, type, weight, and description.")
    public Map<String, Object> searchEdges(SearchEdgesInput input) {
        log.info("Graph edge search: query='{}' type={} minWeight={}",
                input.query(), input.edgeType(), input.minWeight());

        int limit = clampLimit(input.maxResults(), 30);
        EdgeType type = parseEdgeType(input.edgeType());

        try {
            UnifiedGraph unified = unifiedGraph(input.factSheetId());
            if (unified != null) {
                return searchUnifiedEdges(input.query(), type, input.minWeight(), limit, unified);
            }

            List<GraphEdge> edges;

            if (input.factSheetId() != null && type != null && input.minWeight() != null) {
                edges = graphService.getStrongEdgesByTypeInFactSheet(
                        input.factSheetId(), type, input.minWeight(), limit);
            } else if (type != null && input.minWeight() != null) {
                edges = graphService.getStrongEdgesByType(type, input.minWeight(), limit);
            } else if (input.query() != null && !input.query().isBlank()) {
                edges = graphService.searchEdges(input.query(), type, limit);
            } else if (input.factSheetId() != null && type != null) {
                edges = graphService.getEdgesByTypeInFactSheet(input.factSheetId(), type)
                        .stream().limit(limit).collect(Collectors.toList());
            } else if (type != null) {
                edges = graphService.searchEdges(null, type, limit);
            } else {
                return Map.of("error", "Provide at least a query, edgeType, or minWeight filter",
                        "results", List.of());
            }

            List<Map<String, Object>> results = edges.stream()
                    .map(this::edgeToMap)
                    .collect(Collectors.toList());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("source", sourceLabel(null));
            response.put("resultCount", results.size());
            if (input.query() != null) response.put("query", input.query());
            if (type != null) response.put("edgeType", type.name());
            response.put("results", results);
            return response;

        } catch (Exception e) {
            log.error("Graph edge search failed: {}", e.getMessage(), e);
            return Map.of("error", "Edge search failed: " + e.getMessage(), "results", List.of());
        }
    }

    @Tool(name = "graph_node_detail",
          description = "Get full details of a specific graph node by its ID. "
                  + "Returns all properties: title, type, description, metadata, "
                  + "connection count, parent info, source info, confidence, and timestamps.")
    public Map<String, Object> getNodeDetail(GetNodeDetailInput input) {
        if (input.nodeId() == null || input.nodeId().isBlank()) {
            return Map.of("error", "nodeId is required");
        }

        try {
            Optional<GraphNode> opt = graphService.getNode(input.nodeId());
            if (opt.isEmpty()) {
                UnifiedGraph unified = unifiedGraph(input.factSheetId());
                if (unified != null) {
                    return unified.entity(input.nodeId())
                            .map(entity -> unifiedNodeDetail(entity, unified))
                            .orElseGet(() -> Map.of("error", "Node not found: " + input.nodeId()));
                }
                return Map.of("error", "Node not found: " + input.nodeId());
            }

            GraphNode node = opt.get();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("nodeId", node.getNodeId());
            detail.put("title", node.getTitle());
            detail.put("type", node.getNodeType().name());
            detail.put("description", node.getDescription());
            detail.put("externalId", node.getExternalId());
            detail.put("connectionCount", node.getEdgeCount());
            detail.put("childCount", node.getChildCount());
            detail.put("confidence", node.getConfidence());
            detail.put("factSheetId", node.getFactSheetId());
            detail.put("namedGraphId", node.getNamedGraphId());

            if (node.getParentId() != null) {
                detail.put("parentNodeId", node.getParentId());
                detail.put("parentTitle", graphService.getNode(node.getParentId())
                        .map(GraphNode::getTitle).orElse(null));
            }
            if (node.getSourceNode() != null) {
                String sourceNodeId = node.getSourceNode().getNodeId();
                detail.put("sourceNodeId", sourceNodeId);
                detail.put("sourceTitle", graphService.getNode(sourceNodeId)
                        .map(GraphNode::getTitle).orElse(null));
            }
            if (node.getMetadataJson() != null) {
                detail.put("metadata", node.getMetadataJson());
            }
            detail.put("createdAt", node.getCreatedAt());
            detail.put("updatedAt", node.getUpdatedAt());

            // Include edges summary
            List<GraphEdge> edges = graphService.getEdgesForNode(input.nodeId());
            Map<String, Long> edgeTypeCounts = edges.stream()
                    .collect(Collectors.groupingBy(e -> e.getEdgeType().name(), Collectors.counting()));
            detail.put("edgesByType", edgeTypeCounts);

            return detail;

        } catch (Exception e) {
            log.error("Node detail failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed to get node detail: " + e.getMessage());
        }
    }

    @Tool(name = "graph_edge_detail",
          description = "Get full details of a specific graph edge by its ID. "
                  + "Returns source and target nodes, edge type, weight, description, "
                  + "bidirectionality, confidence, provenance, and timestamps.")
    public Map<String, Object> getEdgeDetail(GetEdgeDetailInput input) {
        if (input.edgeId() == null || input.edgeId().isBlank()) {
            return Map.of("error", "edgeId is required");
        }

        try {
            Optional<GraphEdge> opt = graphService.getEdge(input.edgeId());
            if (opt.isEmpty()) {
                UnifiedGraph unified = unifiedGraph(input.factSheetId());
                if (unified != null) {
                    return unified.relations().stream()
                            .filter(relation -> input.edgeId().equals(relation.id()))
                            .findFirst()
                            .map(relation -> unifiedEdgeDetailMap(relation, unified))
                            .orElseGet(() -> Map.of("error", "Edge not found: " + input.edgeId()));
                }
                return Map.of("error", "Edge not found: " + input.edgeId());
            }

            return edgeToDetailMap(opt.get());

        } catch (Exception e) {
            log.error("Edge detail failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed to get edge detail: " + e.getMessage());
        }
    }

    @Tool(name = "graph_edges_between",
          description = "Find all edges connecting two specific nodes. "
                  + "Set bidirectional=true to find edges in both directions. "
                  + "Returns the edges with full details.")
    public Map<String, Object> findEdgesBetween(FindEdgesBetweenInput input) {
        if (input.sourceNodeId() == null || input.targetNodeId() == null) {
            return Map.of("error", "Both sourceNodeId and targetNodeId are required");
        }

        try {
            boolean bidir = input.bidirectional() != null && input.bidirectional();
            UnifiedGraph unified = unifiedGraph(input.factSheetId());
            if (unified != null) {
                return findUnifiedEdgesBetween(input.sourceNodeId(), input.targetNodeId(), bidir, unified);
            }

            Optional<GraphEdge> edge;

            if (bidir) {
                edge = graphService.findEdgeBetweenNodesBidirectional(
                        input.sourceNodeId(), input.targetNodeId());
            } else {
                edge = Optional.ofNullable(
                        graphService.findEdgeBetweenNodes(input.sourceNodeId(), input.targetNodeId()));
            }

            if (edge.isEmpty()) {
                return Map.of(
                        "found", false,
                        "sourceNodeId", input.sourceNodeId(),
                        "targetNodeId", input.targetNodeId()
                );
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", true);
            result.put("edge", edgeToDetailMap(edge.get()));
            return result;

        } catch (Exception e) {
            log.error("Find edges between failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_search_metadata",
          description = "Search for nodes by their metadata key/value pairs. "
                  + "Looks in the node's metadataJson field for matching key-value combinations. "
                  + "Useful for finding nodes by custom attributes like 'author', 'department', 'status', etc.")
    public Map<String, Object> searchByMetadata(SearchByMetadataInput input) {
        if (input.metadataKey() == null || input.metadataKey().isBlank()) {
            return Map.of("error", "metadataKey is required", "results", List.of());
        }

        int limit = clampLimit(input.maxResults(), 30);

        try {
            // Build a search query that targets metadata JSON content
            String searchTerm = input.metadataValue() != null
                    ? input.metadataKey() + ".*" + input.metadataValue()
                    : input.metadataKey();

            // Use the general search and post-filter by metadata content
            List<GraphNode> candidates;
            NodeLevel type = parseNodeLevel(input.nodeType());
            UnifiedGraph unified = unifiedGraph(input.factSheetId());
            if (unified != null) {
                return searchUnifiedMetadata(input.metadataKey(), input.metadataValue(), type, limit, unified);
            }

            if (input.factSheetId() != null) {
                candidates = graphService.getNodesInFactSheet(input.factSheetId());
            } else if (type != null) {
                candidates = graphService.getNodesByType(type, 500);
            } else {
                candidates = graphService.getAllNodes(500);
            }

            List<GraphNode> matched = candidates.stream()
                    .filter(n -> n.getMetadataJson() != null)
                    .filter(n -> {
                        String meta = n.getMetadataJson().toLowerCase();
                        boolean keyMatch = meta.contains(input.metadataKey().toLowerCase());
                        if (!keyMatch) return false;
                        if (input.metadataValue() != null) {
                            return meta.contains(input.metadataValue().toLowerCase());
                        }
                        return true;
                    })
                    .filter(n -> type == null || n.getNodeType() == type)
                    .limit(limit)
                    .collect(Collectors.toList());

            return buildNodeResults("metadata:" + input.metadataKey(), matched, type, sourceLabel(null));

        } catch (Exception e) {
            log.error("Metadata search failed: {}", e.getMessage(), e);
            return Map.of("error", "Metadata search failed: " + e.getMessage(), "results", List.of());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> buildNodeResults(String query, List<GraphNode> nodes, NodeLevel type, String source) {
        List<Map<String, Object>> results = nodes.stream()
                .map(this::nodeToMap)
                .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source", source);
        response.put("query", query);
        if (type != null) response.put("nodeType", type.name());
        response.put("resultCount", results.size());
        response.put("results", results);
        return response;
    }

    private UnifiedGraph unifiedGraph(Long factSheetId) {
        if (unifiedGraphBridge == null || factSheetId == null) {
            return null;
        }
        try {
            return unifiedGraphBridge.export(factSheetId);
        } catch (RuntimeException ex) {
            log.warn("Falling back to live graph search; unified export failed for factSheet={}", factSheetId, ex);
            return null;
        }
    }

    private String sourceLabel(UnifiedGraph unified) {
        return unified != null ? "unified_graph" : "knowledge_graph_service";
    }

    private Map<String, Object> searchUnifiedNodes(String query, NodeLevel type, int limit, UnifiedGraph unified) {
        List<Map<String, Object>> results = unified.entities().stream()
                .filter(entity -> matchesNodeType(entity, type))
                .filter(entity -> matchesText(entitySearchText(entity), query))
                .limit(limit)
                .map(entity -> unifiedNodeToMap(entity, unified))
                .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source", sourceLabel(unified));
        response.put("query", query);
        if (type != null) response.put("nodeType", type.name());
        response.put("resultCount", results.size());
        response.put("results", results);
        return response;
    }

    private Map<String, Object> searchUnifiedEdges(String query, EdgeType type, Double minWeight,
                                                   int limit, UnifiedGraph unified) {
        if ((query == null || query.isBlank()) && type == null && minWeight == null) {
            return Map.of("error", "Provide at least a query, edgeType, or minWeight filter",
                    "results", List.of());
        }
        List<Map<String, Object>> results = unified.relations().stream()
                .filter(relation -> matchesEdgeType(relation, type))
                .filter(relation -> minWeight == null || relation.weight() >= minWeight)
                .filter(relation -> query == null || query.isBlank() || matchesText(relationSearchText(relation, unified), query))
                .limit(limit)
                .map(relation -> unifiedEdgeToMap(relation, unified))
                .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source", sourceLabel(unified));
        response.put("resultCount", results.size());
        if (query != null) response.put("query", query);
        if (type != null) response.put("edgeType", type.name());
        response.put("results", results);
        return response;
    }

    private Map<String, Object> searchUnifiedMetadata(String metadataKey, String metadataValue,
                                                      NodeLevel type, int limit, UnifiedGraph unified) {
        String key = metadataKey.toLowerCase(Locale.ROOT);
        String value = metadataValue == null ? null : metadataValue.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> results = unified.entities().stream()
                .filter(entity -> matchesNodeType(entity, type))
                .filter(entity -> unifiedMetadataMatches(entity, key, value))
                .limit(limit)
                .map(entity -> unifiedNodeToMap(entity, unified))
                .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source", sourceLabel(unified));
        response.put("query", "metadata:" + metadataKey);
        if (type != null) response.put("nodeType", type.name());
        response.put("resultCount", results.size());
        response.put("results", results);
        return response;
    }

    private Map<String, Object> findUnifiedEdgesBetween(String sourceNodeId, String targetNodeId,
                                                        boolean bidirectional, UnifiedGraph unified) {
        Optional<GraphRelation> relation = unified.relations().stream()
                .filter(r -> sourceNodeId.equals(r.sourceId()) && targetNodeId.equals(r.targetId())
                        || bidirectional && sourceNodeId.equals(r.targetId()) && targetNodeId.equals(r.sourceId()))
                .findFirst();
        if (relation.isEmpty()) {
            return Map.of(
                    "source", sourceLabel(unified),
                    "found", false,
                    "sourceNodeId", sourceNodeId,
                    "targetNodeId", targetNodeId
            );
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", sourceLabel(unified));
        result.put("found", true);
        result.put("edge", unifiedEdgeDetailMap(relation.get(), unified));
        return result;
    }

    private boolean matchesNodeType(GraphEntity entity, NodeLevel type) {
        if (type == null) return true;
        if (type.name().equalsIgnoreCase(entity.type())) return true;
        return entity.typeMemberships().stream().anyMatch(t -> type.name().equalsIgnoreCase(t));
    }

    private boolean matchesEdgeType(GraphRelation relation, EdgeType type) {
        return type == null || type.name().equalsIgnoreCase(relation.type());
    }

    private boolean matchesText(String text, String query) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private boolean unifiedMetadataMatches(GraphEntity entity, String key, String value) {
        for (Map.Entry<String, Object> entry : entity.attributes().entrySet()) {
            boolean keyMatch = entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).contains(key);
            String rawValue = entry.getValue() == null ? "" : String.valueOf(entry.getValue()).toLowerCase(Locale.ROOT);
            if (keyMatch && (value == null || rawValue.contains(value))) {
                return true;
            }
        }
        return false;
    }

    private String entitySearchText(GraphEntity entity) {
        return String.join(" ", entity.id(), entity.type(), entity.label(), String.valueOf(entity.tags()),
                String.valueOf(entity.attributes()));
    }

    private String relationSearchText(GraphRelation relation, UnifiedGraph unified) {
        String sourceLabel = unified.entity(relation.sourceId()).map(GraphEntity::label).orElse("");
        String targetLabel = unified.entity(relation.targetId()).map(GraphEntity::label).orElse("");
        return String.join(" ", relation.id(), relation.type(), relation.sourceId(), relation.targetId(),
                sourceLabel, targetLabel, String.valueOf(relation.tags()), String.valueOf(relation.attributes()));
    }

    private Map<String, Object> unifiedNodeToMap(GraphEntity entity, UnifiedGraph unified) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", entity.id());
        m.put("title", entity.label() == null || entity.label().isBlank() ? "Untitled" : entity.label());
        m.put("type", entity.type() == null || entity.type().isBlank() ? "UNKNOWN" : entity.type());
        m.put("description", truncate(String.valueOf(entity.attributes().getOrDefault("description", "")), 200));
        m.put("connections", incidentRelationCount(entity.id(), unified));
        return m;
    }

    private Map<String, Object> unifiedNodeDetail(GraphEntity entity, UnifiedGraph unified) {
        Map<String, Object> detail = unifiedNodeToMap(entity, unified);
        detail.put("source", sourceLabel(unified));
        detail.put("confidence", entity.confidence());
        detail.put("weight", entity.weight());
        detail.put("tags", entity.tags());
        detail.put("metadata", entity.attributes());
        Map<String, Long> edgeTypeCounts = unified.relations().stream()
                .filter(relation -> entity.id().equals(relation.sourceId()) || entity.id().equals(relation.targetId()))
                .collect(Collectors.groupingBy(GraphRelation::type, Collectors.counting()));
        detail.put("edgesByType", edgeTypeCounts);
        return detail;
    }

    private Map<String, Object> unifiedEdgeToMap(GraphRelation relation, UnifiedGraph unified) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("edgeId", relation.id());
        m.put("edgeType", relation.type());
        m.put("weight", relation.weight());
        m.put("description", truncate(String.valueOf(relation.attributes().getOrDefault("description", "")), 150));
        m.put("sourceNodeId", relation.sourceId());
        m.put("sourceTitle", unified.entity(relation.sourceId()).map(GraphEntity::label).orElse(null));
        m.put("targetNodeId", relation.targetId());
        m.put("targetTitle", unified.entity(relation.targetId()).map(GraphEntity::label).orElse(null));
        return m;
    }

    private Map<String, Object> unifiedEdgeDetailMap(GraphRelation relation, UnifiedGraph unified) {
        Map<String, Object> m = unifiedEdgeToMap(relation, unified);
        m.put("source", sourceLabel(unified));
        m.put("bidirectional", !relation.directed());
        m.put("confidence", relation.confidence());
        m.put("provenance", relation.attributes().get("provenance"));
        m.put("label", relation.attributes().get("label"));
        m.put("metadataJson", relation.attributes());
        m.put("createdAt", relation.timestamp());
        return m;
    }

    private long incidentRelationCount(String nodeId, UnifiedGraph unified) {
        return unified.relations().stream()
                .filter(relation -> nodeId.equals(relation.sourceId()) || nodeId.equals(relation.targetId()))
                .count();
    }

    private Map<String, Object> nodeToMap(GraphNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", node.getNodeId());
        m.put("title", node.getTitle() != null ? node.getTitle() : "Untitled");
        m.put("type", node.getNodeType().name());
        m.put("description", truncate(node.getDescription(), 200));
        m.put("connections", node.getEdgeCount());
        m.put("factSheetId", node.getFactSheetId());
        return m;
    }

    private Map<String, Object> edgeToMap(GraphEdge edge) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("edgeId", edge.getEdgeId());
        m.put("edgeType", edge.getEdgeType().name());
        m.put("weight", edge.getWeight());
        m.put("description", truncate(edge.getDescription(), 150));
        if (edge.getSourceNodeId() != null) {
            m.put("sourceNodeId", edge.getSourceNodeId());
            m.put("sourceTitle", endpointTitle(edge.getSourceNodeId(), edge.getSourceNode()));
        }
        if (edge.getTargetNodeId() != null) {
            m.put("targetNodeId", edge.getTargetNodeId());
            m.put("targetTitle", endpointTitle(edge.getTargetNodeId(), edge.getTargetNode()));
        }
        return m;
    }

    /**
     * Endpoint title for edge results. Store-loaded edges carry ids only —
     * {@code GraphEdge.getSourceNode()} synthesizes a hollow node with a null title — so fall back
     * to a store lookup. Bounded by the tool's result limit, so at most 2×limit lookups.
     */
    private String endpointTitle(String nodeId, GraphNode embedded) {
        if (embedded != null && !embedded.isHollow()
                && embedded.getTitle() != null && !embedded.getTitle().isBlank()) {
            return embedded.getTitle();
        }
        try {
            return graphService.getNode(nodeId).map(GraphNode::getTitle).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> edgeToDetailMap(GraphEdge edge) {
        Map<String, Object> m = edgeToMap(edge);
        m.put("bidirectional", edge.getBidirectional());
        m.put("confidence", edge.getConfidence());
        m.put("provenance", edge.getProvenance());
        m.put("label", edge.getLabel());
        m.put("factSheetId", edge.getFactSheetId());
        m.put("metadataJson", edge.getMetadataJson());
        m.put("createdAt", edge.getCreatedAt());
        return m;
    }

    static NodeLevel parseNodeLevel(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return NodeLevel.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static EdgeType parseEdgeType(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return EdgeType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static int clampLimit(Integer requested, int max) {
        if (requested == null || requested <= 0) return 10;
        return Math.min(requested, max);
    }

    static String truncate(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, max - 3) + "...";
    }
}
