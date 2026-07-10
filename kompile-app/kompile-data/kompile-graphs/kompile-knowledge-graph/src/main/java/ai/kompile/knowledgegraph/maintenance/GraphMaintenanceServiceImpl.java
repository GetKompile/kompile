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

import ai.kompile.core.graphrag.conformance.GraphConformanceChecker;
import ai.kompile.core.graphrag.conformance.GraphConformanceSummary;
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
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.resolution.GraphCompactionService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Node levels scanned for orphan health — everything structural except SOURCE roots and freeform CUSTOM. */
    private static final Set<NodeLevel> HEALTH_ORPHAN_LEVELS = Set.of(
            NodeLevel.ENTITY, NodeLevel.DOCUMENT, NodeLevel.SNIPPET,
            NodeLevel.TABLE, NodeLevel.ATTACHMENT, NodeLevel.IDENTIFIER);

    /** Confidence below which a node/edge is reported (not pruned) as low-confidence by STATS_REFRESH. */
    private static final double LOW_CONFIDENCE_REPORT_THRESHOLD = 0.5;

    private final TtlSweepExecutor ttlSweepExecutor;
    private final OrphanPruner orphanPruner;
    private final ConfidencePruner confidencePruner;
    private final ComponentPruner componentPruner;
    private final ContradictionDetector contradictionDetector;
    private final ProvenanceValidator provenanceValidator;
    private final SnapshotManager snapshotManager;
    private final KnowledgeGraphService knowledgeGraphService;
    private final GraphCompactionService graphCompactionService;
    /** Optional ontology conformance SPI — present only when app-main (the implementor) is on the classpath. */
    private final ObjectProvider<GraphConformanceChecker> conformanceCheckerProvider;

    /** Bounded list of recent reports, newest first. */
    private final List<MaintenanceReport> history = new CopyOnWriteArrayList<>();

    public GraphMaintenanceServiceImpl(TtlSweepExecutor ttlSweepExecutor,
                                       OrphanPruner orphanPruner,
                                       ConfidencePruner confidencePruner,
                                       ComponentPruner componentPruner,
                                       ContradictionDetector contradictionDetector,
                                       ProvenanceValidator provenanceValidator,
                                       SnapshotManager snapshotManager,
                                       KnowledgeGraphService knowledgeGraphService,
                                       GraphCompactionService graphCompactionService,
                                       ObjectProvider<GraphConformanceChecker> conformanceCheckerProvider) {
        this.ttlSweepExecutor = ttlSweepExecutor;
        this.orphanPruner = orphanPruner;
        this.confidencePruner = confidencePruner;
        this.componentPruner = componentPruner;
        this.contradictionDetector = contradictionDetector;
        this.provenanceValidator = provenanceValidator;
        this.snapshotManager = snapshotManager;
        this.knowledgeGraphService = knowledgeGraphService;
        this.graphCompactionService = graphCompactionService;
        this.conformanceCheckerProvider = conformanceCheckerProvider;
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
    public MaintenanceReport resolveContradictionsByEdgeSelection(Long factSheetId,
                                                                   List<String> staleEdgeIds,
                                                                   boolean dryRun) {
        log.info("resolveContradictionsByEdgeSelection factSheet={}, edgeCount={}, dryRun={}",
                factSheetId, staleEdgeIds != null ? staleEdgeIds.size() : 0, dryRun);
        Instant start = Instant.now();
        if (!dryRun && staleEdgeIds != null && !staleEdgeIds.isEmpty()) {
            staleEdgeIds.forEach(knowledgeGraphService::deleteEdge);
        }
        int affected = (!dryRun && staleEdgeIds != null) ? staleEdgeIds.size() : 0;
        TaskReport taskReport = new TaskReport(MaintenanceTask.CONTRADICTION_DETECT,
                staleEdgeIds != null ? staleEdgeIds.size() : 0, affected, 0,
                List.of(), Duration.between(start, Instant.now()));
        MaintenanceReport report = singleTaskReport(factSheetId, start, dryRun, taskReport);
        addToHistory(report);
        return report;
    }

    @Override
    public MaintenanceReport reResolveEntities(Long factSheetId, ReResolutionConfig config, boolean dryRun) {
        ReResolutionConfig cfg = config != null ? config : ReResolutionConfig.defaults();
        // Only mutate when the caller asked to merge AND this is not a dry run.
        boolean merge = cfg.mergeOnMatch() && !dryRun;
        log.info("reResolveEntities factSheet={}, threshold={}, merge={} (dryRun={})",
                factSheetId, cfg.similarityThreshold(), merge, dryRun);
        Instant start = Instant.now();
        TaskReport taskReport = reResolve(factSheetId, cfg.similarityThreshold(), merge);
        MaintenanceReport report = singleTaskReport(factSheetId, start, dryRun, taskReport);
        addToHistory(report);
        return report;
    }

    @Override
    public List<ProvenanceCheck> validateProvenance(Long factSheetId) {
        return provenanceValidator.validate(factSheetId);
    }

    @Override
    public GraphConformanceSummary checkOntologyConformance(Long factSheetId) {
        GraphConformanceChecker checker = conformanceCheckerProvider.getIfAvailable();
        if (checker == null) {
            log.debug("No GraphConformanceChecker wired; skipping ontology conformance for factSheet={}", factSheetId);
            return GraphConformanceSummary.notBound(factSheetId);
        }
        return checker.checkFactSheet(factSheetId);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public GraphSnapshot createSnapshot(Long factSheetId, String reason) {
        return snapshotManager.createSnapshot(factSheetId, reason);
    }

    @Override
    public MaintenanceReport restoreSnapshot(String snapshotId) {
        Instant start = Instant.now();
        try {
            GraphSnapshot restored = snapshotManager.restoreSnapshot(snapshotId);
            Instant end = Instant.now();
            int entities = restored != null ? restored.entityCount() : 0;
            int relationships = restored != null ? restored.relationshipCount() : 0;
            Long factSheetId = restored != null ? restored.factSheetId() : null;
            TaskReport task = new TaskReport(
                    MaintenanceTask.STATS_REFRESH, entities + relationships, entities + relationships, 0,
                    List.of(), Duration.between(start, end));
            MaintenanceReport report = new MaintenanceReport(
                    UUID.randomUUID().toString(), factSheetId, start, end, false,
                    Map.of(MaintenanceTask.STATS_REFRESH, task), null, restored);
            addToHistory(report);
            return report;
        } catch (Exception e) {
            Instant end = Instant.now();
            log.warn("Snapshot restore failed for snapshotId={}: {}", snapshotId, e.getMessage());
            TaskReport task = new TaskReport(
                    MaintenanceTask.STATS_REFRESH, 0, 0, 0,
                    List.of("Snapshot restore failed: " + e.getMessage()), Duration.between(start, end));
            MaintenanceReport report = new MaintenanceReport(
                    UUID.randomUUID().toString(), null, start, end, false,
                    Map.of(MaintenanceTask.STATS_REFRESH, task), null, null);
            addToHistory(report);
            return report;
        }
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
            case ENTITY_RE_RESOLUTION ->
                reResolve(factSheetId, ReResolutionConfig.defaults().similarityThreshold(), !dryRun);
            case STATS_REFRESH ->
                computeStatsRefresh(factSheetId);
            case COMMUNITY_REBUILD ->
                new TaskReport(task, 0, 0, 0, List.of("COMMUNITY_REBUILD not yet implemented"), Duration.ZERO);
        };
    }

    /**
     * Recomputes a graph-health snapshot for the fact sheet (active node count, orphan nodes,
     * low-confidence nodes/edges) through the store-agnostic {@link KnowledgeGraphService}, so it
     * reflects whichever backend is active (the matrix/vector store in production). Read-only:
     * {@code itemsAffected} reports the number of quality issues found, never a mutation count.
     */
    private TaskReport computeStatsRefresh(Long factSheetId) {
        Instant start = Instant.now();
        long activeNodes = knowledgeGraphService.countActiveNodes(factSheetId);
        int orphans = knowledgeGraphService.findOrphanNodeIds(factSheetId, HEALTH_ORPHAN_LEVELS).size();
        int lowConfNodes = knowledgeGraphService
                .findLowConfidenceNodeIds(factSheetId, LOW_CONFIDENCE_REPORT_THRESHOLD).size();
        int lowConfEdges = knowledgeGraphService
                .findLowConfidenceEdgeIds(factSheetId, LOW_CONFIDENCE_REPORT_THRESHOLD).size();
        int issues = orphans + lowConfNodes + lowConfEdges;
        List<String> summary = List.of(
                "activeNodes=" + activeNodes,
                "orphanNodes=" + orphans,
                "lowConfidenceNodes(<" + LOW_CONFIDENCE_REPORT_THRESHOLD + ")=" + lowConfNodes,
                "lowConfidenceEdges(<" + LOW_CONFIDENCE_REPORT_THRESHOLD + ")=" + lowConfEdges);
        log.info("STATS_REFRESH factSheet={}: {}", factSheetId, summary);
        return new TaskReport(MaintenanceTask.STATS_REFRESH,
                (int) Math.min(activeNodes, Integer.MAX_VALUE), issues, 0,
                summary, Duration.between(start, Instant.now()));
    }

    /**
     * Re-runs entity resolution (graph compaction) for the fact sheet via {@link GraphCompactionService},
     * which operates on the active backend. When {@code merge} is false it previews candidate pairs without
     * mutating; otherwise it compacts and reports merge deltas.
     */
    private TaskReport reResolve(Long factSheetId, double similarityThreshold, boolean merge) {
        Instant start = Instant.now();
        if (!merge) {
            int candidates = graphCompactionService.previewCandidates(
                    factSheetId, GraphCompactionService.CompactionConfig.previewOnly(similarityThreshold)).size();
            return new TaskReport(MaintenanceTask.ENTITY_RE_RESOLUTION, candidates, 0, candidates,
                    List.of("preview: " + candidates + " merge candidate(s) at threshold " + similarityThreshold),
                    Duration.between(start, Instant.now()));
        }
        GraphCompactionService.CompactionResult result = graphCompactionService.compact(
                factSheetId, GraphCompactionService.CompactionConfig.withThreshold(similarityThreshold));
        return new TaskReport(MaintenanceTask.ENTITY_RE_RESOLUTION,
                result.originalEntityCount(), result.entitiesMerged(),
                Math.max(0, result.originalEntityCount() - result.entitiesMerged()),
                List.of("entitiesMerged=" + result.entitiesMerged(),
                        "edgesRedirected=" + result.edgesRedirected(),
                        "finalEntityCount=" + result.finalEntityCount()),
                Duration.between(start, Instant.now()));
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
