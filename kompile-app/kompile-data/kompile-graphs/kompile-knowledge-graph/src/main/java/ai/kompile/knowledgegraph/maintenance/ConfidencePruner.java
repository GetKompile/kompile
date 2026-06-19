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

import ai.kompile.core.graphrag.maintenance.model.ConfidencePrunePolicy;
import ai.kompile.core.graphrag.maintenance.model.GraphPruneResult;
import ai.kompile.core.graphrag.maintenance.model.MaintenanceTask;
import ai.kompile.core.graphrag.maintenance.model.TaskReport;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prunes knowledge-graph entities and edges whose confidence scores fall below
 * the thresholds defined in a {@link ConfidencePrunePolicy}.
 *
 * <p>Additional heuristics:</p>
 * <ul>
 *   <li>Entities corroborated by fewer than {@code policy.minCorroboratingMentions()}
 *       distinct text chunks are considered weakly evidenced and are eligible for pruning.</li>
 *   <li>When {@code policy.requireExtractedProvenance()} is {@code true}, edges whose
 *       provenance is {@link EdgeProvenance#AMBIGUOUS} are also soft-deleted.</li>
 * </ul>
 *
 * <p>This class depends only on {@link KnowledgeGraphService} and is therefore
 * store-agnostic — it works with both the JPA and the matrix/vector-store
 * backends without modification.</p>
 */
@Slf4j
@Component
public class ConfidencePruner {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final KnowledgeGraphService knowledgeGraphService;
    private final ObjectMapper objectMapper;

    public ConfidencePruner(KnowledgeGraphService knowledgeGraphService,
                            ObjectMapper objectMapper) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.objectMapper = objectMapper;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected ConfidencePruner() {
        this.knowledgeGraphService = null;
        this.objectMapper = null;
    }

    /**
     * Execute confidence-based pruning for the given fact sheet.
     *
     * @param factSheetId the fact sheet to prune
     * @param policy      pruning thresholds and flags
     * @param dryRun      when {@code true} no writes are performed
     * @return a {@link TaskReport} summarising the operation
     */
    public TaskReport execute(Long factSheetId, ConfidencePrunePolicy policy, boolean dryRun) {
        Instant start = Instant.now();
        int scanned = 0;
        int affected = 0;
        int skipped = 0;
        List<String> warnings = new ArrayList<>();

        // ── 1. Prune low-confidence entities ────────────────────────────────────
        List<String> lowConfidenceNodeIds =
                knowledgeGraphService.findLowConfidenceNodeIds(factSheetId, policy.minEntityConfidence());
        scanned += lowConfidenceNodeIds.size();
        log.debug("ConfidencePruner: {} low-confidence entities (below {}) for factSheet={}",
                lowConfidenceNodeIds.size(), policy.minEntityConfidence(), factSheetId);

        // Apply corroborating-mentions heuristic when needed
        List<String> entityIdsToMark = new ArrayList<>();
        if (policy.minCorroboratingMentions() > 1) {
            for (String nodeId : lowConfidenceNodeIds) {
                knowledgeGraphService.getNode(nodeId).ifPresent(node -> {
                    if (isCorroborated(node, policy.minCorroboratingMentions())) {
                        log.debug("Skipping corroborated low-confidence entity {} for factSheet={}",
                                nodeId, factSheetId);
                        // do not add to mark list — node stays
                    } else {
                        entityIdsToMark.add(nodeId);
                    }
                });
            }
            skipped += lowConfidenceNodeIds.size() - entityIdsToMark.size();
        } else {
            entityIdsToMark.addAll(lowConfidenceNodeIds);
        }
        affected += entityIdsToMark.size();

        if (!entityIdsToMark.isEmpty()) {
            GraphPruneResult nodeResult = knowledgeGraphService.pruneNodes(
                    entityIdsToMark, /* softDelete= */ true, null, dryRun);
            if (!dryRun) {
                log.info("ConfidencePruner: marked {} low-confidence entities as stale for factSheet={}",
                        nodeResult.affectedCount(), factSheetId);
            }
        }

        // ── 2. Prune low-confidence edges ────────────────────────────────────────
        List<String> lowConfidenceEdgeIds =
                knowledgeGraphService.findLowConfidenceEdgeIds(factSheetId, policy.minRelationshipConfidence());
        scanned += lowConfidenceEdgeIds.size();
        log.debug("ConfidencePruner: {} low-confidence edges (below {}) for factSheet={}",
                lowConfidenceEdgeIds.size(), policy.minRelationshipConfidence(), factSheetId);

        List<String> edgeIdsToMark = new ArrayList<>(lowConfidenceEdgeIds);
        affected += edgeIdsToMark.size();

        // ── 3. Prune AMBIGUOUS provenance edges (if requireExtractedProvenance) ───
        if (policy.requireExtractedProvenance()) {
            List<String> activeEdgeIds = knowledgeGraphService.findActiveEdgeIds(factSheetId);
            scanned += activeEdgeIds.size();
            for (String edgeId : activeEdgeIds) {
                if (edgeIdsToMark.contains(edgeId)) continue; // already queued
                knowledgeGraphService.getEdge(edgeId).ifPresent(edge -> {
                    String prov = edge.getProvenance();
                    if (EdgeProvenance.AMBIGUOUS.name().equalsIgnoreCase(prov)) {
                        log.debug("Flagging AMBIGUOUS provenance edge {} for pruning", edgeId);
                        edgeIdsToMark.add(edgeId);
                    }
                });
            }
            // Recalculate affected count after AMBIGUOUS scan
            affected = entityIdsToMark.size() + edgeIdsToMark.size();
        }

        if (!edgeIdsToMark.isEmpty()) {
            GraphPruneResult edgeResult = knowledgeGraphService.pruneEdges(
                    edgeIdsToMark, /* softDelete= */ true, dryRun);
            if (!dryRun) {
                log.info("ConfidencePruner: marked {} edges as stale for factSheet={}",
                        edgeResult.affectedCount(), factSheetId);
            }
        }

        log.info("ConfidencePruner (dryRun={}): scanned={}, affected={}, skipped={} for factSheet={}",
                dryRun, scanned, affected, skipped, factSheetId);

        return new TaskReport(
                MaintenanceTask.CONFIDENCE_PRUNE,
                scanned,
                affected,
                skipped,
                warnings,
                Duration.between(start, Instant.now())
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the entity's metadata contains corroborating chunk evidence
     * that meets the minimum count threshold.
     *
     * <p>Looks for {@code sourceChunkIds} (array) or {@code sourceChunkId} (scalar) in
     * the entity's metadataJson.  If the distinct count is >= {@code minMentions}, the
     * entity is considered corroborated and should be kept.</p>
     */
    private boolean isCorroborated(GraphNode entity, int minMentions) {
        if (entity.getMetadataJson() == null || entity.getMetadataJson().isBlank()) {
            return false;
        }
        try {
            Map<String, Object> meta = objectMapper.readValue(entity.getMetadataJson(), MAP_TYPE);
            Object chunks = meta.get("sourceChunkIds");
            if (chunks instanceof List<?> list) {
                return list.size() >= minMentions;
            }
            // Scalar single chunk — counts as exactly 1 mention
            Object singleChunk = meta.get("sourceChunkId");
            return singleChunk != null && minMentions <= 1;
        } catch (Exception e) {
            log.warn("Could not parse metadataJson for node {}: {}", entity.getNodeId(), e.getMessage());
            return false;
        }
    }
}
