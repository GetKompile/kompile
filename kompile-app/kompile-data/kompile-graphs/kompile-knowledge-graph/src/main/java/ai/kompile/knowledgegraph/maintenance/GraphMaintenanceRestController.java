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
import ai.kompile.core.graphrag.maintenance.model.TtlPolicy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * REST controller for the graph-maintenance subsystem.
 *
 * <p>Exposes the full {@link GraphMaintenanceService} API under
 * {@code /api/graph-maintenance/{factSheetId}/...} as well as global
 * history and scheduler control endpoints.
 *
 * <p>Note: the original {@link GraphMaintenanceController} at
 * {@code /api/graph/maintenance} remains available for backward
 * compatibility.  This controller targets the richer core-interface
 * capabilities (TTL, orphan pruning, confidence pruning, component
 * pruning, contradiction detection, provenance validation, snapshots,
 * and history).
 */
@RestController
@RequestMapping("/api/graph-maintenance")
public class GraphMaintenanceRestController {

    private final GraphMaintenanceService maintenanceService;
    private final MaintenanceScheduler scheduler;

    public GraphMaintenanceRestController(GraphMaintenanceService maintenanceService,
                                          MaintenanceScheduler scheduler) {
        this.maintenanceService = maintenanceService;
        this.scheduler = scheduler;
    }

    // ── Full maintenance ──────────────────────────────────────────────────────

    /**
     * Trigger a full maintenance run with the default task set.
     *
     * @param factSheetId the fact sheet to maintain
     * @param dryRun      when {@code true} no mutations are performed
     */
    @PostMapping("/{factSheetId}/run")
    public ResponseEntity<MaintenanceReport> runFullMaintenance(
            @PathVariable Long factSheetId,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        MaintenanceSchedule schedule = new MaintenanceSchedule(
                factSheetId, Duration.ofDays(1),
                List.of(
                        MaintenanceTask.TTL_SWEEP,
                        MaintenanceTask.ORPHAN_CLEANUP,
                        MaintenanceTask.CONFIDENCE_PRUNE,
                        MaintenanceTask.COMPONENT_PRUNE,
                        MaintenanceTask.CONTRADICTION_DETECT,
                        MaintenanceTask.SOURCE_VALIDATION,
                        MaintenanceTask.STATS_REFRESH),
                true, dryRun);
        return ResponseEntity.ok(maintenanceService.runFullMaintenance(factSheetId, schedule));
    }

    // ── TTL sweep ─────────────────────────────────────────────────────────────

