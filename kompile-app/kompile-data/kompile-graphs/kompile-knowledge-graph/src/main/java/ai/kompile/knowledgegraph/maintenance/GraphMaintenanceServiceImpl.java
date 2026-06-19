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

import ai.kompile.core.graphrag.maintenance.GraphMaintenanceService;
import ai.kompile.core.graphrag.maintenance.model.ConfidencePrunePolicy;
import ai.kompile.core.graphrag.maintenance.model.ComponentPrunePolicy;
import ai.kompile.core.graphrag.maintenance.model.Contradiction;
import ai.kompile.core.graphrag.maintenance.model.ContradictionResolutionStrategy;
import ai.kompile.core.graphrag.maintenance.model.GraphSnapshot;
import ai.kompile.core.graphrag.maintenance.model.MaintenanceReport;
import ai.kompile.core.graphrag.maintenance.model.MaintenanceSchedule;
import ai.kompile.core.graphrag.maintenance.model.MaintenanceTask;
import ai.kompile.core.graphrag.maintenance.model.OrphanScanResult;
import ai.kompile.core.graphrag.maintenance.model.ProvenanceCheck;
import ai.kompile.core.graphrag.maintenance.model.ReResolutionConfig;
import ai.kompile.core.graphrag.maintenance.model.TaskReport;
import ai.kompile.core.graphrag.maintenance.model.TtlPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Orchestrates all graph-maintenance operations by delegating to
 * the specialist executor beans.  Each public method wraps the result
 * in a {@link MaintenanceReport} and records it in a bounded in-memory
 * history so callers can retrieve recent maintenance activity.
 */
@Slf4j
@Service
public class GraphMaintenanceServiceImpl implements GraphMaintenanceService {

    private static final int MAX_HISTORY = 50;

    private final TtlSweepExecutor ttlSweepExecutor;
    private final OrphanPruner orphanPruner;
    private final ConfidencePruner confidencePruner;
    private final ComponentPruner componentPruner;
    private final ContradictionDetector contradictionDetector;
    private final ProvenanceValidator provenanceValidator;
    private final SnapshotManager snapshotManager;

    /** Bounded list of recent reports, newest first. */
    private final List<MaintenanceReport> history = new CopyOnWriteArrayList<>();

    public GraphMaintenanceServiceImpl(TtlSweepExecutor ttlSweepExecutor,
                                       OrphanPruner orphanPruner,
                                       ConfidencePruner confidencePruner,
                                       ComponentPruner componentPruner,
                                       ContradictionDetector contradictionDetector,
                                       ProvenanceValidator provenanceValidator,
                                       SnapshotManager snapshotManager) {
        this.ttlSweepExecutor = ttlSweepExecutor;
        this.orphanPruner = orphanPruner;
        this.confidencePruner = confidencePruner;
        this.componentPruner = componentPruner;
        this.contradictionDetector = contradictionDetector;
        this.provenanceValidator = provenanceValidator;
        this.snapshotManager = snapshotManager;
    }

    // ── Pruning ──────────────────────────────────────────────────────────────

    @Override
    public MaintenanceReport runTtlSweep(Long factSheetId, TtlPolicy policy, boolean dryRun) {
        log.info("runTtlSweep factSheet={}, dryRun={}", factSheetId, dryRun);
        Instant start = Instant.now();
        TaskReport taskReport = ttlSweepExecutor.execute(factSheetId, policy, dryRun);
        MaintenanceReport report = singleTaskReport(factSheetId, start, dryRun, taskReport);
        addToHistory(report);
        return report;
    }

    @Override
    public OrphanScanResult findOrphans(Long factSheetId) {
        return orphanPruner.scan(factSheetId);
    }

    @Override
    public MaintenanceReport pruneOrphans(Long factSheetId, Duration gracePeriod, boolean dryRun) {
        log.info("pruneOrphans factSheet={}, grace={}, dryRun={}", factSheetId, gracePeriod, dryRun);
        Instant start = Instant.now();
        TaskReport taskReport = orphanPruner.execute(factSheetId, gracePeriod, dryRun);
        MaintenanceReport report = singleTaskReport(factSheetId, start, dryRun, taskReport);
        addToHistory(report);
        return report;
    }

