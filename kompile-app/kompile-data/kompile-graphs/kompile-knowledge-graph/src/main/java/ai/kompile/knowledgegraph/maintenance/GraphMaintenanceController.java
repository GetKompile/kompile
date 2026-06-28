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
package ai.kompile.knowledgegraph.maintenance;

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.GraphDataPatchService;
import ai.kompile.knowledgegraph.service.GraphDataPatchService.PatchRequest;
import ai.kompile.knowledgegraph.service.GraphDataPatchService.PatchResult;
import ai.kompile.knowledgegraph.service.GraphEdgeComputationService;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * REST controller for practical graph maintenance operations.
 * Provides health diagnostics, pruning, validation, relabeling,
 * bulk delete, edge cleanup, and metadata patching.
 *
 * <p>Mapped at {@code /api/graph/maintenance/} — consumed by
 * the CLI ({@code kompile graph maintain}) and the Angular
 * graph-maintenance-panel component.
 */
@RestController
@RequestMapping("/api/graph/maintenance")
public class GraphMaintenanceController {

    private static final Logger log = LoggerFactory.getLogger(GraphMaintenanceController.class);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private  KnowledgeGraphService knowledgeGraphService;
    private  GraphDataPatchService patchService;
    private  ObjectMapper objectMapper;

    @Autowired(required = false)
    private GraphEdgeComputationService graphEdgeComputationService;

    // @Autowired forces Spring to use this constructor for injection. Without it, the no-arg
    // constructor below (kept for GraalVM native-image proxying) wins on the JVM and every
    // dependency — including patchService — is left null, NPE-ing every endpoint at runtime.
    @Autowired
    public GraphMaintenanceController(KnowledgeGraphService knowledgeGraphService,
                                      GraphDataPatchService patchService,
                                      ObjectMapper objectMapper) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.patchService = patchService;
        this.objectMapper = objectMapper;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected GraphMaintenanceController() {}


