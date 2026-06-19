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

import ai.kompile.core.graphrag.maintenance.model.GraphPruneResult;
import ai.kompile.core.graphrag.maintenance.model.MaintenanceTask;
import ai.kompile.core.graphrag.maintenance.model.OrphanScanResult;
import ai.kompile.core.graphrag.maintenance.model.TaskReport;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds and removes orphan entity nodes — ENTITY-level nodes that have zero
 * edges in the knowledge graph (neither as source nor as target).
 *
 * <p>Orphans are soft-deleted first; a subsequent sweep (or the grace-period
 * hard-delete path in {@link TtlSweepExecutor}) will permanently remove them
 * once the grace period has passed.</p>
 *
 * <p>This class depends only on {@link KnowledgeGraphService} and is therefore
 * store-agnostic — it works with both the JPA and the matrix/vector-store
 * backends without modification.</p>
 */
@Slf4j
@Component
public class OrphanPruner {

    /** Default grace period in days before previously-stale orphans are hard-deleted. */
    private static final int DEFAULT_GRACE_DAYS = 7;

    private final KnowledgeGraphService knowledgeGraphService;

    public OrphanPruner(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected OrphanPruner() {
        this.knowledgeGraphService = null;
    }

    /**
     * Scan for orphan entity nodes without persisting any changes.
     *
     * @param factSheetId the fact sheet to scan
     * @return a summary of orphan entities found
     */
    public OrphanScanResult scan(Long factSheetId) {
        List<String> orphanNodeIds = knowledgeGraphService.findOrphanNodeIds(factSheetId);
        long totalActive = knowledgeGraphService.countActiveNodes(factSheetId);

        Map<String, String> orphanDetails = new LinkedHashMap<>();
        for (String nodeId : orphanNodeIds) {
            knowledgeGraphService.getNode(nodeId).ifPresent(node ->
                    orphanDetails.put(nodeId,
                            "title=" + node.getTitle() + ", confidence=" + node.getConfidence()));
        }

        log.info("Orphan scan: found {}/{} orphan entities for factSheet={}",
                orphanNodeIds.size(), totalActive, factSheetId);

        return new OrphanScanResult(orphanNodeIds, (int) totalActive, orphanNodeIds.size(), orphanDetails);
    }

    /**
     * Execute orphan pruning for the given fact sheet.
     *
     * <p>Step 1 — soft-deletes newly discovered orphans (marks them stale).
     * Step 2 — hard-deletes orphans that were previously marked stale and have
     * exceeded the specified grace period.</p>
     *
     * @param factSheetId the fact sheet to prune
     * @param gracePeriod how long a stale orphan is retained before hard deletion
     * @param dryRun      when {@code true} no writes are performed
     * @return a {@link TaskReport} summarising the operation
     */
    public TaskReport execute(Long factSheetId, Duration gracePeriod, boolean dryRun) {
        Instant start = Instant.now();
        List<String> warnings = new ArrayList<>();

        // ── Step 1: Soft-delete newly discovered orphans ─────────────────────────
        List<String> orphanNodeIds = knowledgeGraphService.findOrphanNodeIds(factSheetId);
        log.debug("OrphanPruner: {} orphan entities found for factSheet={}", orphanNodeIds.size(), factSheetId);

        GraphPruneResult softDeleteResult = knowledgeGraphService.pruneNodes(
                orphanNodeIds,
                /* softDelete= */ true,
                gracePeriod,
                dryRun);

        if (!dryRun && softDeleteResult.affectedCount() > 0) {
            log.info("OrphanPruner: marked {} orphan entities as stale for factSheet={}",
                    softDeleteResult.affectedCount(), factSheetId);
        }

        // ── Step 2: Hard-delete previously stale orphans past grace period ────────
        Duration effectiveGrace = gracePeriod != null
                ? gracePeriod
                : Duration.ofDays(DEFAULT_GRACE_DAYS);

        GraphPruneResult hardDeleteResult = GraphPruneResult.empty(dryRun);
        if (!dryRun) {
            hardDeleteResult = knowledgeGraphService.hardDeleteStaleNodes(factSheetId, effectiveGrace);
            if (hardDeleteResult.hardDeleted() > 0) {
                log.info("OrphanPruner: hard-deleted {} stale nodes past grace period ({} days) for factSheet={}",
                        hardDeleteResult.hardDeleted(), effectiveGrace.toDays(), factSheetId);
            }
        }

        int scanned = orphanNodeIds.size();
        int affected = softDeleteResult.affectedCount();
        int skipped = 0;

        log.info("OrphanPruner (dryRun={}): scanned={}, affected={}, skipped={} for factSheet={}",
                dryRun, scanned, affected, skipped, factSheetId);

        return new TaskReport(
                MaintenanceTask.ORPHAN_CLEANUP,
                scanned,
                affected,
                skipped,
                warnings,
                Duration.between(start, Instant.now())
        );
    }
}
