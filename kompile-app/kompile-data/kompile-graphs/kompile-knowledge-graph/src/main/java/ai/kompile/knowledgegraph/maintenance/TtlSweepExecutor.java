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

import ai.kompile.core.graphrag.maintenance.model.MaintenanceTask;
import ai.kompile.core.graphrag.maintenance.model.TaskReport;
import ai.kompile.core.graphrag.maintenance.model.TtlPolicy;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes TTL-based expiry sweeps against a knowledge graph fact sheet.
 *
 * <p>Behaviour is governed by the {@link TtlPolicy}: expired nodes and edges
 * can be soft-deleted (marked stale) or hard-deleted immediately.  High-confidence
 * items can be exempt via {@code policy.minConfidenceToKeep()}.
 * Previously soft-deleted items that have passed the grace period are hard-deleted.</p>
 */
@Slf4j
@Component
public class TtlSweepExecutor {

    /** Default grace period in days before stale items are permanently deleted. */
    private static final int DEFAULT_GRACE_DAYS = 7;

    private  GraphNodeRepository nodeRepository;
    private  GraphEdgeRepository edgeRepository;

    public TtlSweepExecutor(GraphNodeRepository nodeRepository,
                            GraphEdgeRepository edgeRepository) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected TtlSweepExecutor() {}


    /**
     * Run a TTL sweep over the given fact sheet.
     *
     * @param factSheetId the fact sheet to sweep
     * @param policy      the TTL policy to apply
     * @param dryRun      when {@code true} no writes are performed
     * @return a {@link TaskReport} summarising the sweep
     */
    @Transactional
    public TaskReport execute(Long factSheetId, TtlPolicy policy, boolean dryRun) {
        Instant start = Instant.now();
        LocalDateTime now = LocalDateTime.now();
        int scanned = 0;
        int affected = 0;
        int skipped = 0;
        List<String> warnings = new ArrayList<>();

        // ── 1. Find expired nodes ────────────────────────────────────────────────
        List<GraphNode> expiredNodes = nodeRepository.findExpiredNodes(factSheetId, now);
        scanned += expiredNodes.size();
        log.debug("TTL sweep: {} expired nodes found for factSheet={}", expiredNodes.size(), factSheetId);

        List<Long> nodeIdsToMark = new ArrayList<>();
        for (GraphNode node : expiredNodes) {
            Double confidence = node.getConfidence();
            if (policy.minConfidenceToKeep() != null
                    && confidence != null
                    && confidence >= policy.minConfidenceToKeep()) {
                skipped++;
                log.debug("Skipping high-confidence expired node {} (confidence={})", node.getNodeId(), confidence);
                continue;
            }

            switch (policy.action()) {
                case SOFT_DELETE, MARK_STALE -> {
                    if (!dryRun) {
                        nodeIdsToMark.add(node.getId());
                    }
                    affected++;
                }
                case HARD_DELETE -> {
                    if (!dryRun) {
                        nodeRepository.delete(node);
                    }
                    affected++;
                }
                default -> warnings.add("Unknown TtlAction for node " + node.getNodeId());
            }
        }

        if (!dryRun && !nodeIdsToMark.isEmpty()) {
            nodeRepository.bulkMarkStale(nodeIdsToMark, now);
        }

        // ── 2. Find expired edges ────────────────────────────────────────────────
        List<GraphEdge> expiredEdges = edgeRepository.findExpiredEdges(factSheetId, now);
        scanned += expiredEdges.size();
        log.debug("TTL sweep: {} expired edges found for factSheet={}", expiredEdges.size(), factSheetId);

        List<Long> edgeIdsToMark = new ArrayList<>();
        for (GraphEdge edge : expiredEdges) {
            Double confidence = edge.getConfidence();
            if (policy.minConfidenceToKeep() != null
                    && confidence != null
                    && confidence >= policy.minConfidenceToKeep()) {
                skipped++;
                log.debug("Skipping high-confidence expired edge {} (confidence={})", edge.getEdgeId(), confidence);
                continue;
            }

            switch (policy.action()) {
                case SOFT_DELETE, MARK_STALE -> {
                    if (!dryRun) {
                        edgeIdsToMark.add(edge.getId());
                    }
                    affected++;
                }
                case HARD_DELETE -> {
                    if (!dryRun) {
                        edgeRepository.delete(edge);
                    }
                    affected++;
                }
                default -> warnings.add("Unknown TtlAction for edge " + edge.getEdgeId());
            }
        }

        if (!dryRun && !edgeIdsToMark.isEmpty()) {
            edgeRepository.bulkMarkStale(edgeIdsToMark, now);
        }

        // ── 3. Hard-delete previously stale items past grace period ──────────────
        LocalDateTime graceCutoff = now.minusDays(DEFAULT_GRACE_DAYS);
        if (!dryRun) {
            int hardDeletedNodes = nodeRepository.hardDeleteStaleNodes(factSheetId, graceCutoff);
            int hardDeletedEdges = edgeRepository.hardDeleteStaleEdges(factSheetId, graceCutoff);
            if (hardDeletedNodes > 0 || hardDeletedEdges > 0) {
                log.info("TTL sweep: hard-deleted {} nodes and {} edges past grace period for factSheet={}",
                        hardDeletedNodes, hardDeletedEdges, factSheetId);
            }
        }

        log.info("TTL sweep (dryRun={}): scanned={}, affected={}, skipped={} for factSheet={}",
                dryRun, scanned, affected, skipped, factSheetId);

        return new TaskReport(
                MaintenanceTask.TTL_SWEEP,
                scanned,
                affected,
                skipped,
                warnings,
                Duration.between(start, Instant.now())
        );
    }
}