    // ── Health ────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health(
            @RequestParam(required = false) Long factSheetId) {
        List<GraphNode> nodes = factSheetId != null
                ? knowledgeGraphService.getNodesInFactSheet(factSheetId)
                : knowledgeGraphService.getAllNodes(Integer.MAX_VALUE);
        List<GraphEdge> edges = factSheetId != null
                ? knowledgeGraphService.getEdgesInFactSheet(factSheetId)
                : knowledgeGraphService.searchEdges(null, null, Integer.MAX_VALUE);

        Map<String, Long> nodeCountsByType = nodes.stream()
                .collect(Collectors.groupingBy(n -> n.getNodeType().name(), Collectors.counting()));
        Map<String, Long> edgeCountsByType = edges.stream()
                .collect(Collectors.groupingBy(e -> e.getEdgeType().name(), Collectors.counting()));

        Map<String, Long> entityTypeDist = new LinkedHashMap<>();
        long orphanEntityCount = 0;
        long blankTitleCount = 0;
        long lowConfidenceCount = 0;

        for (GraphNode n : nodes) {
            if (n.getTitle() == null || n.getTitle().isBlank()) blankTitleCount++;
            if (n.getConfidence() != null && n.getConfidence() < 0.3) lowConfidenceCount++;
            if (n.getNodeType() == NodeLevel.ENTITY) {
                String entityType = extractEntityType(n);
                entityTypeDist.merge(entityType != null ? entityType : "UNKNOWN", 1L, Long::sum);
                if (knowledgeGraphService.getEdgesForNode(n.getNodeId()).isEmpty()) orphanEntityCount++;
            }
        }

        // Use nodeId (String) for membership checks — the @Primary matrix store has no JPA Long id
        Set<String> nodeIdSet = nodes.stream().map(GraphNode::getNodeId).collect(Collectors.toSet());
        long danglingEdgeCount = edges.stream()
                .filter(e -> e.getSourceNode() == null || e.getTargetNode() == null
                        || !nodeIdSet.contains(e.getSourceNode().getNodeId())
                        || !nodeIdSet.contains(e.getTargetNode().getNodeId()))
                .count();
        long weakEdgeCount = edges.stream()
                .filter(e -> e.getWeight() != null && e.getWeight() < 0.3
                        && e.getEdgeType() != EdgeType.HIERARCHICAL
                        && e.getEdgeType() != EdgeType.USER_DEFINED)
                .count();

        Set<String> edgeSigs = new HashSet<>();
        long duplicateEdgeCount = 0;
        for (GraphEdge e : edges) {
            String srcId = e.getSourceNode() != null ? e.getSourceNode().getNodeId() : "?";
            String tgtId = e.getTargetNode() != null ? e.getTargetNode().getNodeId() : "?";
            String sig = srcId + "->" + tgtId + ":" + e.getEdgeType();
            if (!edgeSigs.add(sig)) duplicateEdgeCount++;
        }

        List<String> issues = new ArrayList<>();
        if (orphanEntityCount > 0) issues.add(orphanEntityCount + " orphan entities (no edges)");
        if (blankTitleCount > 0) issues.add(blankTitleCount + " nodes with blank titles");
        if (lowConfidenceCount > 0) issues.add(lowConfidenceCount + " low-confidence nodes (<0.3)");
        if (danglingEdgeCount > 0) issues.add(danglingEdgeCount + " dangling edges");
        if (weakEdgeCount > 0) issues.add(weakEdgeCount + " weak edges (<0.3 weight)");
        if (duplicateEdgeCount > 0) issues.add(duplicateEdgeCount + " duplicate edges");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("factSheetId", factSheetId);
        report.put("totalNodes", nodes.size());
        report.put("totalEdges", edges.size());
        report.put("nodeCountsByType", nodeCountsByType);
        report.put("edgeCountsByType", edgeCountsByType);
        report.put("entityTypeDistribution", entityTypeDist);
        report.put("orphanEntityCount", orphanEntityCount);
        report.put("blankTitleCount", blankTitleCount);
        report.put("lowConfidenceCount", lowConfidenceCount);
        report.put("danglingEdgeCount", danglingEdgeCount);
        report.put("weakEdgeCount", weakEdgeCount);
        report.put("duplicateEdgeCount", duplicateEdgeCount);
        report.put("issues", issues);
        return ResponseEntity.ok(report);
    }

    // ── Prune ────────────────────────────────────────────────────────────────

    @PostMapping("/prune")

    public ResponseEntity<Map<String, Object>> prune(
            @RequestParam(required = false) Long factSheetId,
            @RequestBody PruneRequest req) {
        return doPrune(factSheetId, req, false);
    }

    @PostMapping("/prune/preview")
    public ResponseEntity<Map<String, Object>> prunePreview(
            @RequestParam(required = false) Long factSheetId,
            @RequestBody PruneRequest req) {
        return doPrune(factSheetId, req, true);
    }

    private ResponseEntity<Map<String, Object>> doPrune(Long factSheetId, PruneRequest req, boolean dryRun) {
        double confThreshold = req.confidenceThreshold() != null ? req.confidenceThreshold() : 0.3;
        double edgeWeightThreshold = req.edgeWeightThreshold() != null ? req.edgeWeightThreshold() : 0.3;

        List<GraphNode> nodes = factSheetId != null
                ? knowledgeGraphService.getNodesInFactSheet(factSheetId)
                : knowledgeGraphService.getAllNodes(Integer.MAX_VALUE);
        List<GraphEdge> edges = factSheetId != null
                ? knowledgeGraphService.getEdgesInFactSheet(factSheetId)
                : knowledgeGraphService.searchEdges(null, null, Integer.MAX_VALUE);

        List<Map<String, String>> details = new ArrayList<>();
        List<GraphNode> nodesToDelete = new ArrayList<>();
        List<GraphEdge> edgesToDelete = new ArrayList<>();

        for (GraphNode n : nodes) {
            if (n.getNodeType() == NodeLevel.ENTITY) {
                boolean orphan = knowledgeGraphService.getEdgesForNode(n.getNodeId()).isEmpty();
                if (orphan) {
                    nodesToDelete.add(n);
                    details.add(Map.of("nodeId", n.getNodeId(), "title", deriveTitle(n),
                            "reason", "orphan_entity", "info", "Entity with zero edges"));
                } else if (n.getConfidence() != null && n.getConfidence() < confThreshold) {
                    nodesToDelete.add(n);
                    details.add(Map.of("nodeId", n.getNodeId(), "title", deriveTitle(n),
                            "reason", "low_confidence", "info", "Confidence " + n.getConfidence()));
                }
            }
            if (n.getTitle() == null || n.getTitle().isBlank()) {
                if (knowledgeGraphService.getEdgesForNode(n.getNodeId()).isEmpty()) {
                    nodesToDelete.add(n);
                    details.add(Map.of("nodeId", n.getNodeId(), "title", "(blank)",
                            "reason", "blank_orphan", "info", "Blank title, no edges"));
                }
            }
        }

        for (GraphEdge e : edges) {
            if (e.getWeight() != null && e.getWeight() < edgeWeightThreshold
                    && e.getEdgeType() != EdgeType.HIERARCHICAL
                    && e.getEdgeType() != EdgeType.USER_DEFINED) {
                edgesToDelete.add(e);
            }
        }

        if (!dryRun) {
            for (GraphEdge e : edgesToDelete) {
                if (e.getEdgeId() != null) knowledgeGraphService.deleteEdge(e.getEdgeId());
            }
            for (GraphNode n : nodesToDelete) {
                // Remove all edges for this node before deleting it
                for (GraphEdge e : knowledgeGraphService.getEdgesForNode(n.getNodeId())) {
                    if (e.getEdgeId() != null) knowledgeGraphService.deleteEdge(e.getEdgeId());
                }
                knowledgeGraphService.deleteNode(n.getNodeId());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("factSheetId", factSheetId);
        result.put("nodesPruned", nodesToDelete.size());
        result.put("edgesPruned", edgesToDelete.size());
        result.put("details", details);
        return ResponseEntity.ok(result);
    }

    // ── Validate ─────────────────────────────────────────────────────────────

    @PostMapping("/validate")

    public ResponseEntity<Map<String, Object>> validate(
            @RequestParam(required = false) Long factSheetId,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return doValidate(factSheetId, dryRun);
    }

    @PostMapping("/validate/preview")
    public ResponseEntity<Map<String, Object>> validatePreview(
            @RequestParam(required = false) Long factSheetId) {
        return doValidate(factSheetId, true);
    }

    private ResponseEntity<Map<String, Object>> doValidate(Long factSheetId, boolean dryRun) {
        List<GraphNode> nodes = factSheetId != null
                ? knowledgeGraphService.getNodesInFactSheet(factSheetId)
                : knowledgeGraphService.getAllNodes(Integer.MAX_VALUE);
        List<GraphEdge> edges = factSheetId != null
                ? knowledgeGraphService.getEdgesInFactSheet(factSheetId)
                : knowledgeGraphService.searchEdges(null, null, Integer.MAX_VALUE);

        // Use nodeId (String) — the @Primary matrix store has no JPA Long id
        Set<String> nodeIdSet = nodes.stream().map(GraphNode::getNodeId).collect(Collectors.toSet());
        List<Map<String, String>> details = new ArrayList<>();

        for (GraphNode n : nodes) {
            if (n.getTitle() == null || n.getTitle().isBlank()) {
                details.add(Map.of("id", n.getNodeId(), "action", "blank_title",
                        "description", "Node has blank/null title (" + n.getNodeType() + ")"));
                if (!dryRun) {
                    n.setTitle(deriveTitle(n));
                    knowledgeGraphService.saveNode(n);
                }
            }
        }

        for (GraphEdge e : edges) {
            String srcId = e.getSourceNode() != null ? e.getSourceNode().getNodeId() : null;
            String tgtId = e.getTargetNode() != null ? e.getTargetNode().getNodeId() : null;
            if (srcId == null || tgtId == null
                    || !nodeIdSet.contains(srcId) || !nodeIdSet.contains(tgtId)) {
                details.add(Map.of("id", e.getEdgeId() != null ? e.getEdgeId() : "?",
                        "action", "dangling_edge",
                        "description", "Edge references missing node"));
                if (!dryRun && e.getEdgeId() != null) knowledgeGraphService.deleteEdge(e.getEdgeId());
            }
        }

        Set<String> edgeSigs = new HashSet<>();
        for (GraphEdge e : edges) {
            String srcId = e.getSourceNode() != null ? e.getSourceNode().getNodeId() : "?";
            String tgtId = e.getTargetNode() != null ? e.getTargetNode().getNodeId() : "?";
            String sig = srcId + "->" + tgtId + ":" + e.getEdgeType();
            if (!edgeSigs.add(sig)) {
                details.add(Map.of("id", e.getEdgeId() != null ? e.getEdgeId() : "?",
                        "action", "duplicate_edge",
                        "description", "Duplicate edge " + e.getEdgeType() + " between same nodes"));
                if (!dryRun && e.getEdgeId() != null) knowledgeGraphService.deleteEdge(e.getEdgeId());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("factSheetId", factSheetId);
        result.put("issuesFound", details.size());
        result.put("details", details);
        return ResponseEntity.ok(result);
    }

    // ── Relabel ──────────────────────────────────────────────────────────────

    @PostMapping("/relabel")

    public ResponseEntity<Map<String, Object>> relabel(
            @RequestParam(required = false) Long factSheetId,
            @RequestBody RelabelRequest req) {
        return doRelabel(factSheetId, req, false);
    }

    @PostMapping("/relabel/preview")
    public ResponseEntity<Map<String, Object>> relabelPreview(
            @RequestParam(required = false) Long factSheetId,
            @RequestBody RelabelRequest req) {
        return doRelabel(factSheetId, req, true);
    }

    private ResponseEntity<Map<String, Object>> doRelabel(Long factSheetId, RelabelRequest req, boolean dryRun) {
        List<GraphNode> nodes = factSheetId != null
                ? knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY)
                : knowledgeGraphService.getNodesByType(NodeLevel.ENTITY);

        Pattern titlePattern = req.titlePattern() != null
                ? Pattern.compile(req.titlePattern(), Pattern.CASE_INSENSITIVE)
                : null;

        List<Map<String, String>> details = new ArrayList<>();
        for (GraphNode n : nodes) {
            String entityType = extractEntityType(n);
            if (!req.fromType().equalsIgnoreCase(entityType)) continue;
            if (titlePattern != null && (n.getTitle() == null || !titlePattern.matcher(n.getTitle()).find())) continue;

            details.add(Map.of("nodeId", n.getNodeId(), "title", deriveTitle(n),
                    "oldType", entityType != null ? entityType : "", "newType", req.toType()));
            if (!dryRun) {
                setEntityType(n, req.toType());
                knowledgeGraphService.saveNode(n);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("factSheetId", factSheetId);
        result.put("fromType", req.fromType());
        result.put("toType", req.toType());
        result.put("relabeledCount", details.size());
        result.put("details", details);
        return ResponseEntity.ok(result);
    }

    // ── Normalize entity types (clean junk entity_type values in place) ────────

    @PostMapping("/normalize-entity-types")
    public ResponseEntity<Map<String, Object>> normalizeEntityTypes(
            @RequestParam(required = false) Long factSheetId) {
        return doNormalizeEntityTypes(factSheetId, false);
    }

    @PostMapping("/normalize-entity-types/preview")
    public ResponseEntity<Map<String, Object>> normalizeEntityTypesPreview(
            @RequestParam(required = false) Long factSheetId) {
        return doNormalizeEntityTypes(factSheetId, true);
    }

    /**
     * One-time data migration: rewrites already-persisted node {@code entity_type} values through
     * {@link GraphConstants#normalizeEntityType} so junk like {@code entity_entity_number} (crawled
     * before the source-side normalization) becomes {@code entity_number} in the STORED graph — not
     * just in the process-view display. Idempotent (re-running normalizes nothing new); the
     * {@code /preview} variant reports what would change without writing. Scope to one fact sheet via
     * {@code ?factSheetId=}, else scans all nodes.
     */
    private ResponseEntity<Map<String, Object>> doNormalizeEntityTypes(Long factSheetId, boolean dryRun) {
        List<GraphNode> nodes = factSheetId != null
                ? knowledgeGraphService.getNodesInFactSheet(factSheetId)
                : knowledgeGraphService.getAllNodes(100_000);

        int normalizedCount = 0;
        List<Map<String, String>> sample = new ArrayList<>();
        for (GraphNode n : nodes) {
            String current = extractEntityType(n);
            if (current == null || current.isBlank()) continue;
            String normalized = GraphConstants.normalizeEntityType(current);
            if (normalized.equals(current)) continue; // already clean — skip
            normalizedCount++;
            if (sample.size() < 200) {
                sample.add(Map.of("nodeId", n.getNodeId(), "title", deriveTitle(n),
                        "oldType", current, "newType", normalized));
            }
            if (!dryRun) {
                setEntityType(n, normalized);
                knowledgeGraphService.saveNode(n);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("factSheetId", factSheetId);
        result.put("scanned", nodes.size());
        result.put("normalizedCount", normalizedCount);
        result.put("sample", sample);
        return ResponseEntity.ok(result);
    }

    // ── Labels ───────────────────────────────────────────────────────────────

    @GetMapping("/labels")
    public ResponseEntity<List<Map<String, Object>>> labels(
            @RequestParam(required = false) Long factSheetId) {
        List<GraphNode> entities = factSheetId != null
                ? knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY)
                : knowledgeGraphService.getNodesByType(NodeLevel.ENTITY);

        Map<String, Long> counts = new LinkedHashMap<>();
        for (GraphNode n : entities) {
            String et = extractEntityType(n);
            counts.merge(et != null ? et : "UNKNOWN", 1L, Long::sum);
        }

        List<Map<String, Object>> result = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("label", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // ── Bulk Delete ──────────────────────────────────────────────────────────

    @PostMapping("/bulk-delete")

    public ResponseEntity<Map<String, Object>> bulkDelete(
            @RequestParam(required = false) Long factSheetId,
            @RequestBody BulkDeleteRequest req) {
        return doBulkDelete(factSheetId, req, false);
    }

    @PostMapping("/bulk-delete/preview")
    public ResponseEntity<Map<String, Object>> bulkDeletePreview(
            @RequestParam(required = false) Long factSheetId,
            @RequestBody BulkDeleteRequest req) {
        return doBulkDelete(factSheetId, req, true);
    }

    private ResponseEntity<Map<String, Object>> doBulkDelete(Long factSheetId, BulkDeleteRequest req, boolean dryRun) {
        List<GraphNode> nodes = factSheetId != null
                ? knowledgeGraphService.getNodesInFactSheet(factSheetId)
                : knowledgeGraphService.getAllNodes(Integer.MAX_VALUE);

        Pattern titlePattern = req.titlePattern() != null
                ? Pattern.compile(req.titlePattern(), Pattern.CASE_INSENSITIVE)
                : null;

        List<Map<String, String>> details = new ArrayList<>();
        for (GraphNode n : nodes) {
            if (!matchesBulkDelete(n, req, titlePattern)) continue;
            String et = extractEntityType(n);
            details.add(Map.of("nodeId", n.getNodeId(), "title", deriveTitle(n),
                    "nodeType", n.getNodeType().name(), "entityType", et != null ? et : ""));
            if (!dryRun) {
                for (GraphEdge e : knowledgeGraphService.getEdgesForNode(n.getNodeId())) {
                    if (e.getEdgeId() != null) knowledgeGraphService.deleteEdge(e.getEdgeId());
                }
                knowledgeGraphService.deleteNode(n.getNodeId());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("factSheetId", factSheetId);
        result.put("deletedCount", details.size());
        result.put("details", details);
        return ResponseEntity.ok(result);
    }

    // ── Edge Cleanup ─────────────────────────────────────────────────────────

    @PostMapping("/edge-cleanup")

    public ResponseEntity<Map<String, Object>> edgeCleanup(
            @RequestParam(required = false) Long factSheetId,
            @RequestBody EdgeCleanupRequest req) {
        return doEdgeCleanup(factSheetId, req, false);
    }

    @PostMapping("/edge-cleanup/preview")
    public ResponseEntity<Map<String, Object>> edgeCleanupPreview(
            @RequestParam(required = false) Long factSheetId,
            @RequestBody EdgeCleanupRequest req) {
        return doEdgeCleanup(factSheetId, req, true);
    }

    private ResponseEntity<Map<String, Object>> doEdgeCleanup(Long factSheetId, EdgeCleanupRequest req, boolean dryRun) {
        List<GraphNode> nodes = factSheetId != null
                ? knowledgeGraphService.getNodesInFactSheet(factSheetId)
                : knowledgeGraphService.getAllNodes(Integer.MAX_VALUE);
        List<GraphEdge> edges = factSheetId != null
                ? knowledgeGraphService.getEdgesInFactSheet(factSheetId)
                : knowledgeGraphService.searchEdges(null, null, Integer.MAX_VALUE);

        // Use nodeId (String) — the @Primary matrix store has no JPA Long id
        Set<String> nodeIdSet = nodes.stream().map(GraphNode::getNodeId).collect(Collectors.toSet());
        Set<EdgeType> allowedTypes = req.edgeTypes() != null
                ? req.edgeTypes().stream().map(EdgeType::valueOf).collect(Collectors.toSet())
                : null;

        int danglingRemoved = 0, duplicatesRemoved = 0, weakRemoved = 0;
        double minWeight = req.minWeight() != null ? req.minWeight() : 0.3;

        if (req.removeDangling() != null && req.removeDangling()) {
            for (GraphEdge e : edges) {
                String srcId = e.getSourceNode() != null ? e.getSourceNode().getNodeId() : null;
                String tgtId = e.getTargetNode() != null ? e.getTargetNode().getNodeId() : null;
                if (srcId == null || tgtId == null
                        || !nodeIdSet.contains(srcId) || !nodeIdSet.contains(tgtId)) {
                    danglingRemoved++;
                    if (!dryRun && e.getEdgeId() != null) knowledgeGraphService.deleteEdge(e.getEdgeId());
                }
            }
        }

        if (req.removeDuplicates() != null && req.removeDuplicates()) {
            Set<String> sigs = new HashSet<>();
            for (GraphEdge e : edges) {
                String srcId = e.getSourceNode() != null ? e.getSourceNode().getNodeId() : "?";
                String tgtId = e.getTargetNode() != null ? e.getTargetNode().getNodeId() : "?";
                String sig = srcId + "->" + tgtId + ":" + e.getEdgeType();
                if (!sigs.add(sig)) {
                    duplicatesRemoved++;
                    if (!dryRun && e.getEdgeId() != null) knowledgeGraphService.deleteEdge(e.getEdgeId());
                }
            }
        }

        for (GraphEdge e : edges) {
            if (allowedTypes != null && !allowedTypes.contains(e.getEdgeType())) continue;
            if (e.getEdgeType() == EdgeType.HIERARCHICAL || e.getEdgeType() == EdgeType.USER_DEFINED) continue;
            if (e.getWeight() != null && e.getWeight() < minWeight) {
                weakRemoved++;
                if (!dryRun && e.getEdgeId() != null) knowledgeGraphService.deleteEdge(e.getEdgeId());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("factSheetId", factSheetId);
        result.put("danglingRemoved", danglingRemoved);
        result.put("duplicatesRemoved", duplicatesRemoved);
        result.put("weakRemoved", weakRemoved);
        return ResponseEntity.ok(result);
    }

    // ── Patch ────────────────────────────────────────────────────────────────

    @PostMapping("/patch")
    public ResponseEntity<PatchResult> patch(@RequestBody PatchRequest request) {
        return ResponseEntity.ok(patchService.patchNodeMetadata(request));
    }

    @PostMapping("/patch/preview")
    public ResponseEntity<PatchResult> patchPreview(@RequestBody PatchRequest request) {
        PatchRequest dryRunReq = new PatchRequest(
                request.factSheetId(), request.allowGlobal(), true,
                request.limit(), request.rules());
        return ResponseEntity.ok(patchService.patchNodeMetadata(dryRunReq));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String extractEntityType(GraphNode node) {
        if (node.getMetadataJson() == null || node.getMetadataJson().isBlank()) return null;
        try {
            Map<String, Object> meta = objectMapper.readValue(node.getMetadataJson(), MAP_TYPE);
            for (String key : List.of("entity_type", "entityType", "entity_category", "entityCategory")) {
                Object val = meta.get(key);
                if (val instanceof String s && !s.isBlank()) return s;
            }
        } catch (Exception e) {
            log.debug("Failed to extract entity_type from metadata for node {}: {}", node.getNodeId(), e.getMessage());
        }
        return null;
    }

    private void setEntityType(GraphNode node, String newType) {
        try {
            LinkedHashMap<String, Object> meta = node.getMetadataJson() != null
                    ? objectMapper.readValue(node.getMetadataJson(), MAP_TYPE)
                    : new LinkedHashMap<>();
            meta.put("entity_type", newType);
            meta.remove("entityType");
            node.setMetadataJson(objectMapper.writeValueAsString(meta));
        } catch (Exception e) {
            log.warn("Failed to set entity_type '{}' on node {}: {}", newType, node.getNodeId(), e.getMessage());
        }
    }

    private String deriveTitle(GraphNode node) {
        if (node.getTitle() != null && !node.getTitle().isBlank()) return node.getTitle();
        if (node.getExternalId() != null) return node.getExternalId();
        return node.getNodeId();
    }

    private boolean matchesBulkDelete(GraphNode n, BulkDeleteRequest req, Pattern titlePattern) {
        if (req.nodeType() != null) {
            try {
                if (n.getNodeType() != NodeLevel.valueOf(req.nodeType())) return false;
            } catch (IllegalArgumentException e) { return false; }
        }
        if (req.entityType() != null) {
            String et = extractEntityType(n);
            if (et == null || !et.equalsIgnoreCase(req.entityType())) return false;
        }
        if (req.maxConfidence() != null) {
            if (n.getConfidence() == null || n.getConfidence() > req.maxConfidence()) return false;
        }
        if (req.orphansOnly() != null && req.orphansOnly()) {
            // edgeCount may be null on matrix-store nodes; fall back to live edge query
            boolean hasEdges = (n.getEdgeCount() != null && n.getEdgeCount() > 0)
                    || !knowledgeGraphService.getEdgesForNode(n.getNodeId()).isEmpty();
            if (hasEdges) return false;
        }
        if (titlePattern != null) {
            if (n.getTitle() == null || !titlePattern.matcher(n.getTitle()).find()) return false;
        }
        return true;
    }

    // ── Edge Computation ─────────────────────────────────────────────────────

    /**
     * Re-run edge computation on an existing fact-sheet graph without re-crawling.
     *
     * <p>Runs all three edge passes in order:</p>
     * <ol>
     *   <li>Shared-entity edges (embedding-free)</li>
     *   <li>Name-based cross-document entity resolution (embedding-free)</li>
     *   <li>Embedding-similarity edges (only when the embedding model is available)</li>
     * </ol>
     *
     * <p>All passes are idempotent — existing edges are never duplicated.  Use this endpoint
     * to fill in embedding-similarity edges after the GPU/embedding subprocess recovers,
     * or to link cross-document entities by name after a new crawl completes.</p>
     *
     * <p>Example: {@code POST /api/graph/maintenance/compute-edges?factSheetId=1}</p>
     */
    @PostMapping("/compute-edges")
    public ResponseEntity<Map<String, Object>> computeEdges(
            @RequestParam(required = false) Long factSheetId) {
        if (graphEdgeComputationService == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "GraphEdgeComputationService not available"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factSheetId", factSheetId);

        // Pass 1: shared-entity edges (embedding-free)
        try {
            graphEdgeComputationService.computeSharedEntityEdges(factSheetId, 1);
            result.put("sharedEntityEdges", "ok");
        } catch (Exception e) {
            log.warn("compute-edges: shared-entity pass failed (factSheetId={}): {}", factSheetId, e.getMessage());
            result.put("sharedEntityEdges", "failed: " + e.getMessage());
        }

        // Pass 2: name-based cross-document entity resolution (embedding-free)
        try {
            graphEdgeComputationService.computeNameBasedCrossDocEdges(factSheetId);
            result.put("nameBasedCrossDocEdges", "ok");
        } catch (Exception e) {
            log.warn("compute-edges: name-based cross-doc pass failed (factSheetId={}): {}", factSheetId, e.getMessage());
            result.put("nameBasedCrossDocEdges", "failed: " + e.getMessage());
        }

        // Pass 3: embedding-similarity edges (requires embedding model)
        Map<String, Object> status = graphEdgeComputationService.getComputationStatus();
        boolean embeddingAvailable = Boolean.TRUE.equals(status.get("embeddingModelAvailable"));
        if (embeddingAvailable) {
            try {
                graphEdgeComputationService.computeEmbeddingSimilarityEdges(factSheetId, 0.7, 10);
                result.put("embeddingSimilarityEdges", "ok");
            } catch (Exception e) {
                log.warn("compute-edges: similarity pass failed (factSheetId={}): {}", factSheetId, e.getMessage());
                result.put("embeddingSimilarityEdges", "failed: " + e.getMessage());
            }
        } else {
            result.put("embeddingSimilarityEdges", "skipped (embedding model not available — re-run when GPU is ready)");
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Collapse the legacy O(k²) clique cross-doc SHARED_ENTITY edges and recompute them as a STAR
     * (the fixed topology) — the main lever for shrinking the graph (703k clique edges of 1.27M).
     * Numbers-first: call with {@code dryRun=true} to see how many would change before committing.
     *
     * <p>Example: {@code POST /api/graph/maintenance/rebuild-cross-doc-edges?factSheetId=1&dryRun=true}</p>
     */
    @PostMapping("/rebuild-cross-doc-edges")
    public ResponseEntity<Map<String, Object>> rebuildCrossDocEdges(
            @RequestParam(required = false) Long factSheetId,
            @RequestParam(required = false, defaultValue = "false") boolean dryRun) {
        if (graphEdgeComputationService == null) {
            return ResponseEntity.status(503).body(Map.of("error", "GraphEdgeComputationService not available"));
        }
        try {
            return ResponseEntity.ok(graphEdgeComputationService.rebuildCrossDocEdges(factSheetId, dryRun));
        } catch (Exception e) {
            log.warn("rebuild-cross-doc-edges failed (factSheetId={}): {}", factSheetId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage() == null ? "failed" : e.getMessage()));
        }
    }

    // ── Request DTOs ─────────────────────────────────────────────────────────

    public record PruneRequest(
            Boolean dryRun,
            Double confidenceThreshold,
            Double edgeWeightThreshold
    ) {}

    public record RelabelRequest(
            Boolean dryRun,
            String fromType,
            String toType,
            String titlePattern
    ) {}

    public record BulkDeleteRequest(
            Boolean dryRun,
            String nodeType,
            String entityType,
            Double maxConfidence,
            Boolean orphansOnly,
            String titlePattern
    ) {}

    public record EdgeCleanupRequest(
            Boolean dryRun,
            Boolean removeDangling,
            Boolean removeDuplicates,
            Double minWeight,
            List<String> edgeTypes
    ) {}
}
