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
package ai.kompile.knowledgegraph.reasoning.controller;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * REST controller surfacing two graph-visualizer features:
 *
 * <ul>
 *   <li><b>P2 conformance overlay</b> –
 *       {@code GET /api/graph/{factSheetId}/conformance}
 *       returns a per-node conformance summary derived from the
 *       {@code ontology.conformant} / {@code ontology.violation} metadata keys.</li>
 *   <li><b>D2 subgraph / focal-view builder</b> –
 *       {@code POST /api/graph/{factSheetId}/subgraph}
 *       performs a radius-bounded BFS from one or more seed nodes and returns the
 *       resulting subgraph in the same D3 visualization format that
 *       {@link ai.kompile.knowledgegraph.controller.KnowledgeGraphController#getVisualizationData}
 *       produces (maps with {@code nodes}, {@code edges} / {@code links} arrays).</li>
 * </ul>
 *
 * <p>Both endpoints are in package {@code ai.kompile.knowledgegraph.reasoning.controller},
 * which is already registered in {@code GlobalExceptionHandler} via the base
 * {@code ai.kompile.knowledgegraph} scan.</p>
 */
@Slf4j
@RestController
@RequestMapping({
        "/api/graph/{factSheetId}",
        // Compatibility for clients that cached the former double-/api frontend URL.
        "/api/api/graph/{factSheetId}",
        // Compatibility for graph clients whose configured base URL already includes /api/graph.
        "/{factSheetId}"
})
public class GraphFocalViewController {

    /** Metadata key written by the ontology-conformance pipeline (boolean). */
    static final String META_CONFORMANT = "ontology.conformant";

    /** Metadata key written by the ontology-conformance pipeline (violation string). */
    static final String META_VIOLATION  = "ontology.violation";

    private static final List<String> REASONING_SECTIONS =
            List.of("ontology", "psl", "mebn", "provenance", "opinion", "neuralScores");

    private static final com.fasterxml.jackson.databind.ObjectMapper METADATA_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final KnowledgeGraphService graphService;

    public GraphFocalViewController(KnowledgeGraphService graphService) {
        this.graphService = graphService;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // P2 – CONFORMANCE OVERLAY
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns per-ENTITY-node conformance info for the given fact sheet.
     *
     * <p>Response body (array):
     * <pre>
     * [
     *   { "nodeId": "uuid-1", "conformant": true,  "violation": null },
     *   { "nodeId": "uuid-2", "conformant": false, "violation": "Missing required property 'type'" },
     *   { "nodeId": "uuid-3", "conformant": null,  "violation": null }  // untagged
     * ]
     * </pre>
     * Nodes whose metadata carries neither key are reported with {@code conformant=null}
     * so the frontend can colour them distinctly (grey = untagged).</p>
     *
     * @param factSheetId the fact sheet to inspect
     * @return 200 with the conformance list, or 503 on service error
     */
    @GetMapping("/conformance")
    public ResponseEntity<?> getConformanceOverlay(@PathVariable Long factSheetId) {
        log.info("GET /api/graph/{}/conformance", factSheetId);
        try {
            List<GraphNode> entityNodes =
                    graphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY);

            List<Map<String, Object>> result = new ArrayList<>(entityNodes.size());
            for (GraphNode node : entityNodes) {
                Map<String, Object> meta = node.getMetadata();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("nodeId", node.getNodeId());

                if (meta != null && meta.containsKey(META_CONFORMANT)) {
                    Object rawConformant = meta.get(META_CONFORMANT);
                    boolean conformant = Boolean.TRUE.equals(rawConformant)
                            || "true".equalsIgnoreCase(String.valueOf(rawConformant));
                    entry.put("conformant", conformant);
                    entry.put("violation", conformant ? null : meta.get(META_VIOLATION));
                } else {
                    // Untagged — the frontend will render these as grey
                    entry.put("conformant", null);
                    entry.put("violation", null);
                }
                result.add(entry);
            }

            log.debug("Conformance overlay for factSheet={}: {} entity nodes", factSheetId, result.size());
            return ResponseEntity.ok(result);

        } catch (Exception ex) {
            log.error("Conformance overlay failed for factSheetId={}: {}", factSheetId, ex.getMessage(), ex);
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Conformance overlay unavailable: " + ex.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TYPED REASONING LAYERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Return typed reasoning overlays for every valid node and edge in a fact sheet.
     *
     * <p>An empty fact sheet is a normal state and returns {@code 200} with empty
     * {@code nodes}/{@code edges} arrays and zero-valued statistics.</p>
     */
    @GetMapping("/reasoning-layers")
    public ResponseEntity<?> getReasoningLayers(@PathVariable Long factSheetId) {
        log.info("GET /api/graph/{}/reasoning-layers", factSheetId);
        try {
            List<GraphNode> graphNodes = graphService.getNodesInFactSheet(factSheetId);
            List<GraphEdge> graphEdges = graphService.getEdgesInFactSheet(factSheetId);
            if (graphNodes == null) graphNodes = List.of();
            if (graphEdges == null) graphEdges = List.of();

            List<Map<String, Object>> nodes = new ArrayList<>(graphNodes.size());
            for (GraphNode node : graphNodes) {
                if (node != null && normalizeIdentifier(node.getNodeId()) != null) {
                    nodes.add(reasoningNodeToMap(node));
                }
            }

            List<Map<String, Object>> edges = new ArrayList<>(graphEdges.size());
            for (GraphEdge edge : graphEdges) {
                if (edge != null && normalizeIdentifier(edge.getEdgeId()) != null) {
                    edges.add(reasoningEdgeToMap(edge));
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("factSheetId", factSheetId);
            result.put("nodes", nodes);
            result.put("edges", edges);
            result.put("statistics", reasoningStatistics(nodes, edges));
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            log.error("Reasoning layers failed for factSheetId={}: {}", factSheetId, ex.getMessage(), ex);
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Reasoning layers unavailable: " + ex.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // D2 – SUBGRAPH / FOCAL-VIEW BUILDER
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Request body for {@link #buildSubgraph}.
     *
     * @param seedNodeIds     node IDs to start the BFS from (required, non-empty)
     * @param radius          number of hops to expand (1–5, default 2)
     * @param edgeTypes       optional filter – only traverse edges of these types
     *                        (null / empty = traverse all types)
     * @param confidenceFloor only include edges whose {@code weight >= confidenceFloor}
     *                        (0.0 = no floor, default 0.0)
     */
    public record SubgraphRequest(
            List<String> seedNodeIds,
            int          radius,
            List<String> edgeTypes,
            double       confidenceFloor) {
    }

    /**
     * Build and return a bounded subgraph centred on the given seed nodes.
     *
     * <p>The response mirrors the format produced by
     * {@link ai.kompile.knowledgegraph.controller.KnowledgeGraphController#getVisualizationData}:
     * <pre>
     * {
     *   "nodes": [ { "id": "...", "type": "ENTITY", "label": "...", "metadata": {...} }, ... ],
     *   "links": [ { "id": "...", "source": "...", "target": "...", "type": "HIERARCHICAL", "weight": 1.0 }, ... ],
     *   "statistics": { "nodeCount": N, "edgeCount": M, "seedCount": K, "radius": R }
     * }
     * </pre>
     * ({@code edges} is also included as a synonym of {@code links} for tooling compatibility.)</p>
     *
     * @param factSheetId the fact sheet to search within
     * @param request     BFS parameters
     * @return 200 with D3 visualization data, 400 on bad input, 503 on error
     */
    @PostMapping("/subgraph")
    public ResponseEntity<?> buildSubgraph(
            @PathVariable Long factSheetId,
            @RequestBody SubgraphRequest request) {

        log.info("POST /api/graph/{}/subgraph seeds={} radius={} edgeTypes={} confidenceFloor={}",
                factSheetId, request.seedNodeIds(), request.radius(),
                request.edgeTypes(), request.confidenceFloor());

        // ── Input validation ──────────────────────────────────────────────────
        if (request.seedNodeIds() == null || request.seedNodeIds().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "seedNodeIds must not be empty"));
        }
        int radius = Math.min(Math.max(request.radius(), 1), 5);
        Set<String> edgeTypeFilter = request.edgeTypes() != null && !request.edgeTypes().isEmpty()
                ? new HashSet<>(request.edgeTypes())
                : null; // null = accept all types

        try {
            // ── BFS expansion ─────────────────────────────────────────────────
            // Lazy node index — nodes are loaded on first encounter rather than
            // pre-loading the entire fact sheet (which could be tens of thousands
            // of nodes for large graphs). Membership in the fact sheet is confirmed
            // by checking node.getFactSheetId() when a node is first loaded.
            Map<String, GraphNode> nodeIndex = new LinkedHashMap<>();

            // Seed nodes — load lazily and validate fact-sheet membership.
            Set<String> visited  = new LinkedHashSet<>();
            Set<String> frontier = new LinkedHashSet<>();

            for (String seedId : request.seedNodeIds()) {
                Optional<GraphNode> seedOpt = graphService.getNode(seedId);
                if (seedOpt.isPresent()) {
                    GraphNode seed = seedOpt.get();
                    // Accept if the node is unscoped (legacy) or belongs to this fact sheet.
                    if (seed.getFactSheetId() == null || seed.getFactSheetId().equals(factSheetId)) {
                        nodeIndex.put(seedId, seed);
                        frontier.add(seedId);
                        visited.add(seedId);
                    }
                }
            }

            // Collect qualifying edges as we expand
            Set<String> edgeIds = new LinkedHashSet<>();
            List<GraphEdge> qualifyingEdges = new ArrayList<>();

            for (int hop = 0; hop < radius && !frontier.isEmpty(); hop++) {
                Set<String> nextFrontier = new LinkedHashSet<>();
                for (String nodeId : frontier) {
                    List<GraphEdge> edges = graphService.getEdgesForNodeInFactSheet(nodeId, factSheetId);
                    for (GraphEdge edge : edges) {
                        // confidence floor
                        double w = edge.getWeight() != null ? edge.getWeight() : 0.0;
                        if (w < request.confidenceFloor()) continue;
                        // edge type filter
                        if (edgeTypeFilter != null
                                && edge.getEdgeType() != null
                                && !edgeTypeFilter.contains(edge.getEdgeType().name())) {
                            continue;
                        }
                        // collect edge (dedup)
                        if (!edgeIds.contains(edge.getEdgeId())) {
                            edgeIds.add(edge.getEdgeId());
                            qualifyingEdges.add(edge);
                        }
                        // enqueue neighbors — lazy load to confirm fact-sheet membership
                        String srcId = edge.getSourceNode() != null ? edge.getSourceNode().getNodeId() : null;
                        String tgtId = edge.getTargetNode() != null ? edge.getTargetNode().getNodeId() : null;
                        for (String nbId : new String[]{srcId, tgtId}) {
                            if (nbId != null && !visited.contains(nbId)) {
                                Optional<GraphNode> nbOpt = graphService.getNode(nbId);
                                if (nbOpt.isPresent()) {
                                    GraphNode nb = nbOpt.get();
                                    if (nb.getFactSheetId() == null || nb.getFactSheetId().equals(factSheetId)) {
                                        visited.add(nbId);
                                        nextFrontier.add(nbId);
                                        nodeIndex.put(nbId, nb);
                                    }
                                }
                            }
                        }
                    }
                }
                frontier = nextFrontier;
            }

            // ── Serialise in the same format as getVisualizationData ──────────
            List<Map<String, Object>> nodeData = new ArrayList<>();
            for (String nodeId : visited) {
                GraphNode node = nodeIndex.get(nodeId);
                if (node != null) {
                    nodeData.add(nodeToMap(node));
                }
            }

            List<Map<String, Object>> edgeData = new ArrayList<>();
            for (GraphEdge edge : qualifyingEdges) {
                edgeData.add(edgeToMap(edge));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodes", nodeData);
            result.put("links", edgeData);   // front-end uses "links"
            result.put("edges", edgeData);   // synonym for tooling
            result.put("statistics", Map.of(
                    "nodeCount", nodeData.size(),
                    "edgeCount", edgeData.size(),
                    "seedCount", request.seedNodeIds().size(),
                    "radius", radius
            ));

            log.debug("Subgraph for factSheet={}: {} nodes, {} edges (radius={})",
                    factSheetId, nodeData.size(), edgeData.size(), radius);
            return ResponseEntity.ok(result);

        } catch (Exception ex) {
            log.error("Subgraph build failed for factSheetId={}: {}", factSheetId, ex.getMessage(), ex);
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Subgraph build failed: " + ex.getMessage()));
        }
    }

    // ── Private serialization helpers (mirrors KnowledgeGraphServiceImpl) ─────

    private Map<String, Object> nodeToMap(GraphNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", node.getNodeId());
        map.put("type", node.getNodeType() != null ? node.getNodeType().name() : "ENTITY");
        map.put("label", node.getTitle());
        map.put("title", node.getTitle());
        map.put("description", node.getDescription());
        map.put("childCount", node.getChildCount());
        map.put("edgeCount", node.getEdgeCount());
        map.put("metadata", node.getMetadata());
        if (node.getOccurredAt() != null) {
            map.put("occurredAt", node.getOccurredAt().toString());
        }
        return map;
    }

    private Map<String, Object> edgeToMap(GraphEdge edge) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", edge.getEdgeId());
        map.put("source", edge.getSourceNode() != null ? edge.getSourceNode().getNodeId() : null);
        map.put("target", edge.getTargetNode() != null ? edge.getTargetNode().getNodeId() : null);
        map.put("type", edge.getEdgeType() != null ? edge.getEdgeType().name() : "USER_DEFINED");
        map.put("weight", edge.getWeight() != null ? edge.getWeight() : 0.0);
        map.put("label", edge.getLabel());
        if (edge.getOccurredAt() != null) {
            map.put("occurredAt", edge.getOccurredAt().toString());
        }
        return map;
    }

    private Map<String, Object> reasoningNodeToMap(GraphNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeId", normalizeIdentifier(node.getNodeId()));
        map.put("nodeType", node.getNodeType() != null ? node.getNodeType().name() : null);
        map.put("label", node.getTitle());
        putReasoningSections(map, node.getMetadata());
        return map;
    }

    private Map<String, Object> reasoningEdgeToMap(GraphEdge edge) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("edgeId", normalizeIdentifier(edge.getEdgeId()));
        map.put("sourceNodeId", normalizeIdentifier(edge.getSourceNodeId()));
        map.put("targetNodeId", normalizeIdentifier(edge.getTargetNodeId()));
        map.put("edgeType", edge.getEdgeType() != null ? edge.getEdgeType().name() : null);
        map.put("relationType", edge.getRelationType());
        map.put("weight", edge.getWeight());
        putReasoningSections(map, parseMetadata(edge.getMetadataJson()));
        return map;
    }

    private void putReasoningSections(Map<String, Object> target, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return;
        for (String sectionName : REASONING_SECTIONS) {
            Map<String, Object> section = reasoningSection(metadata, sectionName);
            if (section != null && !section.isEmpty()) {
                target.put(sectionName, section);
            }
        }
    }

    private Map<String, Object> reasoningSection(Map<String, Object> metadata, String sectionName) {
        Map<String, Object> section = new LinkedHashMap<>();
        Object nested = metadata.get(sectionName);
        if (nested instanceof Map<?, ?> nestedMap) {
            nestedMap.forEach((key, value) -> {
                if (key != null) section.put(String.valueOf(key), value);
            });
        }

        String prefix = sectionName + ".";
        metadata.forEach((key, value) -> {
            if (key != null && key.startsWith(prefix)) {
                section.put(key.substring(prefix.length()), value);
            }
        });

        if ("ontology".equals(sectionName)) {
            Object violation = section.remove("violation");
            if (violation != null && !section.containsKey("violations")) {
                section.put("violations", List.of(String.valueOf(violation)));
            }
            Object conformant = section.get("conformant");
            if (conformant != null && !(conformant instanceof Boolean)) {
                section.put("conformant", Boolean.parseBoolean(String.valueOf(conformant)));
            }
        }
        return section.isEmpty() ? null : section;
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return Map.of();
        try {
            return METADATA_MAPPER.readValue(
                    metadataJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception ex) {
            log.debug("Ignoring malformed edge metadata while building reasoning layers: {}", ex.getMessage());
            return Map.of();
        }
    }

    private Map<String, Object> reasoningStatistics(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodeCount", nodes.size());
        stats.put("edgeCount", edges.size());
        stats.put("ontologyCount", countSection(nodes, edges, "ontology"));
        stats.put("pslCount", countSection(nodes, edges, "psl"));
        stats.put("mebnCount", countSection(nodes, edges, "mebn"));
        stats.put("provenanceCount", countSection(nodes, edges, "provenance"));
        stats.put("opinionCount", countSection(nodes, edges, "opinion"));
        stats.put("neuralScoreCount", countSection(nodes, edges, "neuralScores"));
        stats.put("typeCandidateCount", countNestedItems(nodes, edges, "ontology", "typeCandidates"));
        stats.put("typeHierarchyCount", countNestedItems(nodes, edges, "ontology", "typeHierarchy"));
        stats.put("inferredRelationCount", countNestedItems(nodes, edges, "ontology", "inferredRelations"));
        return stats;
    }

    private int countSection(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            String sectionName) {
        int count = 0;
        for (Map<String, Object> node : nodes) {
            if (node.get(sectionName) instanceof Map<?, ?>) count++;
        }
        for (Map<String, Object> edge : edges) {
            if (edge.get(sectionName) instanceof Map<?, ?>) count++;
        }
        return count;
    }

    private int countNestedItems(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            String sectionName,
            String fieldName) {
        int count = 0;
        for (Map<String, Object> overlay : nodes) {
            count += nestedListSize(overlay, sectionName, fieldName);
        }
        for (Map<String, Object> overlay : edges) {
            count += nestedListSize(overlay, sectionName, fieldName);
        }
        return count;
    }

    private int nestedListSize(Map<String, Object> overlay, String sectionName, String fieldName) {
        Object section = overlay.get(sectionName);
        if (!(section instanceof Map<?, ?> sectionMap)) return 0;
        Object items = sectionMap.get(fieldName);
        return items instanceof List<?> list ? list.size() : 0;
    }

    private String normalizeIdentifier(Object value) {
        if (value == null) return null;
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
