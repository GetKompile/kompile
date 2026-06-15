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

import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.graph.algorithms.service.GraphAlgorithmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tools for creating, updating, and deleting nodes and edges in the knowledge graph.
 * After mutations the algorithm cache is invalidated so subsequent queries reflect changes.
 */
@Component
@ConditionalOnBean(KnowledgeGraphService.class)
public class GraphMutationTool {

    private static final Logger log = LoggerFactory.getLogger(GraphMutationTool.class);

    private final KnowledgeGraphService graphService;
    private final GraphAlgorithmService algorithmService;

    // ═══════════════════════════════════════════════════════════════════════════
    // INPUT RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    public record CreateNodeInput(
            String title,
            String nodeType,
            String description,
            String externalId,
            Long factSheetId
    ) {}

    public record UpdateNodeInput(
            String nodeId,
            String title,
            String description
    ) {}

    public record DeleteNodeInput(String nodeId) {}

    public record CreateEdgeInput(
            String sourceNodeId,
            String targetNodeId,
            String edgeType,
            Double weight,
            String description
    ) {}

    public record UpdateEdgeInput(
            String edgeId,
            Double weight,
            String description
    ) {}

    public record DeleteEdgeInput(String edgeId) {}

    public record BulkCreateEdgesInput(
            List<EdgeSpec> edges
    ) {
        public record EdgeSpec(
                String sourceNodeId,
                String targetNodeId,
                String edgeType,
                Double weight,
                String description
        ) {}
    }

    public record MergeNodesInput(
            String canonicalNodeId,
            List<String> mergeNodeIds
    ) {}

    @Autowired
    public GraphMutationTool(KnowledgeGraphService graphService,
                             @Autowired(required = false) GraphAlgorithmService algorithmService) {
        this.graphService = graphService;
        this.algorithmService = algorithmService;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOOLS
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "graph_create_node",
          description = "Create a new node in the knowledge graph. "
                  + "nodeType must be one of: SOURCE, DOCUMENT, SNIPPET, ENTITY, CUSTOM, TABLE. "
                  + "ENTITY and CUSTOM are the most common for user-created nodes. "
                  + "Returns the created node's ID and properties.")
    public Map<String, Object> createNode(CreateNodeInput input) {
        if (input.title() == null || input.title().isBlank()) {
            return Map.of("error", "title is required");
        }

        NodeLevel type = GraphSearchTool.parseNodeLevel(input.nodeType());
        if (type == null) {
            type = NodeLevel.CUSTOM;
        }

        try {
            GraphNode node = graphService.createNode(
                    type,
                    input.externalId(),
                    input.title(),
                    input.description(),
                    Map.of(),
                    input.factSheetId()
            );

            invalidateCache(input.factSheetId());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodeId", node.getNodeId());
            result.put("title", node.getTitle());
            result.put("type", node.getNodeType().name());
            result.put("description", node.getDescription());
            result.put("factSheetId", node.getFactSheetId());
            return result;

        } catch (Exception e) {
            log.error("Create node failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_update_node",
          description = "Update an existing node's title and/or description. "
                  + "Provide nodeId and the fields you want to change. "
                  + "Fields set to null are left unchanged.")
    public Map<String, Object> updateNode(UpdateNodeInput input) {
        if (input.nodeId() == null || input.nodeId().isBlank()) {
            return Map.of("error", "nodeId is required");
        }

        try {
            GraphNode updated = graphService.updateNode(
                    input.nodeId(), input.title(), input.description(), null);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodeId", updated.getNodeId());
            result.put("title", updated.getTitle());
            result.put("type", updated.getNodeType().name());
            result.put("description", GraphSearchTool.truncate(updated.getDescription(), 200));
            return result;

        } catch (Exception e) {
            log.error("Update node failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_delete_node",
          description = "Delete a node and all its descendants from the knowledge graph. "
                  + "WARNING: This also removes all edges connected to the node. "
                  + "This operation cannot be undone.")
    public Map<String, Object> deleteNode(DeleteNodeInput input) {
        if (input.nodeId() == null || input.nodeId().isBlank()) {
            return Map.of("error", "nodeId is required");
        }

        try {
            // Get node info before deletion for the response
            Optional<GraphNode> opt = graphService.getNode(input.nodeId());
            if (opt.isEmpty()) {
                return Map.of("error", "Node not found: " + input.nodeId());
            }

            GraphNode node = opt.get();
            String title = node.getTitle();
            Long factSheetId = node.getFactSheetId();

            graphService.deleteNode(input.nodeId());
            invalidateCache(factSheetId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deleted", true);
            result.put("nodeId", input.nodeId());
            result.put("title", title);
            return result;

        } catch (Exception e) {
            log.error("Delete node failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_create_edge",
          description = "Create a new edge (relationship) between two nodes. "
                  + "edgeType must be one of: HIERARCHICAL, EMBEDDING_SIMILARITY, SHARED_ENTITY, "
                  + "USER_DEFINED, CITATION, TEMPORAL, CROSS_SOURCE, CONTAINS. "
                  + "USER_DEFINED is the default for manually created relationships. "
                  + "weight is optional (0.0 to 1.0, default 1.0).")
    public Map<String, Object> createEdge(CreateEdgeInput input) {
        if (input.sourceNodeId() == null || input.targetNodeId() == null) {
            return Map.of("error", "Both sourceNodeId and targetNodeId are required");
        }

        EdgeType type = GraphSearchTool.parseEdgeType(input.edgeType());
        if (type == null) {
            type = EdgeType.USER_DEFINED;
        }

        double weight = input.weight() != null ? input.weight() : 1.0;

        try {
            GraphEdge edge = graphService.createEdge(
                    input.sourceNodeId(), input.targetNodeId(),
                    type, weight, input.description()
            );

            invalidateCacheGlobal();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("edgeId", edge.getEdgeId());
            result.put("edgeType", edge.getEdgeType().name());
            result.put("weight", edge.getWeight());
            result.put("sourceNodeId", input.sourceNodeId());
            result.put("targetNodeId", input.targetNodeId());
            result.put("description", edge.getDescription());
            return result;

        } catch (Exception e) {
            log.error("Create edge failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_update_edge",
          description = "Update an existing edge's weight and/or description. "
                  + "Provide edgeId and the fields you want to change.")
    public Map<String, Object> updateEdge(UpdateEdgeInput input) {
        if (input.edgeId() == null || input.edgeId().isBlank()) {
            return Map.of("error", "edgeId is required");
        }

        try {
            GraphEdge updated = graphService.updateEdge(
                    input.edgeId(), input.weight(), input.description());

            invalidateCacheGlobal();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("edgeId", updated.getEdgeId());
            result.put("edgeType", updated.getEdgeType().name());
            result.put("weight", updated.getWeight());
            result.put("description", updated.getDescription());
            return result;

        } catch (Exception e) {
            log.error("Update edge failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_delete_edge",
          description = "Delete an edge (relationship) from the knowledge graph. "
                  + "The connected nodes are not affected. This cannot be undone.")
    public Map<String, Object> deleteEdge(DeleteEdgeInput input) {
        if (input.edgeId() == null || input.edgeId().isBlank()) {
            return Map.of("error", "edgeId is required");
        }

        try {
            Optional<GraphEdge> opt = graphService.getEdge(input.edgeId());
            if (opt.isEmpty()) {
                return Map.of("error", "Edge not found: " + input.edgeId());
            }

            graphService.deleteEdge(input.edgeId());
            invalidateCacheGlobal();

            return Map.of("deleted", true, "edgeId", input.edgeId());

        } catch (Exception e) {
            log.error("Delete edge failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_bulk_create_edges",
          description = "Create multiple edges in one call. "
                  + "Each edge spec requires sourceNodeId, targetNodeId, and optionally edgeType, weight, description. "
                  + "Returns the count of successfully created edges.")
    public Map<String, Object> bulkCreateEdges(BulkCreateEdgesInput input) {
        if (input.edges() == null || input.edges().isEmpty()) {
            return Map.of("error", "At least one edge specification is required");
        }

        int created = 0;
        List<String> errors = new ArrayList<>();

        for (BulkCreateEdgesInput.EdgeSpec spec : input.edges()) {
            try {
                EdgeType type = GraphSearchTool.parseEdgeType(spec.edgeType());
                if (type == null) type = EdgeType.USER_DEFINED;
                double weight = spec.weight() != null ? spec.weight() : 1.0;

                graphService.createEdge(
                        spec.sourceNodeId(), spec.targetNodeId(),
                        type, weight, spec.description());
                created++;
            } catch (Exception e) {
                errors.add(spec.sourceNodeId() + "->" + spec.targetNodeId() + ": " + e.getMessage());
            }
        }

        invalidateCacheGlobal();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requested", input.edges().size());
        result.put("created", created);
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        return result;
    }

    @Tool(name = "graph_merge_nodes",
          description = "Merge multiple nodes into a canonical node. "
                  + "All edges pointing to/from the merged nodes are redirected to the canonical node. "
                  + "The merged nodes are deleted after edge redirection. "
                  + "Use this to consolidate duplicate entities.")
    public Map<String, Object> mergeNodes(MergeNodesInput input) {
        if (input.canonicalNodeId() == null || input.canonicalNodeId().isBlank()) {
            return Map.of("error", "canonicalNodeId is required");
        }
        if (input.mergeNodeIds() == null || input.mergeNodeIds().isEmpty()) {
            return Map.of("error", "At least one mergeNodeId is required");
        }

        try {
            Optional<GraphNode> canonicalOpt = graphService.getNode(input.canonicalNodeId());
            if (canonicalOpt.isEmpty()) {
                return Map.of("error", "Canonical node not found: " + input.canonicalNodeId());
            }

            int edgesRedirected = 0;
            int nodesDeleted = 0;

            for (String mergeId : input.mergeNodeIds()) {
                if (mergeId.equals(input.canonicalNodeId())) continue;

                Optional<GraphNode> mergeOpt = graphService.getNode(mergeId);
                if (mergeOpt.isEmpty()) continue;

                // Redirect all edges from the merge node to the canonical node
                List<GraphEdge> edges = graphService.getEdgesForNode(mergeId);
                for (GraphEdge edge : edges) {
                    String srcId = edge.getSourceNode() != null ? edge.getSourceNode().getNodeId() : null;
                    String tgtId = edge.getTargetNode() != null ? edge.getTargetNode().getNodeId() : null;

                    String newSrc = mergeId.equals(srcId) ? input.canonicalNodeId() : srcId;
                    String newTgt = mergeId.equals(tgtId) ? input.canonicalNodeId() : tgtId;

                    if (newSrc != null && newTgt != null && !newSrc.equals(newTgt)) {
                        if (!graphService.edgeExists(newSrc, newTgt)) {
                            graphService.createEdge(newSrc, newTgt,
                                    edge.getEdgeType(), edge.getWeight(), edge.getDescription());
                            edgesRedirected++;
                        }
                    }
                }

                graphService.deleteNode(mergeId);
                nodesDeleted++;
            }

            invalidateCacheGlobal();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("canonicalNodeId", input.canonicalNodeId());
            result.put("canonicalTitle", canonicalOpt.get().getTitle());
            result.put("nodesDeleted", nodesDeleted);
            result.put("edgesRedirected", edgesRedirected);
            return result;

        } catch (Exception e) {
            log.error("Merge nodes failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private void invalidateCache(Long factSheetId) {
        if (algorithmService != null) {
            algorithmService.invalidateCache(factSheetId);
        }
    }

    private void invalidateCacheGlobal() {
        if (algorithmService != null) {
            algorithmService.invalidateCache();
        }
    }
}
