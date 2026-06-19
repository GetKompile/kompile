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

import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.service.*;
import ai.kompile.knowledgegraph.service.GraphBuildingService.BuildConfig;
import ai.kompile.knowledgegraph.service.GraphBuildingService.BuildStatus;
import ai.kompile.knowledgegraph.service.SourceLinkingService.LinkingConfig;
import ai.kompile.knowledgegraph.service.SourceLinkingService.LinkingResult;
import ai.kompile.knowledgegraph.service.SourceLinkingService.SourceLink;
import ai.kompile.knowledgegraph.service.SourceLinkingService.TermLinkingResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Knowledge Graph operations.
 */
@RestController
@RequestMapping("/api/knowledge-graph")
@Slf4j
public class KnowledgeGraphController {

    private final KnowledgeGraphService graphService;
    private final SourceWeightingService weightingService;
    private final GraphBuildingService graphBuildingService;
    private final SourceLinkingService sourceLinkingService;

    @Autowired
    public KnowledgeGraphController(KnowledgeGraphService graphService,
                                     SourceWeightingService weightingService,
                                     GraphBuildingService graphBuildingService,
                                     SourceLinkingService sourceLinkingService) {
        this.graphService = graphService;
        this.weightingService = weightingService;
        this.graphBuildingService = graphBuildingService;
        this.sourceLinkingService = sourceLinkingService;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/nodes")
    public ResponseEntity<List<GraphNode>> listNodes(
            @RequestParam(required = false, name = "type") String type,
            @RequestParam(required = false, name = "query") String query,
            @RequestParam(defaultValue = "50", name = "limit") int limit) {

        limit = Math.min(limit, 1000);
        List<GraphNode> nodes;
        NodeLevel nodeType = null;
        if (type != null) {
            try {
                nodeType = NodeLevel.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        if (query != null && !query.isBlank()) {
            // Text search: vector-store similarity search filtered by optional type
            nodes = graphService.searchNodes(query, nodeType, limit);
        } else if (nodeType != null) {
            // Type filter only: enumerate directly from in-memory graph (no vector search)
            nodes = graphService.getNodesByType(nodeType, limit);
        } else {
            // No filter: return all nodes from the vector store (source of truth)
            nodes = graphService.getAllNodes(limit);
        }
        return ResponseEntity.ok(nodes);
    }

    @GetMapping("/nodes/{nodeId}")
    public ResponseEntity<?> getNode(@PathVariable("nodeId") String nodeId) {
        return graphService.getNode(nodeId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/nodes/{nodeId}/children")
    public ResponseEntity<List<GraphNode>> getNodeChildren(@PathVariable("nodeId") String nodeId) {
        return ResponseEntity.ok(graphService.getChildren(nodeId));
    }

    @GetMapping("/nodes/{nodeId}/connected")
    public ResponseEntity<List<GraphNode>> getConnectedNodes(
            @PathVariable("nodeId") String nodeId,
            @RequestParam(defaultValue = "2", name = "depth") int depth) {
        depth = Math.min(depth, 5);
        return ResponseEntity.ok(graphService.getConnectedNodes(nodeId, depth));
    }

    @GetMapping("/nodes/{nodeId}/related")
    public ResponseEntity<List<GraphNode>> getRelatedNodes(
            @PathVariable("nodeId") String nodeId,
            @RequestParam(defaultValue = "10", name = "maxResults") int maxResults) {
        return ResponseEntity.ok(graphService.findRelatedNodes(nodeId, maxResults));
    }

    @PostMapping("/nodes")
    public ResponseEntity<GraphNode> createNode(@RequestBody CreateNodeRequest request) {
        GraphNode node = graphService.createNode(
            NodeLevel.valueOf(request.type().toUpperCase()),
            request.externalId(),
            request.title(),
            request.description(),
            request.metadata()
        );
        return ResponseEntity.ok(node);
    }

    @PatchMapping("/nodes/{nodeId}")
    public ResponseEntity<GraphNode> updateNode(
            @PathVariable("nodeId") String nodeId,
            @RequestBody UpdateNodeRequest request) {
        GraphNode node = graphService.updateNode(
            nodeId,
            request.title(),
            request.description(),
            request.metadata()
        );
        return ResponseEntity.ok(node);
    }

    @DeleteMapping("/nodes/{nodeId}")
    public ResponseEntity<Void> deleteNode(@PathVariable("nodeId") String nodeId) {
        graphService.deleteNode(nodeId);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/edges")
    public ResponseEntity<List<GraphEdge>> listEdges(
            @RequestParam(required = false, name = "nodeId") String nodeId,
            @RequestParam(required = false, name = "type") String type,
            @RequestParam(required = false, name = "query") String query,
            @RequestParam(defaultValue = "100", name = "limit") int limit) {

        if (nodeId != null) {
            if (type != null) {
                EdgeType edgeType = EdgeType.valueOf(type.toUpperCase());
                return ResponseEntity.ok(graphService.getEdgesByType(nodeId, edgeType));
            }
            return ResponseEntity.ok(graphService.getEdgesForNode(nodeId));
        }

        // Search across all edges if query is provided
        if (query != null && !query.isBlank()) {
            List<GraphEdge> edges = graphService.searchEdges(query,
                type != null ? EdgeType.valueOf(type.toUpperCase()) : null, limit);
            return ResponseEntity.ok(edges);
        }

        // Return empty if no nodeId or query specified (too many edges otherwise)
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/edges/search")
    public ResponseEntity<List<GraphEdge>> searchEdges(
            @RequestParam(name = "query") String query,
            @RequestParam(required = false, name = "type") String type,
            @RequestParam(defaultValue = "50", name = "limit") int limit) {
        EdgeType edgeType = type != null ? EdgeType.valueOf(type.toUpperCase()) : null;
        List<GraphEdge> edges = graphService.searchEdges(query, edgeType, limit);
        return ResponseEntity.ok(edges);
    }

    @GetMapping("/edges/{edgeId}")
    public ResponseEntity<?> getEdge(@PathVariable("edgeId") String edgeId) {
        return graphService.getEdge(edgeId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/edges")
    public ResponseEntity<GraphEdge> createEdge(@RequestBody CreateEdgeRequest request) {
        GraphEdge edge = graphService.createEdge(
            request.sourceNodeId(),
            request.targetNodeId(),
            EdgeType.valueOf(request.edgeType().toUpperCase()),
            request.weight(),
            request.description()
        );
        return ResponseEntity.ok(edge);
    }

    @PatchMapping("/edges/{edgeId}")
    public ResponseEntity<GraphEdge> updateEdge(
            @PathVariable("edgeId") String edgeId,
            @RequestBody UpdateEdgeRequest request) {
        GraphEdge edge = graphService.updateEdge(edgeId, request.weight(), request.description());
        return ResponseEntity.ok(edge);
    }

    @DeleteMapping("/edges/{edgeId}")
    public ResponseEntity<Void> deleteEdge(@PathVariable("edgeId") String edgeId) {
        graphService.deleteEdge(edgeId);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SOURCE WEIGHT ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/weights")
    public ResponseEntity<List<SourceWeight>> listWeights(
            @RequestParam(required = false, name = "sourceId") String sourceId) {
        if (sourceId != null) {
            return ResponseEntity.ok(weightingService.getAllWeightsForSource(sourceId));
        }
        // Return all topic weights
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/weights/{sourceId}")
    public ResponseEntity<SourceWeight> getWeight(
            @PathVariable("sourceId") String sourceId,
            @RequestParam(required = false, name = "topic") String topic) {
        return ResponseEntity.ok(weightingService.getSourceWeight(sourceId, topic));
    }

    @PostMapping("/weights")
    public ResponseEntity<SourceWeight> setWeight(@RequestBody SetWeightRequest request) {
        SourceWeight weight = weightingService.setSourceWeight(
            request.sourceNodeId(),
            request.baseWeight(),
            request.topic(),
            request.userId()
        );
        return ResponseEntity.ok(weight);
    }

    @DeleteMapping("/weights/{sourceId}")
    public ResponseEntity<Void> removeWeight(
            @PathVariable("sourceId") String sourceId,
            @RequestParam(required = false, name = "topic") String topic,
            @RequestParam(required = false, name = "userId") String userId) {
        weightingService.removeWeight(sourceId, topic, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/weights/preview")
    public ResponseEntity<Map<String, Object>> previewWeightedSearch(
            @RequestBody WeightedSearchRequest request) {
        return ResponseEntity.ok(weightingService.previewWeightedSearch(
            request.query(),
            request.maxResults() != null ? request.maxResults() : 10
        ));
    }

    @PostMapping("/weights/feedback")
    public ResponseEntity<Void> submitFeedback(@RequestBody FeedbackRequest request) {
        weightingService.updateQualityScore(request.sourceNodeId(), request.wasHelpful());
        return ResponseEntity.ok().build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOPIC ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/topics")
    public ResponseEntity<List<String>> getTopics() {
        return ResponseEntity.ok(weightingService.getTopics());
    }

    @PostMapping("/topics/{sourceId}")
    public ResponseEntity<Void> assignTopic(
            @PathVariable("sourceId") String sourceId,
            @RequestBody AssignTopicRequest request) {
        weightingService.assignTopic(sourceId, request.topic());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/topics/{topic}/sources")
    public ResponseEntity<List<String>> getSourcesForTopic(@PathVariable("topic") String topic) {
        return ResponseEntity.ok(weightingService.getSourcesForTopic(topic));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS & VISUALIZATION ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        return ResponseEntity.ok(graphService.getGraphStatistics());
    }

    @GetMapping("/visualization")
    public ResponseEntity<Map<String, Object>> getVisualizationData(
            @RequestParam(required = false, name = "rootNodeId") String rootNodeId,
            @RequestParam(defaultValue = "2", name = "depth") int depth,
            @RequestParam(defaultValue = "100", name = "maxNodes") int maxNodes,
            @RequestParam(required = false, name = "from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false, name = "to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        if (from != null && to != null) {
            return ResponseEntity.ok(graphService.getVisualizationDataInTimeRange(from, to, maxNodes));
        }
        return ResponseEntity.ok(graphService.getVisualizationData(rootNodeId, depth, maxNodes));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEMPORAL ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/temporal/bounds")
    public ResponseEntity<Map<String, Object>> getTemporalBounds() {
        return ResponseEntity.ok(graphService.getTemporalBounds());
    }

    @GetMapping("/temporal/edges")
    public ResponseEntity<List<GraphEdge>> getEdgesInTimeRange(
            @RequestParam(name = "from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(name = "to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "100", name = "limit") int limit) {
        return ResponseEntity.ok(graphService.searchEdgesByTimeRange(from, to, limit));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH BUILDING ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    @PostMapping("/build")
    public ResponseEntity<BuildStatus> buildGraphFromAllSources(
            @RequestBody(required = false) BuildGraphRequest request) {
        BuildConfig config = request != null ? request.toConfig() : BuildConfig.defaults();
        BuildStatus status = graphBuildingService.buildGraphFromAllSources(config);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/build/sources")
    public ResponseEntity<BuildStatus> buildGraphFromSources(
            @RequestBody BuildFromSourcesRequest request) {
        BuildConfig config = request.config() != null ? request.config().toConfig() : BuildConfig.defaults();
        BuildStatus status = graphBuildingService.buildGraphFromSources(request.sourceIds(), config);
        return ResponseEntity.ok(status);
    }

    @GetMapping("/build/status/{jobId}")
    public ResponseEntity<BuildStatus> getBuildStatus(@PathVariable("jobId") String jobId) {
        BuildStatus status = graphBuildingService.getBuildStatus(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    @PostMapping("/build/cancel/{jobId}")
    public ResponseEntity<Map<String, Object>> cancelBuild(@PathVariable("jobId") String jobId) {
        boolean cancelled = graphBuildingService.cancelBuild(jobId);
        return ResponseEntity.ok(Map.of(
            "jobId", jobId,
            "cancelled", cancelled
        ));
    }

    @GetMapping("/build/jobs")
    public ResponseEntity<List<BuildStatus>> getRunningJobs() {
        return ResponseEntity.ok(graphBuildingService.getRunningJobs());
    }

    @GetMapping("/build/statistics")
    public ResponseEntity<Map<String, Object>> getBuildStatistics() {
        return ResponseEntity.ok(graphBuildingService.getBuildStatistics());
    }

    @PostMapping("/build/shared-entity-edges")
    public ResponseEntity<Map<String, Object>> createSharedEntityEdges(
            @RequestParam(defaultValue = "1", name = "minSharedEntities") int minSharedEntities) {
        int edgesCreated = graphBuildingService.createSharedEntityEdges(minSharedEntities);
        return ResponseEntity.ok(Map.of(
            "edgesCreated", edgesCreated,
            "minSharedEntities", minSharedEntities
        ));
    }

    @DeleteMapping("/build/clear")
    public ResponseEntity<Map<String, Object>> clearGraph() {
        graphBuildingService.clearGraph();
        return ResponseEntity.ok(Map.of(
            "message", "Graph cleared successfully"
        ));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SOURCE LINKING ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    @PostMapping("/links/sources")
    public ResponseEntity<LinkingResult> linkSourcesBySharedConcepts(
            @RequestParam(required = false, name = "factSheetId") Long factSheetId,
            @RequestBody(required = false) LinkingConfigRequest config) {
        LinkingConfig linkingConfig = config != null ? config.toConfig() : LinkingConfig.defaults();
        LinkingResult result = sourceLinkingService.linkSourcesBySharedConcepts(factSheetId, linkingConfig);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/links/sources/similarity")
    public ResponseEntity<LinkingResult> linkSourcesByEmbeddingSimilarity(
            @RequestParam(required = false, name = "factSheetId") Long factSheetId,
            @RequestBody(required = false) LinkingConfigRequest config) {
        LinkingConfig linkingConfig = config != null ? config.toConfig() : LinkingConfig.defaults();
        LinkingResult result = sourceLinkingService.linkSourcesByEmbeddingSimilarity(factSheetId, linkingConfig);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/links/sources/all")
    public ResponseEntity<LinkingResult> linkAllSources(
            @RequestParam(required = false, name = "factSheetId") Long factSheetId,
            @RequestBody(required = false) LinkingConfigRequest config) {
        LinkingConfig linkingConfig = config != null ? config.toConfig() : LinkingConfig.defaults();
        LinkingResult result = sourceLinkingService.linkAllSources(factSheetId, linkingConfig);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/links/sources")
    public ResponseEntity<List<SourceLink>> getSourceLinks(
            @RequestParam(required = false, name = "factSheetId") Long factSheetId) {
        List<SourceLink> links = sourceLinkingService.getSourceLinks(factSheetId);
        return ResponseEntity.ok(links);
    }

    @GetMapping("/links/sources/{sourceNodeId}")
    public ResponseEntity<List<SourceLink>> getLinksForSource(
            @PathVariable("sourceNodeId") String sourceNodeId,
            @RequestParam(required = false, name = "factSheetId") Long factSheetId) {
        List<SourceLink> links = sourceLinkingService.getLinksForSource(factSheetId, sourceNodeId);
        return ResponseEntity.ok(links);
    }

    @PostMapping("/links/manual")
    public ResponseEntity<SourceLink> createManualLink(@RequestBody ManualLinkRequest request) {
        SourceLink link = sourceLinkingService.createManualLink(
            request.factSheetId(),
            request.sourceNodeId1(),
            request.sourceNodeId2(),
            request.description(),
            request.strength() != null ? request.strength() : 0.7
        );
        return ResponseEntity.ok(link);
    }

    @DeleteMapping("/links")
    public ResponseEntity<Map<String, Object>> removeLink(
            @RequestParam(name = "factSheetId") Long factSheetId,
            @RequestParam(name = "sourceNodeId1") String sourceNodeId1,
            @RequestParam(name = "sourceNodeId2") String sourceNodeId2) {
        boolean removed = sourceLinkingService.removeLink(factSheetId, sourceNodeId1, sourceNodeId2);
        return ResponseEntity.ok(Map.of(
            "removed", removed,
            "sourceNodeId1", sourceNodeId1,
            "sourceNodeId2", sourceNodeId2
        ));
    }

    @GetMapping("/links/connectivity")
    public ResponseEntity<Map<String, Object>> getConnectivitySummary(
            @RequestParam(required = false, name = "factSheetId") Long factSheetId) {
        Map<String, Object> summary = sourceLinkingService.getConnectivitySummary(factSheetId);
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/links/isolated")
    public ResponseEntity<List<String>> findIsolatedSources(
            @RequestParam(required = false, name = "factSheetId") Long factSheetId) {
        List<String> isolated = sourceLinkingService.findIsolatedSources(factSheetId);
        return ResponseEntity.ok(isolated);
    }

    @GetMapping("/links/most-connected")
    public ResponseEntity<List<Map<String, Object>>> findMostConnectedSources(
            @RequestParam(required = false, name = "factSheetId") Long factSheetId,
            @RequestParam(defaultValue = "10", name = "limit") int limit) {
        List<Map<String, Object>> connected = sourceLinkingService.findMostConnectedSources(factSheetId, limit);
        return ResponseEntity.ok(connected);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TERM-BASED LINKING ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    @PostMapping("/links/term")
    public ResponseEntity<TermLinkingResult> linkNodesByTerm(@RequestBody LinkByTermRequest request) {
        EdgeType edgeType = request.edgeType() != null ?
            EdgeType.valueOf(request.edgeType().toUpperCase()) : null;
        TermLinkingResult result = sourceLinkingService.linkNodesByTerm(
            request.term(),
            request.factSheetId(),
            edgeType,
            request.weight()
        );
        return ResponseEntity.ok(result);
    }

    @PostMapping("/links/terms")
    public ResponseEntity<List<TermLinkingResult>> linkNodesByTerms(@RequestBody LinkByTermsRequest request) {
        EdgeType edgeType = request.edgeType() != null ?
            EdgeType.valueOf(request.edgeType().toUpperCase()) : null;
        List<TermLinkingResult> results = sourceLinkingService.linkNodesByTerms(
            request.terms(),
            request.factSheetId(),
            edgeType,
            request.weight()
        );
        return ResponseEntity.ok(results);
    }

    @PostMapping("/links/term-relation")
    public ResponseEntity<SourceLink> createTermBasedRelation(@RequestBody TermRelationRequest request) {
        SourceLink link = sourceLinkingService.createTermBasedRelation(
            request.sourceNodeId(),
            request.targetNodeId(),
            request.relationTerm(),
            request.description(),
            request.weight() != null ? request.weight() : 0.7,
            request.bidirectional() != null ? request.bidirectional() : true
        );
        return ResponseEntity.ok(link);
    }

    @GetMapping("/terms/nodes")
    public ResponseEntity<List<String>> findNodesWithTerm(
            @RequestParam(name = "term") String term,
            @RequestParam(name = "factSheetId", required = false) Long factSheetId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        List<String> nodeIds = sourceLinkingService.findNodesWithTerm(term, factSheetId, limit);
        return ResponseEntity.ok(nodeIds);
    }

    @GetMapping("/terms")
    public ResponseEntity<List<Map<String, Object>>> getAllTerms(
            @RequestParam(required = false, name = "factSheetId") Long factSheetId,
            @RequestParam(defaultValue = "100", name = "limit") int limit) {
        List<Map<String, Object>> terms = sourceLinkingService.getAllTerms(factSheetId, limit);
        return ResponseEntity.ok(terms);
    }

    @GetMapping("/terms/shared")
    public ResponseEntity<List<String>> getSharedTerms(
            @RequestParam(name = "nodeId1") String nodeId1,
            @RequestParam(name = "nodeId2") String nodeId2,
            @RequestParam(name = "factSheetId", required = false) Long factSheetId) {
        List<String> sharedTerms = sourceLinkingService.getSharedTerms(nodeId1, nodeId2, factSheetId);
        return ResponseEntity.ok(sharedTerms);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUEST DTOs
    // ═══════════════════════════════════════════════════════════════════════════

    public record CreateNodeRequest(
        String type,
        String externalId,
        String title,
        String description,
        Map<String, Object> metadata
    ) {}

    public record UpdateNodeRequest(
        String title,
        String description,
        Map<String, Object> metadata
    ) {}

    public record CreateEdgeRequest(
        String sourceNodeId,
        String targetNodeId,
        String edgeType,
        Double weight,
        String description
    ) {}

    public record UpdateEdgeRequest(
        Double weight,
        String description
    ) {}

    public record SetWeightRequest(
        String sourceNodeId,
        Double baseWeight,
        String topic,
        String userId
    ) {}

    public record WeightedSearchRequest(
        String query,
        Integer maxResults
    ) {}

    public record FeedbackRequest(
        String sourceNodeId,
        boolean wasHelpful
    ) {}

    public record AssignTopicRequest(
        String topic
    ) {}

    public record BuildGraphRequest(
        Double minEntityConfidence,
        Integer minSharedEntitiesForEdge,
        Boolean includeHierarchicalEdges,
        Boolean computeSimilarityEdges,
        Double similarityThreshold,
        List<String> entityTypesToExtract,
        Integer maxEntitiesPerDocument,
        Boolean asyncProcessing
    ) {
        public BuildConfig toConfig() {
            return new BuildConfig(
                minEntityConfidence != null ? minEntityConfidence : 0.6,
                minSharedEntitiesForEdge != null ? minSharedEntitiesForEdge : 1,
                includeHierarchicalEdges != null ? includeHierarchicalEdges : true,
                computeSimilarityEdges != null ? computeSimilarityEdges : true,
                similarityThreshold != null ? similarityThreshold : 0.7,
                entityTypesToExtract != null ? entityTypesToExtract :
                    List.of("PERSON", "ORGANIZATION", "LOCATION", "TECHNICAL_TERM", "CONCEPT"),
                maxEntitiesPerDocument != null ? maxEntitiesPerDocument : 100,
                asyncProcessing != null ? asyncProcessing : true
            );
        }
    }

    public record BuildFromSourcesRequest(
        List<String> sourceIds,
        BuildGraphRequest config
    ) {}

    public record LinkingConfigRequest(
        Integer minSharedConcepts,
        Double minSimilarity,
        Double minConceptOverlap,
        Boolean createBidirectional,
        Boolean useEmbeddingSimilarity,
        Boolean useConceptOverlap,
        Boolean createCrossSourceEdges
    ) {
        public LinkingConfig toConfig() {
            return new LinkingConfig(
                minSharedConcepts != null ? minSharedConcepts : 3,
                minSimilarity != null ? minSimilarity : 0.7,
                minConceptOverlap != null ? minConceptOverlap : 0.2,
                createBidirectional != null ? createBidirectional : true,
                useEmbeddingSimilarity != null ? useEmbeddingSimilarity : true,
                useConceptOverlap != null ? useConceptOverlap : true,
                createCrossSourceEdges != null ? createCrossSourceEdges : true
            );
        }
    }

    public record ManualLinkRequest(
        Long factSheetId,
        String sourceNodeId1,
        String sourceNodeId2,
        String description,
        Double strength
    ) {}

    public record LinkByTermRequest(
        String term,
        Long factSheetId,
        String edgeType,
        Double weight
    ) {}

    public record LinkByTermsRequest(
        List<String> terms,
        Long factSheetId,
        String edgeType,
        Double weight
    ) {}

    public record TermRelationRequest(
        String sourceNodeId,
        String targetNodeId,
        String relationTerm,
        String description,
        Double weight,
        Boolean bidirectional
    ) {}
}
