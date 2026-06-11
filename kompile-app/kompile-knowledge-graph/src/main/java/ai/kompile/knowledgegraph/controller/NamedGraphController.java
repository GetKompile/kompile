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
package ai.kompile.knowledgegraph.controller;

import ai.kompile.knowledgegraph.domain.NamedGraph;
import ai.kompile.knowledgegraph.service.NamedGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing hierarchical named knowledge graphs.
 * Provides a "graph of graphs" API enabling users to create, nest,
 * browse, and manipulate named knowledge graphs.
 */
@RestController
@RequestMapping("/api/graphs")
@CrossOrigin
@Slf4j
public class NamedGraphController {

    private final NamedGraphService namedGraphService;

    @Autowired
    public NamedGraphController(NamedGraphService namedGraphService) {
        this.namedGraphService = namedGraphService;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CRUD ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Create a new named graph.
     * Body fields: name (required), description, parentGraphId, factSheetId, ontologyType.
     */
    @PostMapping("")
    public ResponseEntity<NamedGraph> createGraph(@RequestBody CreateGraphRequest request) {
        NamedGraph graph = namedGraphService.createGraph(
            request.name(),
            request.description(),
            request.parentGraphId(),
            request.factSheetId(),
            request.ontologyType()
        );
        return ResponseEntity.ok(graph);
    }

    /**
     * List root graphs or search by name when ?query= is provided.
     */
    @GetMapping("")
    public ResponseEntity<List<NamedGraph>> listGraphs(
            @RequestParam(required = false, name = "query") String query) {
        if (query != null && !query.isBlank()) {
            return ResponseEntity.ok(namedGraphService.searchGraphs(query));
        }
        return ResponseEntity.ok(namedGraphService.getRootGraphs());
    }

    /**
     * Get a single named graph by its external UUID.
     */
    @GetMapping("/{graphId}")
    public ResponseEntity<?> getGraph(@PathVariable("graphId") String graphId) {
        return namedGraphService.getGraph(graphId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Update mutable fields of a named graph.
     * Body fields: name, description, metadataJson (all optional).
     */
    @PatchMapping("/{graphId}")
    public ResponseEntity<NamedGraph> updateGraph(
            @PathVariable("graphId") String graphId,
            @RequestBody UpdateGraphRequest request) {
        NamedGraph graph = namedGraphService.updateGraph(
            graphId,
            request.name(),
            request.description(),
            request.metadataJson()
        );
        return ResponseEntity.ok(graph);
    }

    /**
     * Delete a named graph and all its descendants recursively.
     */
    @DeleteMapping("/{graphId}")
    public ResponseEntity<Void> deleteGraph(@PathVariable("graphId") String graphId) {
        namedGraphService.deleteGraph(graphId);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HIERARCHY NAVIGATION ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get direct children of a named graph.
     */
    @GetMapping("/{graphId}/children")
    public ResponseEntity<List<NamedGraph>> getChildGraphs(
            @PathVariable("graphId") String graphId) {
        return ResponseEntity.ok(namedGraphService.getChildGraphs(graphId));
    }

    /**
     * Get the full nested hierarchy tree rooted at the given graph.
     * Use ?maxDepth= to control depth (default 5).
     */
    @GetMapping("/{graphId}/hierarchy")
    public ResponseEntity<Map<String, Object>> getGraphHierarchy(
            @PathVariable("graphId") String graphId,
            @RequestParam(defaultValue = "5", name = "maxDepth") int maxDepth) {
        return ResponseEntity.ok(namedGraphService.getGraphHierarchy(graphId, maxDepth));
    }

    /**
     * Get the ancestor chain for a named graph, ordered root-first.
     */
    @GetMapping("/{graphId}/ancestors")
    public ResponseEntity<List<NamedGraph>> getAncestors(
            @PathVariable("graphId") String graphId) {
        return ResponseEntity.ok(namedGraphService.getAncestors(graphId));
    }

    /**
     * Move a named graph to a new parent.
     * Body field: newParentGraphId (null or absent to promote to root).
     */
    @PostMapping("/{graphId}/move")
    public ResponseEntity<NamedGraph> moveGraph(
            @PathVariable("graphId") String graphId,
            @RequestBody MoveGraphRequest request) {
        NamedGraph moved = namedGraphService.moveGraph(graphId, request.newParentGraphId());
        return ResponseEntity.ok(moved);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS ENDPOINT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get statistics for a named graph (node count, edge count, depth, descendants, etc.).
     */
    @GetMapping("/{graphId}/statistics")
    public ResponseEntity<Map<String, Object>> getGraphStatistics(
            @PathVariable("graphId") String graphId) {
        return ResponseEntity.ok(namedGraphService.getGraphStatistics(graphId));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE MEMBERSHIP ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Associate an existing KG node with this named graph.
     */
    @PostMapping("/{graphId}/nodes/{nodeId}")
    public ResponseEntity<Map<String, Object>> linkNodeToGraph(
            @PathVariable("graphId") String graphId,
            @PathVariable("nodeId") String nodeId) {
        namedGraphService.linkNodeToGraph(nodeId, graphId);
        return ResponseEntity.ok(Map.of(
            "graphId", graphId,
            "nodeId", nodeId,
            "linked", true
        ));
    }

    /**
     * Remove the association between a KG node and this named graph.
     */
    @DeleteMapping("/{graphId}/nodes/{nodeId}")
    public ResponseEntity<Map<String, Object>> unlinkNodeFromGraph(
            @PathVariable("graphId") String graphId,
            @PathVariable("nodeId") String nodeId) {
        namedGraphService.unlinkNodeFromGraph(nodeId, graphId);
        return ResponseEntity.ok(Map.of(
            "graphId", graphId,
            "nodeId", nodeId,
            "linked", false
        ));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUEST DTOs
    // ═══════════════════════════════════════════════════════════════════════════

    public record CreateGraphRequest(
        String name,
        String description,
        String parentGraphId,
        Long factSheetId,
        String ontologyType
    ) {}

    public record UpdateGraphRequest(
        String name,
        String description,
        String metadataJson
    ) {}

    public record MoveGraphRequest(
        String newParentGraphId
    ) {}
}
