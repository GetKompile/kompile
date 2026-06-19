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
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.GraphDataPatchService;
import ai.kompile.knowledgegraph.service.GraphDataPatchService.PatchRequest;
import ai.kompile.knowledgegraph.service.GraphDataPatchService.PatchResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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

    private  GraphNodeRepository nodeRepository;
    private  GraphEdgeRepository edgeRepository;
    private  GraphDataPatchService patchService;
    private  ObjectMapper objectMapper;

    public GraphMaintenanceController(GraphNodeRepository nodeRepository,
                                      GraphEdgeRepository edgeRepository,
                                      GraphDataPatchService patchService,
                                      ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
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
                ? nodeRepository.findByFactSheetId(factSheetId)
                : nodeRepository.findAll();
        List<GraphEdge> edges = factSheetId != null
                ? edgeRepository.findByFactSheetId(factSheetId)
                : edgeRepository.findAll();

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
                if (n.getEdgeCount() == null || n.getEdgeCount() == 0) orphanEntityCount++;
            }
        }

        Set<Long> nodeIds = nodes.stream().map(GraphNode::getId).collect(Collectors.toSet());
        long danglingEdgeCount = edges.stream()
                .filter(e -> !nodeIds.contains(e.getSourceNode().getId())
                        || !nodeIds.contains(e.getTargetNode().getId()))
                .count();
        long weakEdgeCount = edges.stream()
                .filter(e -> e.getWeight() != null && e.getWeight() < 0.3
                        && e.getEdgeType() != EdgeType.HIERARCHICAL
                        && e.getEdgeType() != EdgeType.USER_DEFINED)
                .count();

        Set<String> edgeSigs = new HashSet<>();
        long duplicateEdgeCount = 0;
        for (GraphEdge e : edges) {
            String sig = e.getSourceNode().getId() + "->" + e.getTargetNode().getId() + ":" + e.getEdgeType();
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
    @Transactional
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
                ? nodeRepository.findByFactSheetId(factSheetId)
                : nodeRepository.findAll();
        List<GraphEdge> edges = factSheetId != null
                ? edgeRepository.findByFactSheetId(factSheetId)
                : edgeRepository.findAll();

        List<Map<String, String>> details = new ArrayList<>();
        List<GraphNode> nodesToDelete = new ArrayList<>();
        List<GraphEdge> edgesToDelete = new ArrayList<>();

        for (GraphNode n : nodes) {
            if (n.getNodeType() == NodeLevel.ENTITY) {
                if (n.getEdgeCount() == null || n.getEdgeCount() == 0) {
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
                if (n.getEdgeCount() == null || n.getEdgeCount() == 0) {
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
            for (GraphEdge e : edgesToDelete) edgeRepository.delete(e);
            for (GraphNode n : nodesToDelete) {
                edgeRepository.deleteAllEdgesForNode(n);
                nodeRepository.delete(n);
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
    @Transactional
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
                ? nodeRepository.findByFactSheetId(factSheetId)
                : nodeRepository.findAll();
        List<GraphEdge> edges = factSheetId != null
                ? edgeRepository.findByFactSheetId(factSheetId)
                : edgeRepository.findAll();

        Set<Long> nodeIds = nodes.stream().map(GraphNode::getId).collect(Collectors.toSet());
        List<Map<String, String>> details = new ArrayList<>();

        for (GraphNode n : nodes) {
            if (n.getTitle() == null || n.getTitle().isBlank()) {
                details.add(Map.of("id", n.getNodeId(), "action", "blank_title",
                        "description", "Node has blank/null title (" + n.getNodeType() + ")"));
                if (!dryRun) {
                    n.setTitle(deriveTitle(n));
                    nodeRepository.save(n);
                }
            }
        }

        for (GraphEdge e : edges) {
            if (!nodeIds.contains(e.getSourceNode().getId())
                    || !nodeIds.contains(e.getTargetNode().getId())) {
                details.add(Map.of("id", e.getEdgeId(), "action", "dangling_edge",
                        "description", "Edge references missing node"));
                if (!dryRun) edgeRepository.delete(e);
            }
        }

        Set<String> edgeSigs = new HashSet<>();
        for (GraphEdge e : edges) {
            String sig = e.getSourceNode().getId() + "->" + e.getTargetNode().getId() + ":" + e.getEdgeType();
            if (!edgeSigs.add(sig)) {
                details.add(Map.of("id", e.getEdgeId(), "action", "duplicate_edge",
                        "description", "Duplicate edge " + e.getEdgeType() + " between same nodes"));
                if (!dryRun) edgeRepository.delete(e);
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
    @Transactional
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
                ? nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY)
                : nodeRepository.findByNodeType(NodeLevel.ENTITY);

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
                nodeRepository.save(n);
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

    // ── Labels ───────────────────────────────────────────────────────────────

    @GetMapping("/labels")
    public ResponseEntity<List<Map<String, Object>>> labels(
            @RequestParam(required = false) Long factSheetId) {
        List<GraphNode> entities = factSheetId != null
                ? nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY)
                : nodeRepository.findByNodeType(NodeLevel.ENTITY);

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
    @Transactional
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
                ? nodeRepository.findByFactSheetId(factSheetId)
                : nodeRepository.findAll();

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
                edgeRepository.deleteAllEdgesForNode(n);
                nodeRepository.delete(n);
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
    @Transactional
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
                ? nodeRepository.findByFactSheetId(factSheetId)
                : nodeRepository.findAll();
        List<GraphEdge> edges = factSheetId != null
                ? edgeRepository.findByFactSheetId(factSheetId)
                : edgeRepository.findAll();

        Set<Long> nodeIds = nodes.stream().map(GraphNode::getId).collect(Collectors.toSet());
        Set<EdgeType> allowedTypes = req.edgeTypes() != null
                ? req.edgeTypes().stream().map(EdgeType::valueOf).collect(Collectors.toSet())
                : null;

        int danglingRemoved = 0, duplicatesRemoved = 0, weakRemoved = 0;
        double minWeight = req.minWeight() != null ? req.minWeight() : 0.3;

        if (req.removeDangling() != null && req.removeDangling()) {
            for (GraphEdge e : edges) {
                if (!nodeIds.contains(e.getSourceNode().getId())
                        || !nodeIds.contains(e.getTargetNode().getId())) {
                    danglingRemoved++;
                    if (!dryRun) edgeRepository.delete(e);
                }
            }
        }

        if (req.removeDuplicates() != null && req.removeDuplicates()) {
            Set<String> sigs = new HashSet<>();
            for (GraphEdge e : edges) {
                String sig = e.getSourceNode().getId() + "->" + e.getTargetNode().getId() + ":" + e.getEdgeType();
                if (!sigs.add(sig)) {
                    duplicatesRemoved++;
                    if (!dryRun) edgeRepository.delete(e);
                }
            }
        }

        for (GraphEdge e : edges) {
            if (allowedTypes != null && !allowedTypes.contains(e.getEdgeType())) continue;
            if (e.getEdgeType() == EdgeType.HIERARCHICAL || e.getEdgeType() == EdgeType.USER_DEFINED) continue;
            if (e.getWeight() != null && e.getWeight() < minWeight) {
                weakRemoved++;
                if (!dryRun) edgeRepository.delete(e);
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
            if (n.getEdgeCount() != null && n.getEdgeCount() > 0) return false;
        }
        if (titlePattern != null) {
            if (n.getTitle() == null || !titlePattern.matcher(n.getTitle()).find()) return false;
        }
        return true;
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