    @Override
    public MaintenanceReport pruneByConfidence(Long factSheetId, ConfidencePrunePolicy policy, boolean dryRun) {
        log.info("pruneByConfidence factSheet={}, dryRun={}", factSheetId, dryRun);
        Instant start = Instant.now();
        TaskReport taskReport = confidencePruner.execute(factSheetId, policy, dryRun);
        MaintenanceReport report = singleTaskReport(factSheetId, start, dryRun, taskReport);
        addToHistory(report);
        return report;
    }

    @Override
    public MaintenanceReport pruneSmallComponents(Long factSheetId, ComponentPrunePolicy policy, boolean dryRun) {
        log.info("pruneSmallComponents factSheet={}, dryRun={}", factSheetId, dryRun);
        Instant start = Instant.now();
        TaskReport taskReport = componentPruner.execute(factSheetId, policy, dryRun);
        MaintenanceReport report = singleTaskReport(factSheetId, start, dryRun, taskReport);
        addToHistory(report);
        return report;
    }

    // ── Quality ───────────────────────────────────────────────────────────────

    @Override
    public List<Contradiction> detectContradictions(Long factSheetId) {
        return contradictionDetector.detect(factSheetId);
    }

    @Override
    public MaintenanceReport resolveContradictions(Long factSheetId,
                                                    ContradictionResolutionStrategy strategy,
                                                    boolean dryRun) {
        log.info("resolveContradictions factSheet={}, strategy={}, dryRun={}", factSheetId, strategy, dryRun);
        Instant start = Instant.now();
        TaskReport taskReport = contradictionDetector.resolve(factSheetId, strategy, dryRun);
        MaintenanceReport report = singleTaskReport(factSheetId, start, dryRun, taskReport);
        addToHistory(report);
        return report;
    }

    @Override
    public MaintenanceReport reResolveEntities(Long factSheetId, ReResolutionConfig config, boolean dryRun) {
        log.warn("reResolveEntities not yet implemented for factSheet={}", factSheetId);
        Instant now = Instant.now();
        TaskReport placeholder = new TaskReport(
                MaintenanceTask.ENTITY_RE_RESOLUTION, 0, 0, 0,
                List.of("Entity re-resolution not yet implemented"), Duration.ZERO);
        MaintenanceReport report = singleTaskReport(factSheetId, now, dryRun, placeholder);
        addToHistory(report);
        return report;
    }

