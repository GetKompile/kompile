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
@RequestMapping("/api/graph/{factSheetId}")
public class GraphFocalViewController {

    /** Metadata key written by the ontology-conformance pipeline (boolean). */
    static final String META_CONFORMANT = "ontology.conformant";

    /** Metadata key written by the ontology-conformance pipeline (violation string). */
    static final String META_VIOLATION  = "ontology.violation";

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
            // Collect all node ids in factSheet first (for fast membership checks)
            List<GraphNode> allNodes = graphService.getNodesInFactSheet(factSheetId);
            Set<String> factSheetNodeIds = new HashSet<>();
            Map<String, GraphNode> nodeIndex = new HashMap<>();
            for (GraphNode n : allNodes) {
                factSheetNodeIds.add(n.getNodeId());
                nodeIndex.put(n.getNodeId(), n);
            }

            // BFS frontier
            Set<String> visited     = new LinkedHashSet<>();
            Set<String> frontier    = new LinkedHashSet<>();

            for (String seedId : request.seedNodeIds()) {
                if (factSheetNodeIds.contains(seedId)) {
                    frontier.add(seedId);
                    visited.add(seedId);
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
                        // enqueue neighbors
                        String srcId = edge.getSourceNode() != null ? edge.getSourceNode().getNodeId() : null;
                        String tgtId = edge.getTargetNode() != null ? edge.getTargetNode().getNodeId() : null;
                        if (srcId != null && factSheetNodeIds.contains(srcId) && !visited.contains(srcId)) {
                            visited.add(srcId);
                            nextFrontier.add(srcId);
                        }
                        if (tgtId != null && factSheetNodeIds.contains(tgtId) && !visited.contains(tgtId)) {
                            visited.add(tgtId);
                            nextFrontier.add(tgtId);
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
}
