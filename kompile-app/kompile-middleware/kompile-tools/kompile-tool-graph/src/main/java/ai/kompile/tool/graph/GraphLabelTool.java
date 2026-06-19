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

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.type.TypeReference;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tools for custom labeling and tagging of graph nodes and edges.
 * Labels are stored in the metadataJson field under a "labels" key,
 * allowing arbitrary user-defined categorization.
 */
@Component
@ConditionalOnBean(KnowledgeGraphService.class)
public class GraphLabelTool {

    private static final Logger log = LoggerFactory.getLogger(GraphLabelTool.class);
    private static final String LABELS_KEY = "labels";

    private final KnowledgeGraphService graphService;
    private final ObjectMapper objectMapper;

    // ═══════════════════════════════════════════════════════════════════════════
    // INPUT RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    public record AddNodeLabelsInput(
            String nodeId,
            List<String> labels
    ) {}

    public record RemoveNodeLabelsInput(
            String nodeId,
            List<String> labels
    ) {}

    public record GetNodeLabelsInput(String nodeId) {}

    public record FindByLabelInput(
            String label,
            String nodeType,
            Long factSheetId,
            Integer maxResults
    ) {}

    public record AddEdgeLabelsInput(
            String edgeId,
            List<String> labels
    ) {}

    public record RemoveEdgeLabelsInput(
            String edgeId,
            List<String> labels
    ) {}

    public record BulkLabelNodesInput(
            List<String> nodeIds,
            List<String> labels
    ) {}

    public record ListAllLabelsInput(Long factSheetId) {}

    @Autowired
    public GraphLabelTool(KnowledgeGraphService graphService,
                          @Autowired(required = false) ObjectMapper objectMapper) {
        this.graphService = graphService;
        this.objectMapper = objectMapper != null ? objectMapper : JsonUtils.standardMapper();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOOLS
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "graph_add_node_labels",
          description = "Add custom labels/tags to a graph node. "
                  + "Labels are stored as metadata and can be used for filtering and categorization. "
                  + "Examples: 'reviewed', 'priority-high', 'department:engineering'. "
                  + "Existing labels are preserved; duplicates are ignored.")
    public Map<String, Object> addNodeLabels(AddNodeLabelsInput input) {
        if (input.nodeId() == null || input.nodeId().isBlank()) {
            return Map.of("error", "nodeId is required");
        }
        if (input.labels() == null || input.labels().isEmpty()) {
            return Map.of("error", "At least one label is required");
        }

        try {
            Optional<GraphNode> opt = graphService.getNode(input.nodeId());
            if (opt.isEmpty()) {
                return Map.of("error", "Node not found: " + input.nodeId());
            }

            GraphNode node = opt.get();
            Map<String, Object> metadata = parseMetadata(node.getMetadataJson());
            Set<String> existing = getLabelsFromMetadata(metadata);
            existing.addAll(input.labels());
            metadata.put(LABELS_KEY, new ArrayList<>(existing));
            node.setMetadataJson(objectMapper.writeValueAsString(metadata));
            graphService.saveNode(node);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodeId", input.nodeId());
            result.put("labels", new ArrayList<>(existing));
            result.put("added", input.labels());
            return result;

        } catch (Exception e) {
            log.error("Add node labels failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_remove_node_labels",
          description = "Remove custom labels/tags from a graph node. "
                  + "Specify which labels to remove. Non-existent labels are silently ignored.")
    public Map<String, Object> removeNodeLabels(RemoveNodeLabelsInput input) {
        if (input.nodeId() == null || input.nodeId().isBlank()) {
            return Map.of("error", "nodeId is required");
        }
        if (input.labels() == null || input.labels().isEmpty()) {
            return Map.of("error", "At least one label is required");
        }

        try {
            Optional<GraphNode> opt = graphService.getNode(input.nodeId());
            if (opt.isEmpty()) {
                return Map.of("error", "Node not found: " + input.nodeId());
            }

            GraphNode node = opt.get();
            Map<String, Object> metadata = parseMetadata(node.getMetadataJson());
            Set<String> existing = getLabelsFromMetadata(metadata);
            existing.removeAll(input.labels());
            metadata.put(LABELS_KEY, new ArrayList<>(existing));
            node.setMetadataJson(objectMapper.writeValueAsString(metadata));
            graphService.saveNode(node);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodeId", input.nodeId());
            result.put("labels", new ArrayList<>(existing));
            result.put("removed", input.labels());
            return result;

        } catch (Exception e) {
            log.error("Remove node labels failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_get_node_labels",
          description = "Get all custom labels/tags assigned to a specific node.")
    public Map<String, Object> getNodeLabels(GetNodeLabelsInput input) {
        if (input.nodeId() == null || input.nodeId().isBlank()) {
            return Map.of("error", "nodeId is required");
        }

        try {
            Optional<GraphNode> opt = graphService.getNode(input.nodeId());
            if (opt.isEmpty()) {
                return Map.of("error", "Node not found: " + input.nodeId());
            }

            GraphNode node = opt.get();
            Map<String, Object> metadata = parseMetadata(node.getMetadataJson());
            Set<String> labels = getLabelsFromMetadata(metadata);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodeId", input.nodeId());
            result.put("title", node.getTitle());
            result.put("labels", new ArrayList<>(labels));
            return result;

        } catch (Exception e) {
            log.error("Get node labels failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_find_by_label",
          description = "Find all nodes that have a specific label/tag. "
                  + "Optionally filter by nodeType and factSheetId. "
                  + "Use this to find all nodes tagged as 'reviewed', 'priority-high', etc.")
    public Map<String, Object> findByLabel(FindByLabelInput input) {
        if (input.label() == null || input.label().isBlank()) {
            return Map.of("error", "label is required", "results", List.of());
        }

        int limit = GraphSearchTool.clampLimit(input.maxResults(), 50);

        try {
            List<GraphNode> candidates;
            if (input.factSheetId() != null) {
                candidates = graphService.getNodesInFactSheet(input.factSheetId());
            } else {
                candidates = graphService.getAllNodes(5000);
            }

            var nodeType = GraphSearchTool.parseNodeLevel(input.nodeType());
            List<Map<String, Object>> results = candidates.stream()
                    .filter(n -> nodeType == null || n.getNodeType() == nodeType)
                    .filter(n -> {
                        Map<String, Object> meta = parseMetadata(n.getMetadataJson());
                        Set<String> labels = getLabelsFromMetadata(meta);
                        return labels.contains(input.label());
                    })
                    .limit(limit)
                    .map(n -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("nodeId", n.getNodeId());
                        m.put("title", n.getTitle() != null ? n.getTitle() : "Untitled");
                        m.put("type", n.getNodeType().name());
                        m.put("labels", new ArrayList<>(getLabelsFromMetadata(
                                parseMetadata(n.getMetadataJson()))));
                        return m;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("label", input.label());
            response.put("resultCount", results.size());
            response.put("results", results);
            return response;

        } catch (Exception e) {
            log.error("Find by label failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage(), "results", List.of());
        }
    }

    @Tool(name = "graph_add_edge_labels",
          description = "Add custom labels/tags to a graph edge (relationship). "
                  + "Labels supplement the edge's type with user-defined categorization.")
    public Map<String, Object> addEdgeLabels(AddEdgeLabelsInput input) {
        if (input.edgeId() == null || input.edgeId().isBlank()) {
            return Map.of("error", "edgeId is required");
        }
        if (input.labels() == null || input.labels().isEmpty()) {
            return Map.of("error", "At least one label is required");
        }

        try {
            Optional<GraphEdge> opt = graphService.getEdge(input.edgeId());
            if (opt.isEmpty()) {
                return Map.of("error", "Edge not found: " + input.edgeId());
            }

            GraphEdge edge = opt.get();
            Map<String, Object> metadata = parseMetadata(edge.getMetadataJson());
            Set<String> existing = getLabelsFromMetadata(metadata);
            existing.addAll(input.labels());
            metadata.put(LABELS_KEY, new ArrayList<>(existing));
            edge.setMetadataJson(objectMapper.writeValueAsString(metadata));
            graphService.saveEdge(edge);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("edgeId", input.edgeId());
            result.put("labels", new ArrayList<>(existing));
            result.put("added", input.labels());
            return result;

        } catch (Exception e) {
            log.error("Add edge labels failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_remove_edge_labels",
          description = "Remove custom labels/tags from a graph edge.")
    public Map<String, Object> removeEdgeLabels(RemoveEdgeLabelsInput input) {
        if (input.edgeId() == null || input.edgeId().isBlank()) {
            return Map.of("error", "edgeId is required");
        }
        if (input.labels() == null || input.labels().isEmpty()) {
            return Map.of("error", "At least one label is required");
        }

        try {
            Optional<GraphEdge> opt = graphService.getEdge(input.edgeId());
            if (opt.isEmpty()) {
                return Map.of("error", "Edge not found: " + input.edgeId());
            }

            GraphEdge edge = opt.get();
            Map<String, Object> metadata = parseMetadata(edge.getMetadataJson());
            Set<String> existing = getLabelsFromMetadata(metadata);
            existing.removeAll(input.labels());
            metadata.put(LABELS_KEY, new ArrayList<>(existing));
            edge.setMetadataJson(objectMapper.writeValueAsString(metadata));
            graphService.saveEdge(edge);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("edgeId", input.edgeId());
            result.put("labels", new ArrayList<>(existing));
            result.put("removed", input.labels());
            return result;

        } catch (Exception e) {
            log.error("Remove edge labels failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_bulk_label_nodes",
          description = "Add labels to multiple nodes at once. "
                  + "Provide a list of nodeIds and a list of labels to apply to all of them. "
                  + "Useful for batch categorization after query results.")
    public Map<String, Object> bulkLabelNodes(BulkLabelNodesInput input) {
        if (input.nodeIds() == null || input.nodeIds().isEmpty()) {
            return Map.of("error", "nodeIds is required");
        }
        if (input.labels() == null || input.labels().isEmpty()) {
            return Map.of("error", "At least one label is required");
        }

        try {
            List<GraphNode> nodes = graphService.getNodesByIds(input.nodeIds());
            int updated = 0;

            for (GraphNode node : nodes) {
                Map<String, Object> metadata = parseMetadata(node.getMetadataJson());
                Set<String> existing = getLabelsFromMetadata(metadata);
                existing.addAll(input.labels());
                metadata.put(LABELS_KEY, new ArrayList<>(existing));
                node.setMetadataJson(objectMapper.writeValueAsString(metadata));
                graphService.saveNode(node);
                updated++;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("requestedCount", input.nodeIds().size());
            result.put("updatedCount", updated);
            result.put("labels", input.labels());
            return result;

        } catch (Exception e) {
            log.error("Bulk label failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_list_all_labels",
          description = "List all distinct labels in use across the graph, with their frequency. "
                  + "Optionally scope to a factSheetId. "
                  + "Useful for discovering the labeling taxonomy already applied.")
    public Map<String, Object> listAllLabels(ListAllLabelsInput input) {
        try {
            List<GraphNode> nodes;
            if (input.factSheetId() != null) {
                nodes = graphService.getNodesInFactSheet(input.factSheetId());
            } else {
                nodes = graphService.getAllNodes(5000);
            }

            Map<String, Long> labelCounts = new TreeMap<>();
            for (GraphNode node : nodes) {
                Map<String, Object> metadata = parseMetadata(node.getMetadataJson());
                Set<String> labels = getLabelsFromMetadata(metadata);
                for (String label : labels) {
                    labelCounts.merge(label, 1L, Long::sum);
                }
            }

            List<Map<String, Object>> labelList = labelCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("label", e.getKey());
                        m.put("count", e.getValue());
                        return m;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("distinctLabels", labelList.size());
            result.put("labels", labelList);
            return result;

        } catch (Exception e) {
            log.error("List all labels failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    Set<String> getLabelsFromMetadata(Map<String, Object> metadata) {
        Object raw = metadata.get(LABELS_KEY);
        if (raw instanceof Collection) {
            return new LinkedHashSet<>(((Collection<String>) raw));
        }
        return new LinkedHashSet<>();
    }
}
