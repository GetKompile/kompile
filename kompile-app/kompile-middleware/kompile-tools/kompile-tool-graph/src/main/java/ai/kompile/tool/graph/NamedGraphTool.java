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

import ai.kompile.knowledgegraph.domain.NamedGraph;
import ai.kompile.knowledgegraph.service.NamedGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tools for managing named subgraphs (graph-of-graphs).
 * Supports CRUD, hierarchy navigation, node membership, and search.
 */
@Component
@ConditionalOnBean(NamedGraphService.class)
public class NamedGraphTool {

    private static final Logger log = LoggerFactory.getLogger(NamedGraphTool.class);

    private final NamedGraphService namedGraphService;

    // ═══════════════════════════════════════════════════════════════════════════
    // INPUT RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    public record CreateNamedGraphInput(
            String name,
            String description,
            String parentGraphId,
            Long factSheetId,
            String ontologyType
    ) {}

    public record UpdateNamedGraphInput(
            String graphId,
            String name,
            String description,
            String metadataJson
    ) {}

    public record DeleteNamedGraphInput(String graphId) {}

    public record GetNamedGraphInput(String graphId) {}

    public record ListNamedGraphsInput(String parentGraphId) {}

    public record SearchNamedGraphsInput(String query) {}

    public record GraphHierarchyInput(
            String graphId,
            Integer maxDepth
    ) {}

    public record LinkNodeInput(
            String nodeId,
            String graphId
    ) {}

    public record UnlinkNodeInput(
            String nodeId,
            String graphId
    ) {}

    public record MoveGraphInput(
            String graphId,
            String newParentGraphId
    ) {}

    public record GraphStatsInput(String graphId) {}

    @Autowired
    public NamedGraphTool(NamedGraphService namedGraphService) {
        this.namedGraphService = namedGraphService;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOOLS
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "graph_create_named_graph",
          description = "Create a new named subgraph for organizing nodes into logical groups. "
                  + "Named graphs support hierarchical nesting (graph-of-graphs). "
                  + "Set parentGraphId to nest under an existing graph, or omit for a root graph. "
                  + "ontologyType categorizes the graph (e.g., 'domain_ontology', 'taxonomy', 'project').")
    public Map<String, Object> createNamedGraph(CreateNamedGraphInput input) {
        if (input.name() == null || input.name().isBlank()) {
            return Map.of("error", "name is required");
        }

        try {
            NamedGraph graph = namedGraphService.createGraph(
                    input.name(), input.description(),
                    input.parentGraphId(), input.factSheetId(), input.ontologyType());

            return namedGraphToMap(graph);

        } catch (Exception e) {
            log.error("Create named graph failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_update_named_graph",
          description = "Update a named graph's name, description, or metadata. "
                  + "Fields set to null are left unchanged.")
    public Map<String, Object> updateNamedGraph(UpdateNamedGraphInput input) {
        if (input.graphId() == null || input.graphId().isBlank()) {
            return Map.of("error", "graphId is required");
        }

        try {
            NamedGraph updated = namedGraphService.updateGraph(
                    input.graphId(), input.name(), input.description(), input.metadataJson());
            return namedGraphToMap(updated);

        } catch (Exception e) {
            log.error("Update named graph failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_delete_named_graph",
          description = "Delete a named graph and all its descendant subgraphs. "
                  + "Nodes linked to the graph are not deleted, only unlinked. "
                  + "This operation cannot be undone.")
    public Map<String, Object> deleteNamedGraph(DeleteNamedGraphInput input) {
        if (input.graphId() == null || input.graphId().isBlank()) {
            return Map.of("error", "graphId is required");
        }

        try {
            namedGraphService.deleteGraph(input.graphId());
            return Map.of("deleted", true, "graphId", input.graphId());

        } catch (Exception e) {
            log.error("Delete named graph failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_get_named_graph",
          description = "Get details of a specific named graph by its ID, "
                  + "including node count, edge count, child graphs, and metadata.")
    public Map<String, Object> getNamedGraph(GetNamedGraphInput input) {
        if (input.graphId() == null || input.graphId().isBlank()) {
            return Map.of("error", "graphId is required");
        }

        try {
            Optional<NamedGraph> opt = namedGraphService.getGraph(input.graphId());
            if (opt.isEmpty()) {
                return Map.of("error", "Named graph not found: " + input.graphId());
            }
            return namedGraphToMap(opt.get());

        } catch (Exception e) {
            log.error("Get named graph failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_list_named_graphs",
          description = "List named graphs. Provide parentGraphId to list children of a specific graph, "
                  + "or omit to list all root-level graphs.")
    public Map<String, Object> listNamedGraphs(ListNamedGraphsInput input) {
        try {
            List<NamedGraph> graphs;
            if (input.parentGraphId() != null && !input.parentGraphId().isBlank()) {
                graphs = namedGraphService.getChildGraphs(input.parentGraphId());
            } else {
                graphs = namedGraphService.getRootGraphs();
            }

            List<Map<String, Object>> graphList = graphs.stream()
                    .map(this::namedGraphToMap)
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("level", input.parentGraphId() != null ? "children" : "root");
            result.put("count", graphList.size());
            result.put("graphs", graphList);
            return result;

        } catch (Exception e) {
            log.error("List named graphs failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_search_named_graphs",
          description = "Search named graphs by name (case-insensitive substring match).")
    public Map<String, Object> searchNamedGraphs(SearchNamedGraphsInput input) {
        try {
            List<NamedGraph> results = namedGraphService.searchGraphs(
                    input.query() != null ? input.query() : "");

            List<Map<String, Object>> graphList = results.stream()
                    .map(this::namedGraphToMap)
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", input.query());
            result.put("count", graphList.size());
            result.put("graphs", graphList);
            return result;

        } catch (Exception e) {
            log.error("Search named graphs failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_hierarchy",
          description = "Get the full hierarchy tree rooted at a named graph. "
                  + "Returns a nested structure showing the graph and all its descendant subgraphs. "
                  + "maxDepth controls how deep to traverse (default 3).")
    public Map<String, Object> getGraphHierarchy(GraphHierarchyInput input) {
        if (input.graphId() == null || input.graphId().isBlank()) {
            return Map.of("error", "graphId is required");
        }

        int maxDepth = input.maxDepth() != null && input.maxDepth() > 0
                ? Math.min(input.maxDepth(), 10) : 3;

        try {
            return namedGraphService.getGraphHierarchy(input.graphId(), maxDepth);

        } catch (Exception e) {
            log.error("Graph hierarchy failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_link_node",
          description = "Link an existing graph node to a named graph. "
                  + "The node becomes a member of the named graph for organizational purposes. "
                  + "A node can only belong to one named graph at a time.")
    public Map<String, Object> linkNode(LinkNodeInput input) {
        if (input.nodeId() == null || input.graphId() == null) {
            return Map.of("error", "Both nodeId and graphId are required");
        }

        try {
            namedGraphService.linkNodeToGraph(input.nodeId(), input.graphId());
            return Map.of(
                    "linked", true,
                    "nodeId", input.nodeId(),
                    "graphId", input.graphId()
            );

        } catch (Exception e) {
            log.error("Link node failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_unlink_node",
          description = "Remove a node from a named graph. "
                  + "The node is not deleted, just disassociated from the named graph.")
    public Map<String, Object> unlinkNode(UnlinkNodeInput input) {
        if (input.nodeId() == null || input.graphId() == null) {
            return Map.of("error", "Both nodeId and graphId are required");
        }

        try {
            namedGraphService.unlinkNodeFromGraph(input.nodeId(), input.graphId());
            return Map.of(
                    "unlinked", true,
                    "nodeId", input.nodeId(),
                    "graphId", input.graphId()
            );

        } catch (Exception e) {
            log.error("Unlink node failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_move_named_graph",
          description = "Move a named graph to a new parent, or promote it to a root graph. "
                  + "Set newParentGraphId to null to make it a root graph. "
                  + "Prevents cycles (cannot move a graph under its own descendant).")
    public Map<String, Object> moveGraph(MoveGraphInput input) {
        if (input.graphId() == null || input.graphId().isBlank()) {
            return Map.of("error", "graphId is required");
        }

        try {
            NamedGraph moved = namedGraphService.moveGraph(input.graphId(), input.newParentGraphId());
            return namedGraphToMap(moved);

        } catch (Exception e) {
            log.error("Move graph failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_named_graph_stats",
          description = "Get statistics for a named graph: node count, edge count, "
                  + "child graph count, depth in hierarchy, and descendant count.")
    public Map<String, Object> getGraphStats(GraphStatsInput input) {
        if (input.graphId() == null || input.graphId().isBlank()) {
            return Map.of("error", "graphId is required");
        }

        try {
            return namedGraphService.getGraphStatistics(input.graphId());

        } catch (Exception e) {
            log.error("Graph stats failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> namedGraphToMap(NamedGraph graph) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("graphId", graph.getGraphId());
        m.put("name", graph.getName());
        m.put("description", graph.getDescription());
        m.put("ontologyType", graph.getOntologyType());
        m.put("nodeCount", graph.getNodeCount());
        m.put("edgeCount", graph.getEdgeCount());
        m.put("childGraphCount", graph.getChildGraphCount());
        m.put("factSheetId", graph.getFactSheetId());
        if (graph.getParentGraph() != null) {
            m.put("parentGraphId", graph.getParentGraph().getGraphId());
        }
        m.put("createdAt", graph.getCreatedAt());
        m.put("updatedAt", graph.getUpdatedAt());
        return m;
    }
}