    @Override
    public List<ProvenanceCheck> validateProvenance(Long factSheetId) {
        return provenanceValidator.validate(factSheetId);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public GraphSnapshot createSnapshot(Long factSheetId, String reason) {
        return snapshotManager.createSnapshot(factSheetId, reason);
    }

    @Override
    public MaintenanceReport restoreSnapshot(String snapshotId) {
        log.warn("Snapshot restore not yet implemented for snapshotId={}", snapshotId);
        Instant now = Instant.now();
        TaskReport placeholder = new TaskReport(
                MaintenanceTask.STATS_REFRESH, 0, 0, 0,
                List.of("Snapshot restore not yet implemented for snapshotId=" + snapshotId),
                Duration.ZERO);
        MaintenanceReport report = new MaintenanceReport(
                UUID.randomUUID().toString(), null, now, now, false,
                Map.of(MaintenanceTask.STATS_REFRESH, placeholder), null, null);
        addToHistory(report);
        return report;
    }

    @Override
    public List<GraphSnapshot> listSnapshots(Long factSheetId) {
        return snapshotManager.listSnapshots(factSheetId);
    }

    // ── Full maintenance ──────────────────────────────────────────────────────

    @Override
    public MaintenanceReport runFullMaintenance(Long factSheetId, MaintenanceSchedule schedule) {
        log.info("Starting full maintenance for factSheet={}, tasks={}, dryRun={}",
                factSheetId, schedule.tasks(), schedule.dryRun());
        Instant start = Instant.now();

        // 1. Pre-snapshot
        GraphSnapshot preSnapshot = null;
        if (schedule.snapshotBefore() && !schedule.dryRun()) {
            try {
                preSnapshot = snapshotManager.createSnapshot(factSheetId, "pre-maintenance");
            } catch (Exception e) {
                log.warn("Pre-maintenance snapshot failed for factSheet={}: {}", factSheetId, e.getMessage());
            }
        }

        // 2. Execute each task in order
        Map<MaintenanceTask, TaskReport> taskReports = new LinkedHashMap<>();
        for (MaintenanceTask task : schedule.tasks()) {
            try {
                TaskReport report = executeTask(factSheetId, task, schedule.dryRun());
                taskReports.put(task, report);
                log.info("Task {} completed: scanned={}, affected={}, skipped={}",
                        task, report.itemsScanned(), report.itemsAffected(), report.itemsSkipped());
            } catch (Exception e) {
                log.error("Task {} failed for factSheet={}: {}", task, factSheetId, e.getMessage(), e);
                taskReports.put(task, new TaskReport(task, 0, 0, 0,
                        List.of("FAILED: " + e.getMessage()), Duration.ZERO));
            }
        }

        // 3. Post-snapshot
        GraphSnapshot postSnapshot = null;
        if (schedule.snapshotBefore() && !schedule.dryRun()) {
            try {
                postSnapshot = snapshotManager.createSnapshot(factSheetId, "post-maintenance");
            } catch (Exception e) {
                log.warn("Post-maintenance snapshot failed for factSheet={}: {}", factSheetId, e.getMessage());
            }
        }

        MaintenanceReport report = new MaintenanceReport(
                UUID.randomUUID().toString(), factSheetId, start, Instant.now(),
                schedule.dryRun(), taskReports, preSnapshot, postSnapshot);
        addToHistory(report);
        return report;
    }

    // ── History ───────────────────────────────────────────────────────────────

    @Override
    public List<MaintenanceReport> getMaintenanceHistory(Long factSheetId, int limit) {
        return history.stream()
                .filter(r -> factSheetId == null || factSheetId.equals(r.factSheetId()))
                .limit(limit)
                .toList();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Dispatches a single {@link MaintenanceTask} to the appropriate executor.
     */
    private TaskReport executeTask(Long factSheetId, MaintenanceTask task, boolean dryRun) {
        return switch (task) {
            case TTL_SWEEP ->
                ttlSweepExecutor.execute(factSheetId, TtlPolicy.defaults(), dryRun);
            case ORPHAN_CLEANUP ->
                orphanPruner.execute(factSheetId, Duration.ofDays(7), dryRun);
            case CONFIDENCE_PRUNE ->
                confidencePruner.execute(factSheetId, ConfidencePrunePolicy.defaults(), dryRun);
            case COMPONENT_PRUNE ->
                componentPruner.execute(factSheetId, ComponentPrunePolicy.defaults(), dryRun);
            case CONTRADICTION_DETECT ->
                contradictionDetector.resolve(factSheetId,
                        ContradictionResolutionStrategy.FLAG_FOR_REVIEW, dryRun);
            case SOURCE_VALIDATION -> {
                List<ProvenanceCheck> checks = provenanceValidator.validate(factSheetId);
                int invalid = (int) checks.stream().filter(ProvenanceCheck::allSourcesInvalid).count();
                yield new TaskReport(task, checks.size(), invalid, checks.size() - invalid,
                        List.of(), Duration.ZERO);
            }
            case ENTITY_RE_RESOLUTION, STATS_REFRESH, COMMUNITY_REBUILD ->
                new TaskReport(task, 0, 0, 0, List.of("Not yet implemented"), Duration.ZERO);
        };
    }

    /**
     * Wraps a single {@link TaskReport} in a {@link MaintenanceReport}.
     */
    private MaintenanceReport singleTaskReport(Long factSheetId, Instant start,
                                                boolean dryRun, TaskReport taskReport) {
        return new MaintenanceReport(
                UUID.randomUUID().toString(),
                factSheetId,
                start,
                Instant.now(),
                dryRun,
                Map.of(taskReport.task(), taskReport),
                null,
                null);
    }

    /** Prepends to history and enforces the max-size bound. */
    private void addToHistory(MaintenanceReport report) {
        history.add(0, report);
        while (history.size() > MAX_HISTORY) {
            history.remove(history.size() - 1);
        }
    }
}