    /**
     * Run a TTL sweep to mark or delete expired graph items.
     *
     * @param factSheetId the fact sheet to sweep
     * @param dryRun      when {@code true} reports what would be affected
     * @param ttlDays     items older than this many days are considered expired
     */
    @PostMapping("/{factSheetId}/prune/ttl")
    public ResponseEntity<MaintenanceReport> runTtlSweep(
            @PathVariable Long factSheetId,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "90") int ttlDays) {
        TtlPolicy policy = new TtlPolicy(
                null, null, Duration.ofDays(ttlDays),
                TtlPolicy.TtlAction.MARK_STALE, 0.7);
        return ResponseEntity.ok(maintenanceService.runTtlSweep(factSheetId, policy, dryRun));
    }

    // ── Orphan pruning ────────────────────────────────────────────────────────

    /**
     * Scan for orphaned graph nodes (nodes with no edges).
     */
    @GetMapping("/{factSheetId}/orphans")
    public ResponseEntity<OrphanScanResult> findOrphans(@PathVariable Long factSheetId) {
        return ResponseEntity.ok(maintenanceService.findOrphans(factSheetId));
    }

    /**
     * Prune orphaned graph nodes that have exceeded the grace period.
     *
     * @param graceDays nodes orphaned for fewer days than this are spared
     */
    @PostMapping("/{factSheetId}/prune/orphans")
    public ResponseEntity<MaintenanceReport> pruneOrphans(
            @PathVariable Long factSheetId,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "7") int graceDays) {
        return ResponseEntity.ok(
                maintenanceService.pruneOrphans(factSheetId, Duration.ofDays(graceDays), dryRun));
    }

    // ── Confidence pruning ────────────────────────────────────────────────────

    /**
     * Prune nodes and edges whose confidence score falls below the given thresholds.
     *
     * @param minEntityConfidence minimum confidence for entities (default 0.3)
     * @param minRelConfidence    minimum confidence for relationships (default 0.2)
     */
    @PostMapping("/{factSheetId}/prune/confidence")
    public ResponseEntity<MaintenanceReport> pruneByConfidence(
            @PathVariable Long factSheetId,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "0.3") double minEntityConfidence,
            @RequestParam(defaultValue = "0.2") double minRelConfidence) {
        ConfidencePrunePolicy policy = new ConfidencePrunePolicy(
                minEntityConfidence, minRelConfidence, 1, false);
        return ResponseEntity.ok(maintenanceService.pruneByConfidence(factSheetId, policy, dryRun));
    }

    // ── Component pruning ─────────────────────────────────────────────────────

    /**
     * Prune weakly-connected components smaller than the minimum size.
     *
     * @param minComponentSize components with fewer nodes than this are pruned
     */
    @PostMapping("/{factSheetId}/prune/components")
    public ResponseEntity<MaintenanceReport> pruneSmallComponents(
            @PathVariable Long factSheetId,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "3") int minComponentSize) {
        ComponentPrunePolicy policy = new ComponentPrunePolicy(minComponentSize, true);
        return ResponseEntity.ok(maintenanceService.pruneSmallComponents(factSheetId, policy, dryRun));
    }

    // ── Contradiction detection ───────────────────────────────────────────────

    /**
     * Detect contradictory facts in the graph without resolving them.
     */
    @GetMapping("/{factSheetId}/contradictions")
    public ResponseEntity<List<Contradiction>> detectContradictions(@PathVariable Long factSheetId) {
        return ResponseEntity.ok(maintenanceService.detectContradictions(factSheetId));
    }

    /**
     * Detect and resolve contradictions using the specified strategy.
     *
     * @param strategy how to resolve contradictions (default FLAG_FOR_REVIEW)
     */
    @PostMapping("/{factSheetId}/resolve")
    public ResponseEntity<MaintenanceReport> resolveContradictions(
            @PathVariable Long factSheetId,
            @RequestParam(defaultValue = "FLAG_FOR_REVIEW") ContradictionResolutionStrategy strategy,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return ResponseEntity.ok(maintenanceService.resolveContradictions(factSheetId, strategy, dryRun));
    }

    // ── Provenance validation ─────────────────────────────────────────────────

    /**
     * Validate that all graph entities have traceable source documents.
     */
    @GetMapping("/{factSheetId}/provenance")
    public ResponseEntity<List<ProvenanceCheck>> validateProvenance(@PathVariable Long factSheetId) {
        return ResponseEntity.ok(maintenanceService.validateProvenance(factSheetId));
    }

    // ── Snapshots ─────────────────────────────────────────────────────────────

    /**
     * Take a manual snapshot of the graph state.
     *
     * @param reason human-readable description of why the snapshot was taken
     */
    @PostMapping("/{factSheetId}/snapshot")
    public ResponseEntity<GraphSnapshot> createSnapshot(
            @PathVariable Long factSheetId,
            @RequestParam(defaultValue = "manual") String reason) {
        return ResponseEntity.ok(maintenanceService.createSnapshot(factSheetId, reason));
    }

    /**
     * List all snapshots for a fact sheet, newest first.
     */
    @GetMapping("/{factSheetId}/snapshots")
    public ResponseEntity<List<GraphSnapshot>> listSnapshots(@PathVariable Long factSheetId) {
        return ResponseEntity.ok(maintenanceService.listSnapshots(factSheetId));
    }

    // ── History ───────────────────────────────────────────────────────────────

    /**
     * Retrieve the maintenance history for a specific fact sheet.
     *
     * @param limit maximum number of reports to return (default 20)
     */
    @GetMapping("/{factSheetId}/history")
    public ResponseEntity<List<MaintenanceReport>> getHistory(
            @PathVariable Long factSheetId,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(maintenanceService.getMaintenanceHistory(factSheetId, limit));
    }

    /**
     * Retrieve maintenance history across all fact sheets.
     *
     * @param limit maximum number of reports to return (default 20)
     */
    @GetMapping("/history")
    public ResponseEntity<List<MaintenanceReport>> getAllHistory(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(maintenanceService.getMaintenanceHistory(null, limit));
    }

    // ── Scheduler control ─────────────────────────────────────────────────────

    /**
     * Enable the scheduled daily maintenance run for a fact sheet.
     */
    @PostMapping("/{factSheetId}/scheduler/enable")
    public ResponseEntity<SchedulerStatus> enableScheduler(@PathVariable Long factSheetId) {
        scheduler.enable(factSheetId);
        return ResponseEntity.ok(new SchedulerStatus(true, factSheetId));
    }

    /**
     * Disable the scheduled daily maintenance run.
     */
    @PostMapping("/scheduler/disable")
    public ResponseEntity<SchedulerStatus> disableScheduler() {
        scheduler.disable();
        return ResponseEntity.ok(new SchedulerStatus(false, null));
    }

    /**
     * Query the current scheduler state.
     */
    @GetMapping("/scheduler/status")
    public ResponseEntity<SchedulerStatus> schedulerStatus() {
        return ResponseEntity.ok(
                new SchedulerStatus(scheduler.isEnabled(), scheduler.getTargetFactSheetId()));
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /** Response for scheduler control endpoints. */
    public record SchedulerStatus(boolean enabled, Long targetFactSheetId) {}
}
