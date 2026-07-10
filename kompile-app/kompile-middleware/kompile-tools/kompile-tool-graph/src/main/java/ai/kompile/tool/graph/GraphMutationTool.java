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
import ai.kompile.knowledgegraph.grounding.GroundingResetPort;
import ai.kompile.knowledgegraph.grounding.GroundingProgressEvent;
import ai.kompile.knowledgegraph.reasoning.GraphToFactStoreProjector;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.GraphSnapshotService;
import ai.kompile.graph.algorithms.service.GraphAlgorithmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
    private final GraphToFactStoreProjector factStoreProjector;
    private final GroundingResetPort groundingResetPort;

    /** Optional — injected when the snapshot service is available. */
    private GraphSnapshotService snapshotService;

    @Autowired(required = false)
    public void setSnapshotService(GraphSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INPUT RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    public record CreateNodeInput(
            String title,
            String nodeType,
            String description,
            String externalId,
            Long factSheetId,
            Map<String, Object> metadata
    ) {
        public CreateNodeInput(String title, String nodeType, String description,
                               String externalId, Long factSheetId) {
            this(title, nodeType, description, externalId, factSheetId, null);
        }
    }

    public record BulkCreateNodesInput(
            List<NodeSpec> nodes,
            Long factSheetId
    ) {
        public BulkCreateNodesInput(List<NodeSpec> nodes) {
            this(nodes, null);
        }

        public record NodeSpec(
                String title,
                String nodeType,
                String description,
                String externalId,
                Map<String, Object> metadata,
                Long factSheetId
        ) {
            public NodeSpec(String title, String nodeType, String description,
                            String externalId, Map<String, Object> metadata) {
                this(title, nodeType, description, externalId, metadata, null);
            }
        }
    }

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
            String description,
            String relationType,
            Long factSheetId
    ) {
        public CreateEdgeInput(String sourceNodeId, String targetNodeId, String edgeType,
                               Double weight, String description) {
            this(sourceNodeId, targetNodeId, edgeType, weight, description, null, null);
        }
    }

    public record UpdateEdgeInput(
            String edgeId,
            Double weight,
            String description
    ) {}

    public record DeleteEdgeInput(String edgeId) {}

    public record BulkCreateEdgesInput(
            List<EdgeSpec> edges,
            Long factSheetId
    ) {
        public BulkCreateEdgesInput(List<EdgeSpec> edges) {
            this(edges, null);
        }

        public record EdgeSpec(
                String sourceNodeId,
                String targetNodeId,
                String edgeType,
                Double weight,
                String description,
                String relationType,
                Long factSheetId
        ) {
            public EdgeSpec(String sourceNodeId, String targetNodeId, String edgeType,
                            Double weight, String description) {
                this(sourceNodeId, targetNodeId, edgeType, weight, description, null, null);
            }
        }
    }

    public record SeedGraphInput(
            List<SeedNodeSpec> nodes,
            List<SeedEdgeSpec> edges,
            Long factSheetId,
            Boolean rollbackOnFailure
    ) {
        public SeedGraphInput(List<SeedNodeSpec> nodes, List<SeedEdgeSpec> edges, Long factSheetId) {
            this(nodes, edges, factSheetId, false);
        }

        public SeedGraphInput(List<SeedNodeSpec> nodes, List<SeedEdgeSpec> edges) {
            this(nodes, edges, null, false);
        }

        public record SeedNodeSpec(
                String clientId,
                String nodeId,
                String title,
                String nodeType,
                String description,
                String externalId,
                Map<String, Object> metadata,
                Long factSheetId
        ) {}

        public record SeedEdgeSpec(
                String sourceNodeId,
                String targetNodeId,
                String sourceClientId,
                String targetClientId,
                String edgeType,
                Double weight,
                String description,
                String relationType,
                Long factSheetId
        ) {}
    }

    public record SeedTraceInput(
            List<Map<String, Object>> attributionIndex,
            List<Map<String, Object>> traceGaps,
            Long factSheetId,
            String conclusionNodeId,
            Boolean includeEvidenceRefs,
            Boolean includeTraceGaps,
            Boolean rollbackOnFailure
    ) {
        public SeedTraceInput(List<Map<String, Object>> attributionIndex,
                              List<Map<String, Object>> traceGaps,
                              Long factSheetId) {
            this(attributionIndex, traceGaps, factSheetId, null, true, true, false);
        }
    }

    public record MergeNodesInput(
            String canonicalNodeId,
            List<String> mergeNodeIds
    ) {}

    public GraphMutationTool(KnowledgeGraphService graphService,
                             GraphAlgorithmService algorithmService) {
        this(graphService, algorithmService, null, null);
    }

    public GraphMutationTool(KnowledgeGraphService graphService,
                             GraphAlgorithmService algorithmService,
                             GraphToFactStoreProjector factStoreProjector) {
        this(graphService, algorithmService, factStoreProjector, null);
    }

    @Autowired
    public GraphMutationTool(KnowledgeGraphService graphService,
                             @Autowired(required = false) GraphAlgorithmService algorithmService,
                             @Autowired(required = false) GraphToFactStoreProjector factStoreProjector,
                             @Autowired(required = false) GroundingResetPort groundingResetPort) {
        this.graphService = graphService;
        this.algorithmService = algorithmService;
        this.factStoreProjector = factStoreProjector;
        this.groundingResetPort = groundingResetPort;
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
                    metadata(input.metadata()),
                    input.factSheetId()
            );

            invalidateCache(input.factSheetId());

            Map<String, Object> result = nodeResult(node, input.factSheetId());
            putProjectionResult(result, projectFactStores(singletonFactSheet(input.factSheetId())));
            return result;

        } catch (Exception e) {
            log.error("Create node failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_bulk_create_nodes",
          description = "Create multiple nodes in the knowledge graph in one call. "
                  + "Use factSheetId to seed a scoped graph. Each node may include metadata for attribution, "
                  + "evidence references, confidence, and source identifiers. Returns created node IDs in input order.")
    public Map<String, Object> bulkCreateNodes(BulkCreateNodesInput input) {
        if (input.nodes() == null || input.nodes().isEmpty()) {
            return Map.of("error", "At least one node specification is required");
        }

        record PendingNode(int index, BulkCreateNodesInput.NodeSpec spec, NodeLevel type, Long factSheetId) {}

        Map<Long, List<PendingNode>> grouped = new LinkedHashMap<>();
        List<Map<String, Object>> nodesByIndex = new ArrayList<>(Collections.nCopies(input.nodes().size(), null));
        List<String> errors = new ArrayList<>();
        Set<Long> touchedFactSheets = new LinkedHashSet<>();
        boolean touchedGlobal = false;

        for (int i = 0; i < input.nodes().size(); i++) {
            BulkCreateNodesInput.NodeSpec spec = input.nodes().get(i);
            if (spec == null || isBlank(spec.title())) {
                errors.add("node[" + i + "]: title is required");
                continue;
            }

            NodeLevel type = GraphSearchTool.parseNodeLevel(spec.nodeType());
            if (type == null) {
                type = NodeLevel.CUSTOM;
            }
            Long factSheetId = spec.factSheetId() != null ? spec.factSheetId() : input.factSheetId();
            if (factSheetId != null) {
                touchedFactSheets.add(factSheetId);
            } else {
                touchedGlobal = true;
            }
            grouped.computeIfAbsent(factSheetId, ignored -> new ArrayList<>())
                    .add(new PendingNode(i, spec, type, factSheetId));
        }

        int created = 0;
        for (Map.Entry<Long, List<PendingNode>> entry : grouped.entrySet()) {
            List<PendingNode> group = entry.getValue();
            List<KnowledgeGraphService.NodeSpec> specs = group.stream()
                    .map(p -> new KnowledgeGraphService.NodeSpec(
                            p.type(),
                            p.spec().externalId(),
                            p.spec().title(),
                            p.spec().description(),
                            metadata(p.spec().metadata())))
                    .collect(Collectors.toList());
            try {
                List<GraphNode> createdNodes = graphService.createNodesBatch(specs, entry.getKey());
                int returned = createdNodes != null ? createdNodes.size() : 0;
                for (int j = 0; j < returned && j < group.size(); j++) {
                    PendingNode pending = group.get(j);
                    nodesByIndex.set(pending.index(), nodeResult(createdNodes.get(j), pending.factSheetId()));
                    created++;
                }
                for (int j = returned; j < group.size(); j++) {
                    errors.add("node[" + group.get(j).index() + "] " + group.get(j).spec().title()
                            + ": service did not return a created node");
                }
            } catch (Exception e) {
                log.error("Bulk create nodes failed for factSheetId {}: {}", entry.getKey(), e.getMessage(), e);
                for (PendingNode pending : group) {
                    errors.add("node[" + pending.index() + "] " + pending.spec().title() + ": " + e.getMessage());
                }
            }
        }

        if (created > 0) {
            invalidateCaches(touchedFactSheets, touchedGlobal);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requested", input.nodes().size());
        result.put("valid", grouped.values().stream().mapToInt(List::size).sum());
        result.put("created", created);
        result.put("nodes", nodesByIndex.stream().filter(Objects::nonNull).collect(Collectors.toList()));
        if (!touchedFactSheets.isEmpty()) {
            result.put("factSheetIds", new ArrayList<>(touchedFactSheets));
            if (created > 0) {
                putProjectionResult(result, projectFactStores(touchedFactSheets));
            }
        }
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        return result;
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
                  + "relationType may carry a semantic predicate such as SUPPORTS, CONTRADICTS, CAUSES, or DERIVED_FROM. "
                  + "factSheetId scopes the edge for unified graph seeding. "
                  + "weight is optional (0.0 to 1.0, default 1.0).")
    public Map<String, Object> createEdge(CreateEdgeInput input) {
        if (isBlank(input.sourceNodeId()) || isBlank(input.targetNodeId())) {
            return Map.of("error", "Both sourceNodeId and targetNodeId are required");
        }

        EdgeType type = edgeType(input.edgeType());
        double weight = input.weight() != null ? input.weight() : 1.0;

        try {
            Long factSheetId = resolveEdgeFactSheetId(input.factSheetId(),
                    input.sourceNodeId(), input.targetNodeId());
            GraphEdge edge = createEdgeInternal(input.sourceNodeId(), input.targetNodeId(),
                    type, weight, input.description(), input.relationType(), factSheetId);

            invalidateCacheForFactSheet(factSheetId);

            Map<String, Object> result = edgeResult(edge, input.sourceNodeId(), input.targetNodeId(), input.relationType(), factSheetId);
            putProjectionResult(result, projectFactStores(singletonFactSheet(factSheetId)));
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
                  + "Each edge spec requires sourceNodeId and targetNodeId, and may include edgeType, relationType, "
                  + "weight, description, and factSheetId. Returns the count of successfully created edges.")
    public Map<String, Object> bulkCreateEdges(BulkCreateEdgesInput input) {
        if (input.edges() == null || input.edges().isEmpty()) {
            return Map.of("error", "At least one edge specification is required");
        }

        List<KnowledgeGraphService.EdgeSpec> specs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<Long> touchedFactSheets = new LinkedHashSet<>();
        boolean touchedGlobal = false;

        for (int i = 0; i < input.edges().size(); i++) {
            BulkCreateEdgesInput.EdgeSpec spec = input.edges().get(i);
            if (spec == null || isBlank(spec.sourceNodeId()) || isBlank(spec.targetNodeId())) {
                errors.add("edge[" + i + "]: sourceNodeId and targetNodeId are required");
                continue;
            }

            EdgeType type = edgeType(spec.edgeType());
            double weight = spec.weight() != null ? spec.weight() : 1.0;
            Long explicitFactSheetId = spec.factSheetId() != null ? spec.factSheetId() : input.factSheetId();
            Long factSheetId = resolveEdgeFactSheetId(explicitFactSheetId, spec.sourceNodeId(), spec.targetNodeId());
            if (factSheetId != null) {
                touchedFactSheets.add(factSheetId);
            } else {
                touchedGlobal = true;
            }

            specs.add(new KnowledgeGraphService.EdgeSpec(
                    spec.sourceNodeId(),
                    spec.targetNodeId(),
                    type,
                    weight,
                    spec.description(),
                    blankToNull(spec.relationType()),
                    null,
                    null,
                    factSheetId
            ));
        }

        int created = 0;
        if (!specs.isEmpty()) {
            try {
                created = graphService.createEdgesBatch(specs);
            } catch (Exception e) {
                log.error("Bulk create edges failed: {}", e.getMessage(), e);
                errors.add("batch: " + e.getMessage());
            }
        }

        if (created > 0) {
            invalidateCaches(touchedFactSheets, touchedGlobal);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requested", input.edges().size());
        result.put("valid", specs.size());
        result.put("created", created);
        if (!touchedFactSheets.isEmpty()) {
            result.put("factSheetIds", new ArrayList<>(touchedFactSheets));
            if (created > 0) {
                putProjectionResult(result, projectFactStores(touchedFactSheets));
            }
        }
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        return result;
    }

    @Tool(name = "graph_seed",
          description = "Seed a fact-sheet graph with nodes and relationships in one call. "
                  + "Nodes may define clientId values so edges can reference newly-created nodes by sourceClientId/targetClientId. "
                  + "Use metadata on nodes for attribution IDs, evidence references, confidence, source spans, and trace gaps. "
                  + "Edges may include relationType for semantic predicates. Set rollbackOnFailure=true for tracked "
                  + "best-effort cleanup if validation or edge writes fail. Returns created IDs and per-item errors.")
    public Map<String, Object> seedGraph(SeedGraphInput input) {
        if (input == null) {
            return Map.of("error", "input is required");
        }
        boolean rollbackOnFailure = Boolean.TRUE.equals(input.rollbackOnFailure());
        boolean noNodes = input.nodes() == null || input.nodes().isEmpty();
        boolean noEdges = input.edges() == null || input.edges().isEmpty();
        if (noNodes && noEdges) {
            return Map.of("error", "At least one node or edge specification is required");
        }

        record PendingSeedNode(int index, SeedGraphInput.SeedNodeSpec spec, NodeLevel type, Long factSheetId) {}

        List<String> errors = new ArrayList<>();
        Map<String, String> clientNodeIds = new LinkedHashMap<>();
        Map<String, Long> clientFactSheetIds = new LinkedHashMap<>();
        Set<String> seenClientIds = new LinkedHashSet<>();
        Set<Long> touchedFactSheets = new LinkedHashSet<>();
        boolean touchedGlobal = false;
        int existingNodes = 0;
        int createdNodes = 0;
        List<String> rollbackNodeIds = new ArrayList<>();
        List<String> rollbackEdgeIds = new ArrayList<>();
        List<Map<String, Object>> createdNodeResults = new ArrayList<>();
        Map<Long, List<PendingSeedNode>> groupedNodes = new LinkedHashMap<>();

        if (!noNodes) {
            for (int i = 0; i < input.nodes().size(); i++) {
                SeedGraphInput.SeedNodeSpec spec = input.nodes().get(i);
                if (spec == null) {
                    errors.add("node[" + i + "]: specification is required");
                    continue;
                }

                String clientId = blankToNull(spec.clientId());
                if (clientId != null && !seenClientIds.add(clientId)) {
                    errors.add("node[" + i + "]: duplicate clientId " + clientId);
                    continue;
                }

                Long factSheetId = spec.factSheetId() != null ? spec.factSheetId() : input.factSheetId();
                String existingNodeId = blankToNull(spec.nodeId());
                if (existingNodeId != null) {
                    if (factSheetId == null) {
                        factSheetId = nodeFactSheetId(existingNodeId);
                    }
                    if (clientId != null) {
                        clientNodeIds.put(clientId, existingNodeId);
                        if (factSheetId != null) {
                            clientFactSheetIds.put(clientId, factSheetId);
                        }
                    }
                    existingNodes++;
                    continue;
                }

                if (isBlank(spec.title())) {
                    errors.add("node[" + i + "]: title is required when nodeId is not provided");
                    continue;
                }

                NodeLevel type = GraphSearchTool.parseNodeLevel(spec.nodeType());
                if (type == null) {
                    type = NodeLevel.CUSTOM;
                }
                groupedNodes.computeIfAbsent(factSheetId, ignored -> new ArrayList<>())
                        .add(new PendingSeedNode(i, spec, type, factSheetId));
            }
        }

        for (Map.Entry<Long, List<PendingSeedNode>> entry : groupedNodes.entrySet()) {
            Long factSheetId = entry.getKey();
            List<PendingSeedNode> group = entry.getValue();
            List<KnowledgeGraphService.NodeSpec> specs = group.stream()
                    .map(p -> new KnowledgeGraphService.NodeSpec(
                            p.type(),
                            p.spec().externalId(),
                            p.spec().title(),
                            p.spec().description(),
                            metadata(p.spec().metadata())))
                    .collect(Collectors.toList());
            try {
                List<GraphNode> nodes = graphService.createNodesBatch(specs, factSheetId);
                int returned = nodes != null ? nodes.size() : 0;
                for (int j = 0; j < returned && j < group.size(); j++) {
                    PendingSeedNode pending = group.get(j);
                    GraphNode node = nodes.get(j);
                    if (rollbackOnFailure && !isBlank(node.getNodeId())) {
                        rollbackNodeIds.add(node.getNodeId());
                    }
                    Map<String, Object> nodeResult = new LinkedHashMap<>(nodeResult(node, pending.factSheetId()));
                    String clientId = blankToNull(pending.spec().clientId());
                    if (clientId != null) {
                        clientNodeIds.put(clientId, node.getNodeId());
                        if (pending.factSheetId() != null) {
                            clientFactSheetIds.put(clientId, pending.factSheetId());
                        }
                        nodeResult.put("clientId", clientId);
                    }
                    createdNodeResults.add(nodeResult);
                    if (pending.factSheetId() != null) {
                        touchedFactSheets.add(pending.factSheetId());
                    } else {
                        touchedGlobal = true;
                    }
                    createdNodes++;
                }
                for (int j = returned; j < group.size(); j++) {
                    errors.add("node[" + group.get(j).index() + "] " + group.get(j).spec().title()
                            + ": service did not return a created node");
                }
            } catch (Exception e) {
                log.error("Graph seed node batch failed for factSheetId {}: {}", factSheetId, e.getMessage(), e);
                for (PendingSeedNode pending : group) {
                    errors.add("node[" + pending.index() + "] " + pending.spec().title() + ": " + e.getMessage());
                }
            }
        }

        List<KnowledgeGraphService.EdgeSpec> edgeSpecs = new ArrayList<>();
        List<Integer> edgeIndexes = new ArrayList<>();
        if (!noEdges) {
            for (int i = 0; i < input.edges().size(); i++) {
                SeedGraphInput.SeedEdgeSpec spec = input.edges().get(i);
                if (spec == null) {
                    errors.add("edge[" + i + "]: specification is required");
                    continue;
                }

                String sourceNodeId = resolveSeedEndpoint(spec.sourceNodeId(), spec.sourceClientId(), clientNodeIds);
                String targetNodeId = resolveSeedEndpoint(spec.targetNodeId(), spec.targetClientId(), clientNodeIds);
                if (isBlank(sourceNodeId) || isBlank(targetNodeId)) {
                    errors.add("edge[" + i + "]: source and target must resolve from nodeId or clientId");
                    continue;
                }

                EdgeType type = edgeType(spec.edgeType());
                double weight = spec.weight() != null ? spec.weight() : 1.0;
                Long factSheetId = resolveSeedEdgeFactSheetId(
                        spec.factSheetId() != null ? spec.factSheetId() : input.factSheetId(),
                        spec.sourceClientId(), spec.targetClientId(), clientFactSheetIds,
                        sourceNodeId, targetNodeId);
                if (factSheetId != null) {
                    touchedFactSheets.add(factSheetId);
                } else {
                    touchedGlobal = true;
                }

                edgeIndexes.add(i);
                edgeSpecs.add(new KnowledgeGraphService.EdgeSpec(
                        sourceNodeId,
                        targetNodeId,
                        type,
                        weight,
                        spec.description(),
                        blankToNull(spec.relationType()),
                        null,
                        null,
                        factSheetId
                ));
            }
        }

        int createdEdges = 0;
        boolean rolledBack = false;
        Map<String, Object> rollbackResult = Map.of();
        if (rollbackOnFailure && !errors.isEmpty()) {
            rollbackResult = rollbackSeedWrites(rollbackEdgeIds, rollbackNodeIds);
            rolledBack = !rollbackResult.isEmpty();
        } else if (!edgeSpecs.isEmpty()) {
            if (rollbackOnFailure) {
                for (int i = 0; i < edgeSpecs.size(); i++) {
                    KnowledgeGraphService.EdgeSpec spec = edgeSpecs.get(i);
                    try {
                        GraphEdge edge = createEdgeInternal(spec.sourceNodeId(), spec.targetNodeId(),
                                spec.edgeType(), spec.weight() != null ? spec.weight() : 1.0,
                                spec.description(), spec.label(), spec.factSheetId());
                        createdEdges++;
                        if (edge != null && !isBlank(edge.getEdgeId())) {
                            rollbackEdgeIds.add(edge.getEdgeId());
                        }
                    } catch (Exception e) {
                        int edgeIndex = i < edgeIndexes.size() ? edgeIndexes.get(i) : i;
                        log.warn("Graph seed edge[{}] failed; rolling back tracked seed writes: {}",
                                edgeIndex, e.getMessage());
                        errors.add("edge[" + edgeIndex + "]: " + e.getMessage());
                        break;
                    }
                }
                if (!errors.isEmpty()) {
                    rollbackResult = rollbackSeedWrites(rollbackEdgeIds, rollbackNodeIds);
                    rolledBack = !rollbackResult.isEmpty();
                }
            } else {
                try {
                    createdEdges = graphService.createEdgesBatch(edgeSpecs);
                } catch (Exception e) {
                    log.error("Graph seed edge batch failed: {}", e.getMessage(), e);
                    errors.add("edges: " + e.getMessage());
                }
            }
        }

        if (createdNodes > 0 || createdEdges > 0) {
            invalidateCaches(touchedFactSheets, touchedGlobal);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestedNodes", noNodes ? 0 : input.nodes().size());
        result.put("existingNodes", existingNodes);
        result.put("createdNodes", createdNodes);
        result.put("requestedEdges", noEdges ? 0 : input.edges().size());
        result.put("validEdges", edgeSpecs.size());
        result.put("createdEdges", createdEdges);
        result.put("nodes", createdNodeResults);
        if (rollbackOnFailure) {
            result.put("rollbackOnFailure", true);
            result.put("rolledBack", rolledBack);
            if (!rollbackResult.isEmpty()) {
                result.put("rollback", rollbackResult);
            }
        }
        if (!clientNodeIds.isEmpty()) {
            result.put("clientNodeIds", clientNodeIds);
        }
        if (!touchedFactSheets.isEmpty()) {
            result.put("factSheetIds", new ArrayList<>(touchedFactSheets));
            if (!rolledBack && (createdNodes > 0 || createdEdges > 0)) {
                putProjectionResult(result, projectFactStores(touchedFactSheets));
            }
        }
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        return result;
    }

    @Tool(name = "graph_seed_trace",
          description = "Seed graph nodes and edges from a reasoning trace DTO. "
                  + "Accepts attributionIndex and traceGaps as returned by reasoning APIs. "
                  + "Creates attribution, evidence, and clarification nodes with metadata and semantic edges.")
    public Map<String, Object> seedTrace(SeedTraceInput input) {
        if (input == null) {
            return Map.of("error", "input is required");
        }

        List<Map<String, Object>> attributions = input.attributionIndex() == null
                ? List.of() : input.attributionIndex();
        List<Map<String, Object>> gaps = Boolean.FALSE.equals(input.includeTraceGaps()) || input.traceGaps() == null
                ? List.of() : input.traceGaps();
        if (attributions.isEmpty() && gaps.isEmpty()) {
            return Map.of("error", "attributionIndex or traceGaps are required");
        }

        boolean includeEvidenceRefs = !Boolean.FALSE.equals(input.includeEvidenceRefs());
        Long factSheetId = input.factSheetId();
        List<SeedGraphInput.SeedNodeSpec> nodes = new ArrayList<>();
        List<SeedGraphInput.SeedEdgeSpec> edges = new ArrayList<>();
        Map<String, String> stepClientIds = new LinkedHashMap<>();
        String rootClientId = null;

        for (int i = 0; i < attributions.size(); i++) {
            Map<String, Object> attribution = attributions.get(i);
            if (attribution == null) {
                continue;
            }
            String stepId = stringValue(attribution, "stepId");
            if (isBlank(stepId)) {
                stepId = "trace.step." + i;
            }
            String clientId = "trace.step." + stepId;
            stepClientIds.put(stepId, clientId);
            if (rootClientId == null) {
                rootClientId = clientId;
            }

            Map<String, Object> metadata = new LinkedHashMap<>(attribution);
            metadata.put("seedType", "TRACE_ATTRIBUTION");
            metadata.put("stepId", stepId);
            if (!includeEvidenceRefs) {
                metadata.remove("evidenceRefs");
            }

            String conclusion = stringValue(attribution, "conclusion");
            String kind = stringValue(attribution, "kind");
            String title = !isBlank(conclusion) ? conclusion : stepId;
            String description = !isBlank(kind) ? kind + " trace step" : "Reasoning trace step";
            nodes.add(new SeedGraphInput.SeedNodeSpec(
                    clientId, null, title, "CUSTOM", description,
                    "trace:step:" + stepId, metadata, factSheetId));

            if (!clientId.equals(rootClientId)) {
                edges.add(new SeedGraphInput.SeedEdgeSpec(
                        null, null, clientId, rootClientId, "USER_DEFINED",
                        doubleValue(attribution, "confidence"), "Trace step supports the conclusion",
                        "SUPPORTS", factSheetId));
            }

            if (includeEvidenceRefs) {
                List<Map<String, Object>> evidenceRefs = mapList(attribution.get("evidenceRefs"));
                for (int refIndex = 0; refIndex < evidenceRefs.size(); refIndex++) {
                    Map<String, Object> evidenceRef = evidenceRefs.get(refIndex);
                    Long refFactSheetId = longValue(evidenceRef, "factSheetId");
                    if (refFactSheetId == null) {
                        refFactSheetId = factSheetId;
                    }
                    String evidenceNodeId = stringValue(evidenceRef, "nodeId");
                    if (!isBlank(evidenceNodeId)) {
                        edges.add(new SeedGraphInput.SeedEdgeSpec(
                                evidenceNodeId, null, null, clientId, "USER_DEFINED", 1.0,
                                "Evidence reference supports trace step", "EVIDENCE_FOR", refFactSheetId));
                        continue;
                    }

                    String evidenceClientId = "trace.evidence." + stepId + "." + refIndex;
                    Map<String, Object> evidenceMetadata = new LinkedHashMap<>(evidenceRef);
                    evidenceMetadata.put("seedType", "TRACE_EVIDENCE");
                    evidenceMetadata.put("stepId", stepId);
                    nodes.add(new SeedGraphInput.SeedNodeSpec(
                            evidenceClientId, null, evidenceTitle(evidenceRef, refIndex), "CUSTOM",
                            "Reasoning trace evidence reference", evidenceExternalId(stepId, refIndex, evidenceRef),
                            evidenceMetadata, refFactSheetId));
                    edges.add(new SeedGraphInput.SeedEdgeSpec(
                            null, null, evidenceClientId, clientId, "USER_DEFINED", 1.0,
                            "Evidence reference supports trace step", "EVIDENCE_FOR", refFactSheetId));
                }
            }
        }

        for (int i = 0; i < gaps.size(); i++) {
            Map<String, Object> gap = gaps.get(i);
            if (gap == null) {
                continue;
            }
            String question = stringValue(gap, "question");
            if (isBlank(question)) {
                question = "Clarify trace gap " + i;
            }
            String gapClientId = "trace.gap." + i;
            Map<String, Object> metadata = new LinkedHashMap<>(gap);
            metadata.put("seedType", "TRACE_GAP");
            nodes.add(new SeedGraphInput.SeedNodeSpec(
                    gapClientId, null, question, "CUSTOM", stringValue(gap, "reason"),
                    "trace:gap:" + i, metadata, factSheetId));

            for (String relatedStepId : stringList(gap.get("relatedStepIds"))) {
                String relatedClientId = stepClientIds.get(relatedStepId);
                if (relatedClientId != null) {
                    edges.add(new SeedGraphInput.SeedEdgeSpec(
                            null, null, relatedClientId, gapClientId, "USER_DEFINED", 1.0,
                            "Trace step requires clarification", "NEEDS_CLARIFICATION", factSheetId));
                }
            }
        }

        if (!isBlank(input.conclusionNodeId()) && rootClientId != null) {
            edges.add(new SeedGraphInput.SeedEdgeSpec(
                    null, input.conclusionNodeId(), rootClientId, null, "USER_DEFINED", 1.0,
                    "Trace attributes an external conclusion node", "ATTRIBUTES", factSheetId));
        }

        Map<String, Object> result = seedGraph(new SeedGraphInput(
                nodes, edges, factSheetId, Boolean.TRUE.equals(input.rollbackOnFailure())));
        result.put("traceAttributions", attributions.size());
        result.put("traceGaps", gaps.size());
        result.put("traceSeedNodes", nodes.size());
        result.put("traceSeedEdges", edges.size());
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

    /** Input record for graph_reproject. */
    public record ReprojectInput(
            Long factSheetId,
            String mode          // "project" or "full" (default "project")
    ) {
        public ReprojectInput(Long factSheetId) {
            this(factSheetId, "project");
        }
    }

    @Tool(name = "graph_reproject",
          description = "Re-project a fact-sheet's graph into the FOL fact store and optionally trigger a full "
                  + "re-ground cascade so that ask_graph_verify/query/explain reflect the latest graph mutations. "
                  + "mode='project' (default) runs GraphToFactStoreProjector only (fast, no cascade). "
                  + "mode='full' projects first then schedules a full re-ground via the grounding cascade "
                  + "(may take seconds to minutes depending on graph size). "
                  + "Use this after bulk mutations or when ask_graph_verify returns stale results.")
    public Map<String, Object> reprojectGraph(ReprojectInput input) {
        if (input == null || input.factSheetId() == null) {
            return Map.of("error", "factSheetId is required");
        }

        long factSheetId = input.factSheetId();
        String mode = input.mode() != null ? input.mode().toLowerCase() : "project";
        boolean full = "full".equals(mode);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factSheetId", factSheetId);
        result.put("mode", mode);

        // Step 1: project into the fact store (always)
        if (factStoreProjector != null) {
            try {
                int atomsProjected = factStoreProjector.project(factSheetId);
                result.put("atomsProjected", atomsProjected);
                result.put("projected", true);
                log.info("graph_reproject: projected factSheet={} atoms={}", factSheetId, atomsProjected);
            } catch (Exception e) {
                log.error("graph_reproject: projection failed for factSheet={}: {}", factSheetId, e.getMessage(), e);
                result.put("projected", false);
                result.put("projectionError", e.getMessage());
            }
        } else {
            result.put("projected", false);
            result.put("projectionUnavailable", "GraphToFactStoreProjector not wired — projection skipped");
            log.warn("graph_reproject: factStoreProjector is not available for factSheet={}", factSheetId);
        }

        // Step 2: schedule cascade (only for mode=full)
        if (full) {
            if (groundingResetPort != null) {
                try {
                    groundingResetPort.schedule(factSheetId, "graph_reproject:full",
                            GroundingProgressEvent.TRIGGER_CASCADE);
                    result.put("cascadeScheduled", true);
                    log.info("graph_reproject: cascade scheduled for factSheet={}", factSheetId);
                } catch (Exception e) {
                    log.error("graph_reproject: cascade schedule failed for factSheet={}: {}",
                            factSheetId, e.getMessage(), e);
                    result.put("cascadeScheduled", false);
                    result.put("cascadeError", e.getMessage());
                }
            } else {
                result.put("cascadeScheduled", false);
                result.put("cascadeUnavailable",
                        "GroundingResetPort not wired — cascade not scheduled. "
                                + "Fact-store projection was still applied if factStoreProjector was available.");
                log.warn("graph_reproject: groundingResetPort is not available for factSheet={}", factSheetId);
            }
        } else {
            result.put("cascadeScheduled", false);
        }

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SNAPSHOT TOOLS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Input record for graph_snapshot_create. */
    public record SnapshotCreateInput(Long factSheetId, String label) {}

    /** Input record for graph_snapshot_list / graph_snapshot_restore / graph_snapshot_delete. */
    public record SnapshotInput(Long factSheetId, String snapshotId) {}

    @Tool(name = "graph_snapshot_create",
          description = "Create a named .kgraph snapshot of the live graph for a fact sheet — "
                  + "graph versioning / undo checkpoint. "
                  + "The snapshot captures all nodes, edges, embeddings, weights, opinions, and model artifacts. "
                  + "Optional label is appended to the snapshot file name as a human-readable slug. "
                  + "Retention: oldest snapshots are pruned automatically once the per-sheet limit is reached.")
    public Map<String, Object> snapshotCreate(SnapshotCreateInput input) {
        if (input == null || input.factSheetId() == null) {
            return Map.of("error", "factSheetId is required");
        }
        if (snapshotService == null) {
            return Map.of("error", "GraphSnapshotService not available — snapshot feature is not wired");
        }
        if (!snapshotService.isEnabled()) {
            return Map.of("error", "Graph snapshot feature is disabled: kompile.data.dir is not configured");
        }
        try {
            GraphSnapshotService.SnapshotMetadata meta =
                    snapshotService.createSnapshot(input.factSheetId(), input.label());
            return Map.of(
                    "snapshotId", meta.snapshotId(),
                    "factSheetId", meta.factSheetId(),
                    "label", meta.label() == null ? "" : meta.label(),
                    "createdAt", meta.createdAt().toString(),
                    "sizeBytes", meta.sizeBytes());
        } catch (IllegalStateException e) {
            return Map.of("error", e.getMessage());
        } catch (IOException e) {
            log.error("graph_snapshot_create failed for factSheet={}: {}", input.factSheetId(), e.getMessage(), e);
            return Map.of("error", "Snapshot create failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_snapshot_list",
          description = "List all .kgraph snapshots for a fact sheet, newest first. "
                  + "Returns snapshotId (file name), factSheetId, label, createdAt, sizeBytes for each.")
    public Map<String, Object> snapshotList(SnapshotInput input) {
        if (input == null || input.factSheetId() == null) {
            return Map.of("error", "factSheetId is required");
        }
        if (snapshotService == null) {
            return Map.of("error", "GraphSnapshotService not available — snapshot feature is not wired");
        }
        if (!snapshotService.isEnabled()) {
            return Map.of("error", "Graph snapshot feature is disabled: kompile.data.dir is not configured");
        }
        try {
            List<GraphSnapshotService.SnapshotMetadata> snapshots =
                    snapshotService.listSnapshots(input.factSheetId());
            List<Map<String, Object>> items = new ArrayList<>();
            for (GraphSnapshotService.SnapshotMetadata m : snapshots) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("snapshotId", m.snapshotId());
                row.put("factSheetId", m.factSheetId());
                row.put("label", m.label() == null ? "" : m.label());
                row.put("createdAt", m.createdAt().toString());
                row.put("sizeBytes", m.sizeBytes());
                items.add(row);
            }
            return Map.of("factSheetId", input.factSheetId(), "count", items.size(), "snapshots", items);
        } catch (IOException e) {
            log.error("graph_snapshot_list failed for factSheet={}: {}", input.factSheetId(), e.getMessage(), e);
            return Map.of("error", "Snapshot list failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_snapshot_restore",
          description = "Restore a named .kgraph snapshot for a fact sheet (REPLACES the live graph). "
                  + "Before restoring, a 'pre-restore' safety snapshot of the current graph is taken automatically "
                  + "so you can redo if needed. "
                  + "After restore the graph is automatically re-projected into the FOL fact store and a "
                  + "graph-build event fires — the restored graph is immediately reasoning-ready for "
                  + "ask_graph_verify/query/explain and process mining. "
                  + "Use graph_snapshot_list first to find the snapshotId.")
    public Map<String, Object> snapshotRestore(SnapshotInput input) {
        if (input == null || input.factSheetId() == null) {
            return Map.of("error", "factSheetId is required");
        }
        if (input.snapshotId() == null || input.snapshotId().isBlank()) {
            return Map.of("error", "snapshotId is required");
        }
        if (snapshotService == null) {
            return Map.of("error", "GraphSnapshotService not available — snapshot feature is not wired");
        }
        if (!snapshotService.isEnabled()) {
            return Map.of("error", "Graph snapshot feature is disabled: kompile.data.dir is not configured");
        }
        try {
            GraphSnapshotService.RestoreResult result =
                    snapshotService.restoreSnapshot(input.factSheetId(), input.snapshotId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("factSheetId", result.factSheetId());
            out.put("restoredSnapshotId", result.restoredSnapshotId());
            out.put("preRestoreSnapshotId",
                    result.preRestoreSnapshotId() == null ? "" : result.preRestoreSnapshotId());
            if (result.importSummary() != null) {
                out.put("nodes", result.importSummary().nodes());
                out.put("edges", result.importSummary().edges());
                out.put("atoms", result.importSummary().atoms());
                out.put("graphBuildEventPublished", result.importSummary().graphBuildEventPublished());
            }
            return out;
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        } catch (IllegalStateException e) {
            return Map.of("error", e.getMessage());
        } catch (IOException e) {
            log.error("graph_snapshot_restore failed: snapshotId={} factSheet={}: {}",
                    input.snapshotId(), input.factSheetId(), e.getMessage(), e);
            return Map.of("error", "Snapshot restore failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_snapshot_delete",
          description = "Delete a named .kgraph snapshot for a fact sheet. "
                  + "The live graph is not affected — this only removes the archived file. "
                  + "Use graph_snapshot_list to find snapshotId values.")
    public Map<String, Object> snapshotDelete(SnapshotInput input) {
        if (input == null || input.factSheetId() == null) {
            return Map.of("error", "factSheetId is required");
        }
        if (input.snapshotId() == null || input.snapshotId().isBlank()) {
            return Map.of("error", "snapshotId is required");
        }
        if (snapshotService == null) {
            return Map.of("error", "GraphSnapshotService not available — snapshot feature is not wired");
        }
        if (!snapshotService.isEnabled()) {
            return Map.of("error", "Graph snapshot feature is disabled: kompile.data.dir is not configured");
        }
        try {
            boolean deleted = snapshotService.deleteSnapshot(input.factSheetId(), input.snapshotId());
            if (!deleted) {
                return Map.of("error", "Snapshot not found: " + input.snapshotId()
                        + " for factSheet=" + input.factSheetId());
            }
            return Map.of("deleted", true, "snapshotId", input.snapshotId(),
                    "factSheetId", input.factSheetId());
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        } catch (IOException e) {
            log.error("graph_snapshot_delete failed: snapshotId={} factSheet={}: {}",
                    input.snapshotId(), input.factSheetId(), e.getMessage(), e);
            return Map.of("error", "Snapshot delete failed: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> metadata(Map<String, Object> metadata) {
        return metadata == null ? Map.of() : metadata;
    }

    private Map<String, Object> nodeResult(GraphNode node, Long requestedFactSheetId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", node.getNodeId());
        result.put("title", node.getTitle());
        result.put("type", node.getNodeType().name());
        result.put("description", node.getDescription());
        result.put("factSheetId", node.getFactSheetId() != null ? node.getFactSheetId() : requestedFactSheetId);
        return result;
    }

    private EdgeType edgeType(String rawType) {
        EdgeType type = GraphSearchTool.parseEdgeType(rawType);
        return type != null ? type : EdgeType.USER_DEFINED;
    }

    private GraphEdge createEdgeInternal(String sourceNodeId, String targetNodeId,
                                         EdgeType type, double weight, String description,
                                         String relationType, Long factSheetId) {
        String label = blankToNull(relationType);
        if (label != null || factSheetId != null) {
            return graphService.createEdgeWithMetadata(sourceNodeId, targetNodeId, type, weight,
                    label, description, null, null, factSheetId);
        }
        return graphService.createEdge(sourceNodeId, targetNodeId, type, weight, description);
    }

    private Map<String, Object> edgeResult(GraphEdge edge, String sourceNodeId, String targetNodeId,
                                           String relationType, Long factSheetId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("edgeId", edge.getEdgeId());
        result.put("edgeType", edge.getEdgeType().name());
        result.put("weight", edge.getWeight());
        result.put("sourceNodeId", sourceNodeId);
        result.put("targetNodeId", targetNodeId);
        result.put("description", edge.getDescription());
        if (!isBlank(relationType)) {
            result.put("relationType", relationType);
        }
        if (factSheetId != null) {
            result.put("factSheetId", factSheetId);
        }
        return result;
    }

    private Long resolveEdgeFactSheetId(Long explicitFactSheetId, String sourceNodeId, String targetNodeId) {
        if (explicitFactSheetId != null) {
            return explicitFactSheetId;
        }
        Long sourceFactSheetId = nodeFactSheetId(sourceNodeId);
        Long targetFactSheetId = nodeFactSheetId(targetNodeId);
        if (sourceFactSheetId != null && sourceFactSheetId.equals(targetFactSheetId)) {
            return sourceFactSheetId;
        }
        if (sourceFactSheetId != null && targetFactSheetId == null) {
            return sourceFactSheetId;
        }
        if (targetFactSheetId != null && sourceFactSheetId == null) {
            return targetFactSheetId;
        }
        return null;
    }

    private String resolveSeedEndpoint(String nodeId, String clientId, Map<String, String> clientNodeIds) {
        String explicitNodeId = blankToNull(nodeId);
        if (explicitNodeId != null) {
            return explicitNodeId;
        }
        String key = blankToNull(clientId);
        return key == null ? null : clientNodeIds.get(key);
    }

    private Map<String, Object> rollbackSeedWrites(List<String> edgeIds, List<String> nodeIds) {
        int edgesRolledBack = 0;
        int nodesRolledBack = 0;
        List<String> rollbackErrors = new ArrayList<>();

        ListIterator<String> edgeIterator = edgeIds.listIterator(edgeIds.size());
        while (edgeIterator.hasPrevious()) {
            String edgeId = edgeIterator.previous();
            if (isBlank(edgeId)) {
                continue;
            }
            try {
                graphService.deleteEdge(edgeId);
                edgesRolledBack++;
            } catch (Exception e) {
                rollbackErrors.add("edge " + edgeId + ": " + e.getMessage());
            }
        }

        ListIterator<String> nodeIterator = nodeIds.listIterator(nodeIds.size());
        while (nodeIterator.hasPrevious()) {
            String nodeId = nodeIterator.previous();
            if (isBlank(nodeId)) {
                continue;
            }
            try {
                graphService.deleteNode(nodeId);
                nodesRolledBack++;
            } catch (Exception e) {
                rollbackErrors.add("node " + nodeId + ": " + e.getMessage());
            }
        }

        if (edgesRolledBack == 0 && nodesRolledBack == 0 && rollbackErrors.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("edgesRolledBack", edgesRolledBack);
        result.put("nodesRolledBack", nodesRolledBack);
        if (!rollbackErrors.isEmpty()) {
            result.put("errors", rollbackErrors);
        }
        return result;
    }

    private Long resolveSeedEdgeFactSheetId(Long explicitFactSheetId,
                                            String sourceClientId,
                                            String targetClientId,
                                            Map<String, Long> clientFactSheetIds,
                                            String sourceNodeId,
                                            String targetNodeId) {
        if (explicitFactSheetId != null) {
            return explicitFactSheetId;
        }
        Long sourceFactSheetId = clientFactSheetIds.get(blankToNull(sourceClientId));
        Long targetFactSheetId = clientFactSheetIds.get(blankToNull(targetClientId));
        Long inferred = coalesceMatchingScope(sourceFactSheetId, targetFactSheetId);
        return inferred != null ? inferred : resolveEdgeFactSheetId(null, sourceNodeId, targetNodeId);
    }

    private Long coalesceMatchingScope(Long sourceFactSheetId, Long targetFactSheetId) {
        if (sourceFactSheetId != null && sourceFactSheetId.equals(targetFactSheetId)) {
            return sourceFactSheetId;
        }
        if (sourceFactSheetId != null && targetFactSheetId == null) {
            return sourceFactSheetId;
        }
        if (targetFactSheetId != null && sourceFactSheetId == null) {
            return targetFactSheetId;
        }
        return null;
    }

    private String stringValue(Map<String, Object> values, String key) {
        if (values == null || key == null) {
            return null;
        }
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private Double doubleValue(Map<String, Object> values, String key) {
        if (values == null || key == null) {
            return null;
        }
        Object value = values.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Long longValue(Map<String, Object> values, String key) {
        if (values == null || key == null) {
            return null;
        }
        Object value = values.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        copy.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                out.add(copy);
            }
        }
        return out;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                String text = item == null ? null : String.valueOf(item);
                if (!isBlank(text)) {
                    out.add(text);
                }
            }
            return out;
        }
        String text = value == null ? null : String.valueOf(value);
        return isBlank(text) ? List.of() : List.of(text);
    }

    private String evidenceTitle(Map<String, Object> evidenceRef, int index) {
        for (String key : List.of("raw", "findingKey", "atomKey", "sourceId", "documentId", "nodeId")) {
            String value = stringValue(evidenceRef, key);
            if (!isBlank(value)) {
                return GraphSearchTool.truncate(value, 160);
            }
        }
        return "Evidence reference " + index;
    }

    private String evidenceExternalId(String stepId, int refIndex, Map<String, Object> evidenceRef) {
        String source = stringValue(evidenceRef, "sourceId");
        String document = stringValue(evidenceRef, "documentId");
        String finding = stringValue(evidenceRef, "findingKey");
        String suffix = !isBlank(source) ? source : (!isBlank(document) ? document : finding);
        return "trace:evidence:" + stepId + ":" + refIndex + (isBlank(suffix) ? "" : ":" + suffix);
    }

    private Long nodeFactSheetId(String nodeId) {
        if (isBlank(nodeId)) {
            return null;
        }
        try {
            return graphService.getNode(nodeId)
                    .map(GraphNode::getFactSheetId)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Unable to resolve factSheetId for node {}: {}", nodeId, e.getMessage());
            return null;
        }
    }

    private Set<Long> singletonFactSheet(Long factSheetId) {
        return factSheetId == null ? Set.of() : Set.of(factSheetId);
    }

    private Map<Long, Integer> projectFactStores(Set<Long> factSheetIds) {
        if (factStoreProjector == null || factSheetIds == null || factSheetIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> projected = new LinkedHashMap<>();
        for (Long factSheetId : factSheetIds) {
            if (factSheetId == null) {
                continue;
            }
            try {
                projected.put(factSheetId, factStoreProjector.project(factSheetId));
            } catch (RuntimeException e) {
                log.warn("Graph seed wrote factSheet={} but fact-store projection failed: {}",
                        factSheetId, e.toString());
            }
        }
        return projected;
    }

    private void putProjectionResult(Map<String, Object> result, Map<Long, Integer> projectedAtomsByFactSheet) {
        if (result != null && projectedAtomsByFactSheet != null && !projectedAtomsByFactSheet.isEmpty()) {
            result.put("projectedAtomsByFactSheet", projectedAtomsByFactSheet);
        }
    }

    private void invalidateCacheForFactSheet(Long factSheetId) {
        if (factSheetId != null) {
            invalidateCache(factSheetId);
        } else {
            invalidateCacheGlobal();
        }
    }

    private void invalidateCaches(Set<Long> factSheetIds, boolean includeGlobal) {
        if (algorithmService == null) {
            return;
        }
        if (includeGlobal || factSheetIds == null || factSheetIds.isEmpty()) {
            algorithmService.invalidateCache();
            return;
        }
        for (Long factSheetId : factSheetIds) {
            algorithmService.invalidateCache(factSheetId);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

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
