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

import ai.kompile.knowledgegraph.service.*;
import ai.kompile.knowledgegraph.service.FactSheetGraphService.*;
import ai.kompile.knowledgegraph.service.SourceLinkingService.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for fact sheet-scoped knowledge graph operations.
 */
@RestController
@RequestMapping("/api/fact-sheets/{factSheetId}/graph")
@Slf4j
public class FactSheetGraphController {

    private final FactSheetGraphService factSheetGraphService;
    private final SourceLinkingService sourceLinkingService;

    @Autowired
    public FactSheetGraphController(
            FactSheetGraphService factSheetGraphService,
            SourceLinkingService sourceLinkingService) {
        this.factSheetGraphService = factSheetGraphService;
        this.sourceLinkingService = sourceLinkingService;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH BUILDING ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Build knowledge graph from indexed documents for a fact sheet.
     */
    @PostMapping("/build")
    public ResponseEntity<GraphBuildStatus> buildGraph(
            @PathVariable("factSheetId") Long factSheetId,
            @RequestBody(required = false) BuildRequest request) {

        GraphBuildConfig config = request != null && request.config() != null ?
            request.config() : GraphBuildConfig.defaults();

        GraphBuildStatus status = factSheetGraphService.buildGraphFromIndex(factSheetId, config);
        return ResponseEntity.ok(status);
    }

    /**
     * Get the status of a graph building job.
     */
    @GetMapping("/build/status/{jobId}")
    public ResponseEntity<GraphBuildStatus> getBuildStatus(
            @PathVariable("factSheetId") Long factSheetId,
            @PathVariable("jobId") String jobId) {

        GraphBuildStatus status = factSheetGraphService.getBuildStatus(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /**
     * Cancel a running graph building job.
     */
    @PostMapping("/build/cancel/{jobId}")
    public ResponseEntity<Map<String, Object>> cancelBuild(
            @PathVariable("factSheetId") Long factSheetId,
            @PathVariable("jobId") String jobId) {

        boolean cancelled = factSheetGraphService.cancelBuild(jobId);
        return ResponseEntity.ok(Map.of(
            "jobId", jobId,
            "cancelled", cancelled
        ));
    }

    /**
     * Get all running build jobs.
     */
    @GetMapping("/build/running")
    public ResponseEntity<List<GraphBuildStatus>> getRunningJobs(
            @PathVariable("factSheetId") Long factSheetId) {
        return ResponseEntity.ok(factSheetGraphService.getRunningJobs());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VISUALIZATION ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get D3-compatible visualization data for the fact sheet's graph.
     */
    @GetMapping("/visualization")
    public ResponseEntity<GraphVisualizationData> getVisualization(
            @PathVariable("factSheetId") Long factSheetId,
            @RequestParam(defaultValue = "500", name = "maxNodes") int maxNodes,
            @RequestParam(defaultValue = "1000", name = "maxEdges") int maxEdges) {

        GraphVisualizationData data = factSheetGraphService.getVisualizationData(
            factSheetId, maxNodes, maxEdges);
        return ResponseEntity.ok(data);
    }

    /**
     * Get graph statistics for a fact sheet.
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics(
            @PathVariable("factSheetId") Long factSheetId) {
        return ResponseEntity.ok(factSheetGraphService.getGraphStatistics(factSheetId));
    }

    /**
     * Clear the graph for a fact sheet.
     */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clearGraph(
            @PathVariable("factSheetId") Long factSheetId) {

        int deleted = factSheetGraphService.clearGraph(factSheetId);
        return ResponseEntity.ok(Map.of(
            "factSheetId", factSheetId,
            "entitiesDeleted", deleted
        ));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONCEPT ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get top concepts in the fact sheet's graph.
     */
    @GetMapping("/concepts/top")
    public ResponseEntity<List<Map<String, Object>>> getTopConcepts(
            @PathVariable("factSheetId") Long factSheetId,
            @RequestParam(defaultValue = "20", name = "limit") int limit) {

        return ResponseEntity.ok(factSheetGraphService.getTopConcepts(factSheetId, limit));
    }

    /**
     * Rebuild concept-based edges.
     */
    @PostMapping("/concepts/rebuild-edges")
    public ResponseEntity<Map<String, Object>> rebuildConceptEdges(
            @PathVariable("factSheetId") Long factSheetId,
            @RequestParam(defaultValue = "2", name = "minSharedConcepts") int minSharedConcepts) {

        int edgesCreated = factSheetGraphService.rebuildConceptEdges(factSheetId, minSharedConcepts);
        return ResponseEntity.ok(Map.of(
            "factSheetId", factSheetId,
            "edgesCreated", edgesCreated
        ));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SEARCH ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Search nodes in the graph.
     */
    @GetMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> searchNodes(
            @PathVariable("factSheetId") Long factSheetId,
            @RequestParam(name = "query") String query,
            @RequestParam(defaultValue = "20", name = "limit") int limit) {

        return ResponseEntity.ok(factSheetGraphService.searchNodes(factSheetId, query, limit));
    }

    /**
     * Get documents related to a specific document by shared concepts.
     */
    @GetMapping("/documents/{documentNodeId}/related")
    public ResponseEntity<List<Map<String, Object>>> getRelatedDocuments(
            @PathVariable("factSheetId") Long factSheetId,
            @PathVariable("documentNodeId") String documentNodeId,
            @RequestParam(defaultValue = "2", name = "minSharedConcepts") int minSharedConcepts,
            @RequestParam(defaultValue = "10", name = "limit") int limit) {

        return ResponseEntity.ok(factSheetGraphService.getRelatedDocuments(
            factSheetId, documentNodeId, minSharedConcepts, limit));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SOURCE LINKING ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Link sources based on shared concepts.
     */
    @PostMapping("/sources/link")
    public ResponseEntity<LinkingResult> linkSources(
            @PathVariable("factSheetId") Long factSheetId,
            @RequestBody(required = false) LinkingConfigRequest request) {

        LinkingConfig config = request != null && request.config() != null ?
            request.config() : LinkingConfig.defaults();

        LinkingResult result = sourceLinkingService.linkAllSources(factSheetId, config);
        return ResponseEntity.ok(result);
    }

    /**
     * Get existing source links.
     */
    @GetMapping("/sources/links")
    public ResponseEntity<List<SourceLink>> getSourceLinks(
            @PathVariable("factSheetId") Long factSheetId) {
        return ResponseEntity.ok(sourceLinkingService.getSourceLinks(factSheetId));
    }

    /**
     * Get links for a specific source.
     */
    @GetMapping("/sources/{sourceNodeId}/links")
    public ResponseEntity<List<SourceLink>> getLinksForSource(
            @PathVariable("factSheetId") Long factSheetId,
            @PathVariable("sourceNodeId") String sourceNodeId) {
        return ResponseEntity.ok(sourceLinkingService.getLinksForSource(factSheetId, sourceNodeId));
    }

    /**
     * Create a manual link between two sources.
     */
    @PostMapping("/sources/links")
    public ResponseEntity<SourceLink> createManualLink(
            @PathVariable("factSheetId") Long factSheetId,
            @RequestBody CreateLinkRequest request) {

        SourceLink link = sourceLinkingService.createManualLink(
            factSheetId,
            request.sourceNodeId1(),
            request.sourceNodeId2(),
            request.description(),
            request.strength() != null ? request.strength() : 0.5
        );
        return ResponseEntity.ok(link);
    }

    /**
     * Remove a link between two sources.
     */
    @DeleteMapping("/sources/links")
    public ResponseEntity<Map<String, Object>> removeLink(
            @PathVariable("factSheetId") Long factSheetId,
            @RequestParam(name = "sourceNodeId1") String sourceNodeId1,
            @RequestParam(name = "sourceNodeId2") String sourceNodeId2) {

        boolean removed = sourceLinkingService.removeLink(factSheetId, sourceNodeId1, sourceNodeId2);
        return ResponseEntity.ok(Map.of(
            "removed", removed,
            "sourceNodeId1", sourceNodeId1,
            "sourceNodeId2", sourceNodeId2
        ));
    }

    /**
     * Get source connectivity summary.
     */
    @GetMapping("/sources/connectivity")
    public ResponseEntity<Map<String, Object>> getConnectivitySummary(
            @PathVariable("factSheetId") Long factSheetId) {
        return ResponseEntity.ok(sourceLinkingService.getConnectivitySummary(factSheetId));
    }

    /**
     * Find isolated sources (no links).
     */
    @GetMapping("/sources/isolated")
    public ResponseEntity<List<String>> findIsolatedSources(
            @PathVariable("factSheetId") Long factSheetId) {
        return ResponseEntity.ok(sourceLinkingService.findIsolatedSources(factSheetId));
    }

    /**
     * Find most connected sources.
     */
    @GetMapping("/sources/most-connected")
    public ResponseEntity<List<Map<String, Object>>> findMostConnectedSources(
            @PathVariable("factSheetId") Long factSheetId,
            @RequestParam(defaultValue = "10", name = "limit") int limit) {
        return ResponseEntity.ok(sourceLinkingService.findMostConnectedSources(factSheetId, limit));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUEST/RESPONSE DTOs
    // ═══════════════════════════════════════════════════════════════════════════

    record BuildRequest(GraphBuildConfig config) {}

    record LinkingConfigRequest(LinkingConfig config) {}

    record CreateLinkRequest(
        String sourceNodeId1,
        String sourceNodeId2,
        String description,
        Double strength
    ) {}
}
