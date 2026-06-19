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
import ai.kompile.core.graphrag.maintenance.model.MaintenanceTask;
import ai.kompile.core.graphrag.maintenance.model.TaskReport;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
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
 */
@Slf4j
@Component
public class ConfidencePruner {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private  GraphNodeRepository nodeRepository;
    private  GraphEdgeRepository edgeRepository;
    private  ObjectMapper objectMapper;

    public ConfidencePruner(GraphNodeRepository nodeRepository,
                            GraphEdgeRepository edgeRepository,
                            ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.objectMapper = objectMapper;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected ConfidencePruner() {}


    /**
     * Execute confidence-based pruning for the given fact sheet.
     *
     * @param factSheetId the fact sheet to prune
     * @param policy      pruning thresholds and flags
     * @param dryRun      when {@code true} no writes are performed
     * @return a {@link TaskReport} summarising the operation
     */
    @Transactional
    public TaskReport execute(Long factSheetId, ConfidencePrunePolicy policy, boolean dryRun) {
        Instant start = Instant.now();
        LocalDateTime now = LocalDateTime.now();
        int scanned = 0;
        int affected = 0;
        int skipped = 0;
        List<String> warnings = new ArrayList<>();

        // ── 1. Prune low-confidence entities ────────────────────────────────────
        List<GraphNode> lowConfidenceEntities =
                nodeRepository.findLowConfidenceEntities(factSheetId, policy.minEntityConfidence());
        scanned += lowConfidenceEntities.size();
        log.debug("ConfidencePruner: {} low-confidence entities (below {}) for factSheet={}",
                lowConfidenceEntities.size(), policy.minEntityConfidence(), factSheetId);

        List<Long> entityIdsToMark = new ArrayList<>();
        for (GraphNode entity : lowConfidenceEntities) {
            // Check corroborating mentions heuristic: if minCorroboratingMentions > 1,
            // only prune entities whose metadata indicates a single sourceChunkId
            if (policy.minCorroboratingMentions() > 1 && isCorroborated(entity, policy.minCorroboratingMentions())) {
                skipped++;
                log.debug("Skipping corroborated low-confidence entity {} for factSheet={}",
                        entity.getNodeId(), factSheetId);
                continue;
            }
            entityIdsToMark.add(entity.getId());
            affected++;
        }

        if (!dryRun && !entityIdsToMark.isEmpty()) {
            nodeRepository.bulkMarkStale(entityIdsToMark, now);
            log.info("ConfidencePruner: marked {} low-confidence entities as stale for factSheet={}",
                    entityIdsToMark.size(), factSheetId);
        }

        // ── 2. Prune low-confidence edges ────────────────────────────────────────
        List<GraphEdge> lowConfidenceEdges =
                edgeRepository.findLowConfidenceEdges(factSheetId, policy.minRelationshipConfidence());
        scanned += lowConfidenceEdges.size();
        log.debug("ConfidencePruner: {} low-confidence edges (below {}) for factSheet={}",
                lowConfidenceEdges.size(), policy.minRelationshipConfidence(), factSheetId);

        List<Long> edgeIdsToMark = new ArrayList<>();
        for (GraphEdge edge : lowConfidenceEdges) {
            edgeIdsToMark.add(edge.getId());
            affected++;
        }

        // ── 3. Prune AMBIGUOUS provenance edges (if requireExtractedProvenance) ───
        if (policy.requireExtractedProvenance()) {
            List<GraphEdge> allActiveEdges = edgeRepository.findActiveEdges(factSheetId);
            scanned += allActiveEdges.size();
            for (GraphEdge edge : allActiveEdges) {
                // Skip if already queued via low-confidence path
                if (edgeIdsToMark.contains(edge.getId())) continue;

                String prov = edge.getProvenance();
                if (EdgeProvenance.AMBIGUOUS.name().equalsIgnoreCase(prov)) {
                    log.debug("Flagging AMBIGUOUS provenance edge {} for pruning", edge.getEdgeId());
                    edgeIdsToMark.add(edge.getId());
                    affected++;
                }
            }
        }

        if (!dryRun && !edgeIdsToMark.isEmpty()) {
            edgeRepository.bulkMarkStale(edgeIdsToMark, now);
            log.info("ConfidencePruner: marked {} edges as stale for factSheet={}",
                    edgeIdsToMark.size(), factSheetId);
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
