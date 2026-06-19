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
import ai.kompile.core.graphrag.maintenance.model.MaintenanceReport;
import ai.kompile.core.graphrag.maintenance.model.MaintenanceSchedule;
import ai.kompile.core.graphrag.maintenance.model.MaintenanceTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Scheduled maintenance runner that fires once per day at 03:00 (server local time).
 *
 * <p>The scheduler is <em>off by default</em>: it must be explicitly enabled via
 * {@link #enable(Long)} (or via the REST endpoint
 * {@code POST /api/graph-maintenance/{factSheetId}/scheduler/enable}) before any
 * automatic runs will occur.
 *
 * <p>The target fact sheet and enabled flag are held in volatile fields so that
 * a hot REST call always observes the latest value without requiring a lock.
 */
@Slf4j
@Component
public class MaintenanceScheduler {

    private final GraphMaintenanceService maintenanceService;

    private volatile boolean enabled = false;
    private volatile Long targetFactSheetId = null;

    public MaintenanceScheduler(GraphMaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    /**
     * Enable scheduled maintenance for the given fact sheet.
     * Any subsequent cron trigger will use this ID.
     *
     * @param factSheetId the fact sheet to maintain on schedule
     */
    public void enable(Long factSheetId) {
        this.targetFactSheetId = factSheetId;
        this.enabled = true;
        log.info("Scheduled maintenance enabled for factSheetId={}", factSheetId);
    }

    /**
     * Disable scheduled maintenance.
     * The next cron trigger will be a no-op.
     */
    public void disable() {
        this.enabled = false;
        log.info("Scheduled maintenance disabled");
    }

    /** Returns {@code true} if scheduled maintenance is currently active. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Returns the fact sheet targeted by the scheduler, or {@code null} if none is set. */
    public Long getTargetFactSheetId() {
        return targetFactSheetId;
    }

    // ── Cron trigger ──────────────────────────────────────────────────────────

    /**
     * Daily maintenance run at 03:00 server local time.
     *
     * <p>Executes the standard task set (TTL sweep, orphan cleanup, confidence
     * pruning, component pruning, stats refresh) with a pre-maintenance snapshot.
     * Skipped silently when the scheduler is disabled or no target fact sheet
     * has been configured.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void runScheduledMaintenance() {
        if (!enabled || targetFactSheetId == null) {
            return;
        }

        log.info("Starting scheduled maintenance for factSheetId={}", targetFactSheetId);
        try {
            MaintenanceSchedule schedule = new MaintenanceSchedule(
                    targetFactSheetId,
                    Duration.ofDays(1),
                    List.of(
                            MaintenanceTask.TTL_SWEEP,
                            MaintenanceTask.ORPHAN_CLEANUP,
                            MaintenanceTask.CONFIDENCE_PRUNE,
                            MaintenanceTask.COMPONENT_PRUNE,
                            MaintenanceTask.STATS_REFRESH),
                    true,   // snapshotBefore
                    false   // dryRun
            );

            MaintenanceReport report = maintenanceService.runFullMaintenance(targetFactSheetId, schedule);
            log.info("Scheduled maintenance completed for factSheetId={}: {} tasks, dryRun={}",
                    targetFactSheetId, report.taskReports().size(), report.dryRun());
        } catch (Exception e) {
            log.error("Scheduled maintenance failed for factSheetId={}: {}",
                    targetFactSheetId, e.getMessage(), e);
        }
    }
}
